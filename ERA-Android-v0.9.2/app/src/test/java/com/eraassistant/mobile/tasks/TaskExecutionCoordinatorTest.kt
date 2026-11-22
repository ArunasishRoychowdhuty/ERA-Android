package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.capability.*
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.observation.ObservationSource
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskStep
import com.eraassistant.mobile.recovery.*
import com.eraassistant.mobile.statemachine.ExecutionState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class TaskExecutionCoordinatorTest {
    private val capabilityId = CapabilityId("test.dashboard")

    private class FakeAdapter(
        override val descriptor: CapabilityDescriptor,
        private val response: suspend (StructuredActionRequest) -> StructuredActionResult
    ) : CapabilityAdapter {
        var calls = 0
        override suspend fun checkAvailability(): CapabilityAvailability = CapabilityAvailability.AVAILABLE
        override fun validatePreconditions(request: StructuredActionRequest): ExecutionError? = null
        override suspend fun execute(request: StructuredActionRequest): StructuredActionResult {
            calls += 1
            return response(request)
        }
    }

    private fun plan(
        criteria: List<String> = listOf("Dashboard"),
        risk: TaskRisk = TaskRisk.LOW,
        requiredCapabilities: Set<TaskCapability> = emptySet(),
        requiresUserApproval: Boolean = false
    ) = TaskPlan(
        goal = "Open dashboard",
        steps = listOf(
            TaskStep(
                id = 1,
                title = "Open dashboard",
                description = "Open the selected dashboard",
                acceptanceCriteria = criteria,
                retryLimit = 1,
                risk = risk,
                requiredCapabilities = requiredCapabilities,
                requiresUserApproval = requiresUserApproval
            )
        ),
        note = "Coordinator integration test",
        overallRisk = risk
    )

    private fun descriptor() = CapabilityDescriptor(
        id = capabilityId,
        name = "Test Dashboard",
        description = "Test-only dashboard adapter",
        category = CapabilityCategory.DEVICE_CONTROL,
        supportedActions = setOf("test.dashboard.open")
    )

    private fun request(actionId: String = "action-1", key: String = "key-1") = StructuredActionRequest(
        actionId = actionId,
        idempotencyKey = key,
        capabilityId = capabilityId,
        actionName = "test.dashboard.open"
    )

    private fun verifiedObservation() = NormalizedObservation(
        observationId = "post-${System.nanoTime()}",
        source = ObservationSource.SYNTHETIC_TEST,
        capturedAtEpochMs = System.currentTimeMillis(),
        titleOrHeader = "Dashboard home"
    )

    private fun approvedCoordinator(
        plan: TaskPlan,
        adapter: FakeAdapter,
        journal: ExecutionJournal = ExecutionJournal(InMemoryExecutionJournalPersistence())
    ): TaskExecutionCoordinator {
        val registry = CapabilityRegistry().apply { register(adapter) }
        val coordinator = TaskExecutionCoordinator.from(
            plan = plan,
            persistence = InMemoryTaskLedgerPersistence(),
            capabilityRegistry = registry,
            executionJournal = journal
        )
        assertTrue(coordinator.start("1") is TransitionResult.Updated)
        assertTrue(coordinator.readyForReview("1", "Reviewed test action") is TransitionResult.Updated)
        assertTrue(coordinator.approve("1") is TransitionResult.Updated)
        return coordinator
    }

    @Test
    fun verifiedActionUpdatesJournalLedgerAndStateMachine() = runBlocking {
        val adapter = FakeAdapter(descriptor()) { request ->
            StructuredActionResult.success(
                request.actionId,
                outputData = mapOf("accepted" to true),
                observation = verifiedObservation()
            )
        }
        val journal = ExecutionJournal(InMemoryExecutionJournalPersistence())
        val coordinator = approvedCoordinator(plan(), adapter, journal)

        val result = coordinator.executeStep("1", request())

        assertEquals(ActionResultStatus.SUCCESS, result.status)
        assertEquals(1, adapter.calls)
        assertEquals(TaskStepState.MACHINE_VERIFIED, coordinator.ledger.get("1")?.state)
        assertEquals(ExecutionState.SUCCEEDED, coordinator.stateMachine?.getState())
        assertEquals(JournalEntryState.COMPLETED, journal.findByActionId("action-1")?.state)
    }

    @Test
    fun adapterSuccessWithoutPostObservationNeverMarksMachineVerified() = runBlocking {
        val adapter = FakeAdapter(descriptor()) { request ->
            StructuredActionResult.success(request.actionId, outputData = mapOf("message" to "Dashboard opened"))
        }
        val journal = ExecutionJournal(InMemoryExecutionJournalPersistence())
        val coordinator = approvedCoordinator(plan(), adapter, journal)

        val result = coordinator.executeStep("1", request())

        assertEquals(ActionResultStatus.SUCCESS, result.status)
        assertEquals(TaskStepState.WAITING_USER, coordinator.ledger.get("1")?.state)
        assertNotEquals(ExecutionState.SUCCEEDED, coordinator.stateMachine?.getState())
        assertEquals(JournalEntryState.WAITING_USER, journal.findByActionId("action-1")?.state)
    }

    @Test
    fun pendingStepCannotCallAdapter() = runBlocking {
        val adapter = FakeAdapter(descriptor()) { request ->
            StructuredActionResult.success(request.actionId, observation = verifiedObservation())
        }
        val coordinator = TaskExecutionCoordinator.from(
            plan = plan(),
            persistence = InMemoryTaskLedgerPersistence(),
            capabilityRegistry = CapabilityRegistry().apply { register(adapter) },
            executionJournal = ExecutionJournal(InMemoryExecutionJournalPersistence())
        )

        val result = coordinator.executeStep("1", request())

        assertEquals(ActionResultStatus.FAILURE, result.status)
        assertEquals(0, adapter.calls)
        assertTrue(coordinator.executionJournal?.allEntries().orEmpty().isEmpty())
        assertEquals(ExecutionState.CREATED, coordinator.stateMachine?.getState())
    }

    @Test
    fun journalWriteFailurePreventsAdapterDispatchAndStateAdvance() = runBlocking {
        val failingPersistence = object : ExecutionJournalPersistence {
            override fun loadAll(): List<JournalEntry> = emptyList()
            override fun save(entry: JournalEntry): Boolean = false
            override fun remove(journalId: String) = Unit
            override fun clear() = Unit
        }
        val adapter = FakeAdapter(descriptor()) { request ->
            StructuredActionResult.success(request.actionId, observation = verifiedObservation())
        }
        val coordinator = approvedCoordinator(
            plan(),
            adapter,
            ExecutionJournal(failingPersistence)
        )

        val result = coordinator.executeStep("1", request())

        assertEquals(ActionResultStatus.FAILURE, result.status)
        assertEquals(0, adapter.calls)
        assertEquals(ExecutionState.CREATED, coordinator.stateMachine?.getState())
        assertEquals(TaskStepState.APPROVED, coordinator.ledger.get("1")?.state)
    }

    @Test
    fun requestCannotUnderstateHighRiskDeclaredByThePlan() = runBlocking {
        val adapter = FakeAdapter(descriptor()) { req ->
            StructuredActionResult.success(req.actionId, observation = verifiedObservation())
        }
        val coordinator = approvedCoordinator(plan(risk = TaskRisk.HIGH, requiresUserApproval = true), adapter)
        val result = coordinator.executeStep("1", request())
        assertEquals(ActionResultStatus.FAILURE, result.status)
        assertEquals(ExecutionErrorCategory.INVALID_REQUEST, result.error?.category)
        assertEquals(0, adapter.calls)
        assertTrue(coordinator.executionJournal?.allEntries().orEmpty().isEmpty())
    }

    @Test
    fun exactOneTimeApprovalIsRequiredForHighRiskPlanAndChangedRequestIsRejected() = runBlocking {
        val adapter = FakeAdapter(descriptor()) { req ->
            StructuredActionResult.success(req.actionId, observation = verifiedObservation())
        }
        val coordinator = approvedCoordinator(plan(risk = TaskRisk.HIGH, requiresUserApproval = true), adapter)
        val observation = verifiedObservation()
        val highRiskRequest = request().copy(
            parameters = mapOf("target" to "exact"),
            targetObservationId = observation.observationId,
            requiresUserApproval = true,
            riskLevel = ActionRiskLevel.HIGH
        )
        val approvedRequest = coordinator.bindExplicitUserApproval("1", highRiskRequest, observation)
        assertNotNull(approvedRequest)
        val tampered = approvedRequest!!.copy(parameters = mapOf("target" to "different"))
        val rejected = coordinator.executeStep("1", tampered, observation)
        assertEquals(ActionResultStatus.FAILURE, rejected.status)
        assertEquals(ExecutionErrorCategory.CONSEQUENTIAL_APPROVAL_REQUIRED, rejected.error?.category)
        assertEquals(0, adapter.calls)

        val freshRequest = highRiskRequest.copy(actionId = "action-2", idempotencyKey = "key-2")
        val freshApproved = coordinator.bindExplicitUserApproval("1", freshRequest, observation)
        assertNotNull(freshApproved)
        val success = coordinator.executeStep("1", freshApproved!!, observation)
        assertEquals(ActionResultStatus.SUCCESS, success.status)
        assertEquals(1, adapter.calls)
    }

    @Test
    fun declaredPlanCapabilityMustPermitTheExactAdapterAction() = runBlocking {
        val adapter = FakeAdapter(descriptor()) { req ->
            StructuredActionResult.success(req.actionId, observation = verifiedObservation())
        }
        val coordinator = approvedCoordinator(plan(requiredCapabilities = setOf(TaskCapability.APP_LAUNCH)), adapter)
        val result = coordinator.executeStep("1", request())
        assertEquals(ActionResultStatus.FAILURE, result.status)
        assertEquals(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, result.error?.category)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun snapshotBoundUiActionRequiresFreshPreObservation() = runBlocking {
        val adapter = FakeAdapter(descriptor()) { req ->
            StructuredActionResult.success(req.actionId, observation = verifiedObservation())
        }
        val coordinator = approvedCoordinator(plan(), adapter)
        val uiRequest = request().copy(
            capabilityId = CapabilityId.NATIVE_WEBVIEW,
            actionName = "browser.webview.click"
        )
        val result = coordinator.executeStep("1", uiRequest)
        assertEquals(ActionResultStatus.FAILURE, result.status)
        assertEquals(ExecutionErrorCategory.STALE_STATE, result.error?.category)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun cancellationCancelsTheActiveAdapterCoroutineAndMarksRecovery() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val adapter = FakeAdapter(descriptor()) {
            entered.complete(Unit)
            awaitCancellation()
        }
        val coordinator = approvedCoordinator(plan(), adapter)
        val execution = launch { coordinator.executeStep("1", request()) }
        entered.await()
        coordinator.cancel()
        execution.join()
        assertTrue(execution.isCancelled)
        assertEquals(1, adapter.calls)
        assertEquals(TaskStepState.RECOVERY_REQUIRED, coordinator.ledger.get("1")?.state)
    }

    @Test
    fun sameActionCannotBeDispatchedAgainAfterMachineVerification() = runBlocking {
        val adapter = FakeAdapter(descriptor()) { request ->
            StructuredActionResult.success(request.actionId, observation = verifiedObservation())
        }
        val coordinator = approvedCoordinator(plan(), adapter)

        val first = coordinator.executeStep("1", request())
        val second = coordinator.executeStep("1", request(actionId = "action-2", key = "key-2"))

        assertEquals(ActionResultStatus.SUCCESS, first.status)
        assertEquals(ActionResultStatus.FAILURE, second.status)
        assertEquals(1, adapter.calls)
    }
}
