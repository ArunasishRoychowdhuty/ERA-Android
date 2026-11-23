package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.capability.*
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.orchestration.PlannerFeedbackLoop
import com.eraassistant.mobile.orchestration.TaskCapabilityActionPolicy
import com.eraassistant.mobile.execution.AutonomyExecutionPolicy
import com.eraassistant.mobile.execution.AutonomyGateInput
import com.eraassistant.mobile.execution.AutonomyGateDecision
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.recovery.ExecutionJournal
import com.eraassistant.mobile.recovery.JournalClaimResult
import com.eraassistant.mobile.statemachine.ExecutionState
import com.eraassistant.mobile.statemachine.TaskStateMachine
import com.eraassistant.mobile.statemachine.TaskStatePersistence
import com.eraassistant.mobile.verification.ActionVerificationReport
import com.eraassistant.mobile.verification.VerificationEngine
import com.eraassistant.mobile.verification.VerificationStatus
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/**
 * Bridges a validated planner DAG into the task ledger, capability registry,
 * state machine, verification engine, journal and bounded planner feedback loop.
 */
class TaskExecutionCoordinator(
    val plan: TaskPlan,
    val ledger: TaskExecutionLedger,
    val capabilityRegistry: CapabilityRegistry? = null,
    val stateMachine: TaskStateMachine? = null,
    val verificationEngine: VerificationEngine? = null,
    val executionJournal: ExecutionJournal? = null,
    val feedbackLoop: PlannerFeedbackLoop? = null,
    private val onTaskVerified: ((TaskStateMachine) -> Unit)? = null,
    val taskRunId: String = "taskrun_${UUID.randomUUID()}"
) {
    @Volatile
    var suggestedReplanPlan: TaskPlan? = null
        private set

    private val approvalStore = ActionApprovalStore()
    private val activeExecutionJob = AtomicReference<Job?>(null)
    @Volatile
    var activeCapabilityId: CapabilityId? = null
        private set

    fun readySteps(): List<TaskStepRecord> = ledger.readySteps()
    fun start(stepId: String) = ledger.transition(stepId, TaskStepState.OBSERVING, "Fresh screen observation requested.")
    fun readyForReview(stepId: String, summary: String) = ledger.transition(stepId, TaskStepState.READY_FOR_REVIEW, summary)
    fun approve(stepId: String) = ledger.transition(stepId, TaskStepState.APPROVED, "User approved the reviewed step.")

    /** Records a user's statement that they performed the action; ERA did not dispatch it. */
    fun reportUserPerformed(stepId: String) = ledger.transition(
        stepId,
        TaskStepState.USER_REPORTED,
        "User reports performing this step manually."
    )

    /** Records user-supplied outcome evidence without claiming independent machine verification. */
    fun confirmUserOutcome(stepId: String, evidence: String): TransitionResult {
        val cleanEvidence = evidence.trim().take(500)
        if (cleanEvidence.isBlank()) return TransitionResult.Rejected("User-confirmed outcome evidence is required.")
        val result = ledger.transition(
            stepId,
            TaskStepState.USER_CONFIRMED,
            "User-confirmed outcome evidence: $cleanEvidence"
        )
        if (result is TransitionResult.Updated &&
            stateMachine?.getState() == ExecutionState.WAITING_USER) {
            stateMachine.transitionTask(
                ExecutionState.RUNNING,
                "User completed the manual follow-through; remaining plan steps may proceed."
            )
        }
        return result
    }

    fun confirmRecoveryInspection(stepId: String, evidence: String): TransitionResult {
        val result = ledger.confirmRecoveryInspection(stepId, evidence)
        if (result is TransitionResult.Updated) {
            val machine = stateMachine
            if (machine?.getState() == ExecutionState.RECOVERY_REQUIRED) {
                machine.transitionTask(ExecutionState.READY, "User inspected the target app and confirmed recovery review.")
            }
            if (machine?.getActionProgress(stepId)?.state == ExecutionState.RECOVERY_REQUIRED) {
                machine.transitionAction(stepId, ExecutionState.READY, "User inspected target app; step requires a fresh reviewed attempt.")
            }
        }
        return result
    }

    fun fail(stepId: String, reason: String): TransitionResult =
        ledger.recordFailure(stepId, reason)

    fun cancel() {
        approvalStore.clear()
        // Cancel the actual coroutine running the adapter, not only the UI/ledger state.
        activeExecutionJob.getAndSet(null)?.cancel(CancellationException("Cancelled by user."))
        stateMachine?.cancel("Cancelled by user.")
        runCatching { ledger.cancelPending("Cancelled by user.") }
    }

    /**
     * Binds a user's explicit final approval to one exact request and current observation.
     * The returned request must be passed unchanged to executeStep; a process restart,
     * changed parameter, changed selector, new observation or replay invalidates the receipt.
     */
    fun bindExplicitUserApproval(
        stepId: String,
        request: StructuredActionRequest,
        preObservation: NormalizedObservation?
    ): StructuredActionRequest? {
        val record = ledger.get(stepId) ?: return null
        if (record.state != TaskStepState.APPROVED) return null
        val plannedStep = plan.steps.firstOrNull { it.id.toString() == stepId } ?: return null
        if (!TaskCapabilityActionPolicy.allows(plannedStep, request.capabilityId, request.actionName)) return null
        if (request.riskLevel.ordinal < plannedStep.risk.toActionRisk().ordinal) return null

        val requiresApproval = plannedStep.requiresUserApproval ||
            plannedStep.risk.ordinal >= TaskRisk.HIGH.ordinal ||
            request.requiresUserApproval ||
            request.riskLevel >= ActionRiskLevel.HIGH
        if (!requiresApproval) return request

        val observation = preObservation ?: return null
        if (observation.isStale(MAX_PRE_OBSERVATION_AGE_MS)) return null
        if (request.targetObservationId != observation.observationId) return null
        val receipt = approvalStore.issue(stepId, request, observation.observationId)
        return request.copy(approvalGranted = true, approvalReceiptId = receipt)
    }

    /**
     * Executes one already-reviewed step. It is intentionally impossible to execute
     * from PENDING/OBSERVING/READY_FOR_REVIEW. The exact structured request must be
     * supplied by a trusted local action builder; planner prose is never interpreted as code.
     */
    suspend fun executeStep(
        stepId: String,
        request: StructuredActionRequest,
        preObservation: NormalizedObservation? = null
    ): StructuredActionResult {
        val job = currentCoroutineContext()[Job] ?: return failure(
            request.actionId,
            ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Execution requires a cancellable coroutine.")
        )
        if (!activeExecutionJob.compareAndSet(null, job)) {
            return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.PRECONDITION_FAILED, "Another task step is already executing.")
            )
        }
        activeCapabilityId = request.capabilityId
        return try {
            executeStepInternal(stepId, request, preObservation)
        } finally {
            activeCapabilityId = null
            activeExecutionJob.compareAndSet(job, null)
        }
    }

    private suspend fun executeStepInternal(
        stepId: String,
        request: StructuredActionRequest,
        preObservation: NormalizedObservation?
    ): StructuredActionResult {
        val record = ledger.get(stepId) ?: return failure(
            request.actionId,
            ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Unknown task step '$stepId'.")
        )
        if (record.state != TaskStepState.APPROVED) {
            return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.PRECONDITION_FAILED, "Step must be reviewed and explicitly approved before dispatch. Current state: ${record.state}.")
            )
        }
        val plannedStep = plan.steps.firstOrNull { it.id.toString() == stepId }
            ?: return failure(request.actionId, ExecutionError(ExecutionErrorCategory.INVALID_REQUEST, "Task plan step '$stepId' does not exist."))
        if (!TaskCapabilityActionPolicy.allows(plannedStep, request.capabilityId, request.actionName)) {
            return failure(
                request.actionId,
                ExecutionError(
                    ExecutionErrorCategory.UNSUPPORTED_CAPABILITY,
                    "Action '${request.actionName}' through '${request.capabilityId}' is outside the declared capabilities for plan step '$stepId'."
                )
            )
        }
        if (request.riskLevel.ordinal < plannedStep.risk.toActionRisk().ordinal) {
            return failure(
                request.actionId,
                ExecutionError(
                    ExecutionErrorCategory.INVALID_REQUEST,
                    "Request risk '${request.riskLevel}' understates the plan risk '${plannedStep.risk}'."
                )
            )
        }
        val requiresObservationBinding = request.actionName in SNAPSHOT_BOUND_ACTIONS
        if (requiresObservationBinding && preObservation == null) {
            return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.STALE_STATE, "This UI action requires a fresh pre-action observation.")
            )
        }
        if (request.targetObservationId != null &&
            request.targetObservationId != preObservation?.observationId) {
            return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.STALE_STATE, "Request target observation does not match the supplied pre-action observation.")
            )
        }
        if (preObservation?.isStale(MAX_PRE_OBSERVATION_AGE_MS) == true) {
            ledger.markRecoveryRequired(stepId, "Pre-action observation is stale. Capture a fresh screen and review again.")
            return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.STALE_STATE, "Pre-action observation is stale.")
            )
        }
        val isConsequential = plannedStep.requiresUserApproval ||
            plannedStep.risk.ordinal >= TaskRisk.HIGH.ordinal ||
            request.requiresUserApproval ||
            request.riskLevel >= ActionRiskLevel.HIGH
        if (isConsequential &&
            (!request.approvalGranted || request.approvalReceiptId.isNullOrBlank())) {
            return failure(
                request.actionId,
                ExecutionError(
                    ExecutionErrorCategory.CONSEQUENTIAL_APPROVAL_REQUIRED,
                    "This structured action requires an exact one-time approval receipt."
                )
            )
        }

        val registry = capabilityRegistry ?: return failure(
            request.actionId,
            ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, "Capability registry is not configured.")
        )
        val journal = executionJournal ?: return failure(
            request.actionId,
            ExecutionError(ExecutionErrorCategory.ENVIRONMENT_ERROR, "Durable execution journal is not configured; dispatch is disabled.")
        )
        if (!journal.isAvailable()) {
            return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.ENVIRONMENT_ERROR, journal.unavailableReason() ?: "Execution journal is unavailable.")
            )
        }

        val adapterResult = registry.validateAndResolve(request)
        if (adapterResult.isFailure) {
            val message = adapterResult.exceptionOrNull()?.message ?: "Capability resolution failed."
            ledger.recordFailure(stepId, message)
            transitionActionIfPossible(stepId, ExecutionState.FAILED, message)
            return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, message)
            )
        }
        val adapter = adapterResult.getOrThrow()

        // Central autonomy gate runs after exact capability, schema, live permission and adapter
        // availability validation, but before journal claim or any adapter side effect.
        val exactReceiptMatch = if (isConsequential) {
            request.approvalGranted && approvalStore.matches(
                receiptId = request.approvalReceiptId,
                stepId = stepId,
                request = request,
                observationId = preObservation?.observationId
            )
        } else true
        val observationFresh = !requiresObservationBinding ||
            (preObservation != null && !preObservation.isStale(MAX_PRE_OBSERVATION_AGE_MS))
        val gateDecision = AutonomyExecutionPolicy.decide(
            AutonomyGateInput(
                risk = plannedStep.risk,
                reversible = plannedStep.reversible,
                requiresUserApproval = isConsequential,
                exactApprovalGranted = request.approvalGranted && !request.approvalReceiptId.isNullOrBlank(),
                approvalMatchesAction = exactReceiptMatch,
                approvalFresh = exactReceiptMatch,
                observationRequired = requiresObservationBinding,
                observationFresh = observationFresh,
                permissionGranted = true,
                toolAvailable = true
            )
        )
        when (gateDecision) {
            AutonomyGateDecision.ALLOW_REVERSIBLE_ACTION,
            AutonomyGateDecision.ALLOW_APPROVED_ACTION -> Unit
            AutonomyGateDecision.WAIT_FOR_EXACT_USER_APPROVAL -> return failure(
                request.actionId,
                ExecutionError(
                    ExecutionErrorCategory.CONSEQUENTIAL_APPROVAL_REQUIRED,
                    "A fresh one-time approval bound to this exact step, request and observation is required. No adapter was invoked."
                )
            )
            AutonomyGateDecision.USER_HANDOFF_REQUIRED -> {
                val prompt = "This critical-risk step must be completed by the user in the target app. ERA did not invoke an adapter."
                ledger.transition(stepId, TaskStepState.WAITING_USER, prompt)
                return StructuredActionResult.waitingUser(request.actionId, prompt)
            }
            AutonomyGateDecision.BLOCK_MISSING_PERMISSION -> return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.PERMISSION_DENIED, "Required Android permission is not granted. No adapter was invoked.")
            )
            AutonomyGateDecision.BLOCK_TOOL_UNAVAILABLE -> return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.UNSUPPORTED_CAPABILITY, "Selected adapter is unavailable. No adapter was invoked.")
            )
            AutonomyGateDecision.BLOCK_STALE_OBSERVATION -> return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.STALE_STATE, "A fresh pre-action observation is required. No adapter was invoked.")
            )
            AutonomyGateDecision.BLOCK_INVALID_APPROVAL -> return failure(
                request.actionId,
                ExecutionError(ExecutionErrorCategory.CONSEQUENTIAL_APPROVAL_REQUIRED, "Approval receipt is expired or bound to a different action. No adapter was invoked.")
            )
        }

        val fingerprint = ExecutionJournal.computeFingerprint(request.actionName, request.parameters)
        val claim = journal.claim(
            taskId = stateMachine?.taskId ?: "task_${fingerprint(plan).take(16)}",
            actionId = request.actionId,
            idempotencyKey = request.idempotencyKey,
            actionFingerprint = fingerprint,
            actionName = request.actionName,
            isConsequential = isConsequential
        )

        val journalEntryId = when (claim) {
            JournalClaimResult.Acquired -> journal.findByActionId(request.actionId)?.journalId
            is JournalClaimResult.Conflict -> {
                ledger.markRecoveryRequired(stepId, claim.message)
                transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, claim.message)
                return failure(request.actionId, ExecutionError(ExecutionErrorCategory.IDEMPOTENCY_CONFLICT, claim.message))
            }
            is JournalClaimResult.RecoveryRequired -> {
                ledger.markRecoveryRequired(stepId, claim.reason)
                transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, claim.reason)
                return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, claim.reason))
            }
            is JournalClaimResult.Existing -> {
                val message = "A previous attempt completed with this idempotency key, but its result is not cached for replay."
                return failure(request.actionId, ExecutionError(ExecutionErrorCategory.IDEMPOTENCY_CONFLICT, message))
            }
            is JournalClaimResult.PersistenceFailure -> {
                return failure(request.actionId, ExecutionError(ExecutionErrorCategory.ENVIRONMENT_ERROR, claim.reason))
            }
        }
        if (journalEntryId == null) {
            ledger.markRecoveryRequired(stepId, "Journal claim was not readable after durable commit.")
            transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, "Journal entry lookup failed.")
            return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, "Journal entry could not be resolved after claim."))
        }

        val checkpoint = listOf(
            "step=$stepId",
            "capability=${request.capabilityId.value}",
            "action=${request.actionName}",
            "observation=${preObservation?.observationId.orEmpty()}",
            "fingerprint=$fingerprint"
        ).joinToString(";").take(1_500)
        if (!journal.recordCheckpoint(journalEntryId, checkpoint)) {
            val reason = "Execution checkpoint could not be durably committed. No adapter was invoked."
            journal.markRecoveryRequired(journalEntryId, reason)
            ledger.markRecoveryRequired(stepId, reason)
            return failure(request.actionId, ExecutionError(ExecutionErrorCategory.ENVIRONMENT_ERROR, reason))
        }

        if (isConsequential && !approvalStore.consume(
                receiptId = request.approvalReceiptId,
                stepId = stepId,
                request = request,
                observationId = preObservation?.observationId
            )) {
            val reason = "Exact one-time action approval was missing, expired, changed or already consumed. No adapter was invoked."
            journal.markFailed(journalEntryId, reason)
            return failure(request.actionId, ExecutionError(ExecutionErrorCategory.CONSEQUENTIAL_APPROVAL_REQUIRED, reason))
        }

        // Persist the dispatch claim before advancing runtime state or invoking the adapter.
        val stateReady = prepareStateMachineForDispatch(stepId)
        if (!stateReady) {
            val reason = "Task/action state machine rejected the RUNNING transition. No adapter was invoked."
            journal.markFailed(journalEntryId, reason)
            ledger.recordFailure(stepId, reason)
            return failure(request.actionId, ExecutionError(ExecutionErrorCategory.PRECONDITION_FAILED, reason))
        }

        val result = try {
            withTimeout(request.timeoutMs) {
                registry.validateOutput(request, adapter.execute(request))
            }
        } catch (timeout: TimeoutCancellationException) {
            val reason = "Action timed out after ${request.timeoutMs}ms. External side effects may have partially occurred; inspect the target before retrying."
            journal.markRecoveryRequired(journalEntryId, reason)
            ledger.markRecoveryRequired(stepId, reason)
            transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, reason)
            return failure(request.actionId, ExecutionError(ExecutionErrorCategory.TIMEOUT, reason, retryable = false))
        } catch (cancelled: CancellationException) {
            val reason = "Execution was cancelled while an adapter may have been active. Inspect the target before retrying."
            journal.markRecoveryRequired(journalEntryId, reason)
            ledger.markRecoveryRequired(stepId, reason)
            transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, reason)
            throw cancelled
        } catch (error: Throwable) {
            StructuredActionResult.failure(
                actionId = request.actionId,
                error = ExecutionError(
                    ExecutionErrorCategory.ADAPTER_EXECUTION_FAILURE,
                    "Capability adapter failed: ${(error.message ?: error::class.simpleName ?: "unknown").take(180)}",
                    cause = error
                )
            )
        }

        if (result.status == ActionResultStatus.WAITING_USER) {
            val prompt = result.outputData["userPrompt"]?.toString() ?: "Waiting for user action."
            if (!journal.markWaitingForUser(journalEntryId, prompt)) {
                val reason = "Journal could not persist WAITING_USER state; inspect target before proceeding."
                ledger.markRecoveryRequired(stepId, reason)
                transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, reason)
                return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, reason))
            }
            val stateUpdated = transitionActionIfPossible(stepId, ExecutionState.WAITING_USER, prompt)
            val ledgerResult = ledger.transition(stepId, TaskStepState.WAITING_USER, prompt)
            if (!stateUpdated || ledgerResult is TransitionResult.Rejected) {
                val reason = "Waiting-user state could not be synchronized. Inspect target app."
                journal.markRecoveryRequired(journalEntryId, reason)
                ledger.markRecoveryRequired(stepId, reason)
                return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, reason))
            }
            return result
        }

        if (result.status == ActionResultStatus.CANCELLED) {
            val reason = "Adapter reported cancellation; the external outcome is not independently known."
            journal.markRecoveryRequired(journalEntryId, reason)
            ledger.markRecoveryRequired(stepId, reason)
            transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, reason)
            return result
        }

        val currentStateUpdated = transitionActionIfPossible(stepId, ExecutionState.OBSERVING, "Collecting independent post-action evidence.")
        if (!currentStateUpdated) {
            val reason = "State machine rejected OBSERVING after adapter dispatch. Inspect target app."
            journal.markRecoveryRequired(journalEntryId, reason)
            ledger.markRecoveryRequired(stepId, reason)
            return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, reason))
        }

        // No fallback to preObservation: only a distinct post-action observation can verify an outcome.
        val criteria = plan.steps.firstOrNull { it.id.toString() == stepId }?.acceptanceCriteria.orEmpty()
        val report = (verificationEngine ?: VerificationEngine()).verify(
            actionId = request.actionId,
            expectedCriteria = criteria,
            preObservation = preObservation,
            postObservation = result.observation,
            actionResult = result,
            retryCount = record.retryCount,
            maxRetries = record.step.retryLimit
        )

        return applyVerificationResult(
            stepId = stepId,
            request = request,
            result = result,
            report = report,
            journal = journal,
            journalEntryId = journalEntryId
        )
    }

    private suspend fun applyVerificationResult(
        stepId: String,
        request: StructuredActionRequest,
        result: StructuredActionResult,
        report: ActionVerificationReport,
        journal: ExecutionJournal,
        journalEntryId: String
    ): StructuredActionResult {
        when (report.overallStatus) {
            VerificationStatus.VERIFIED -> {
                if (!journal.markCompleted(journalEntryId)) {
                    val reason = "Action evidence looked successful, but journal completion could not be durably committed."
                    journal.markRecoveryRequired(journalEntryId, reason)
                    ledger.markRecoveryRequired(stepId, reason)
                    transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, reason)
                    return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, reason))
                }
                if (!transitionActionIfPossible(stepId, ExecutionState.VERIFIED, report.summary, verificationEvidence = report.summary) ||
                    !transitionActionIfPossible(stepId, ExecutionState.SUCCEEDED, "Action independently verified.")) {
                    val reason = "Verified result could not be reflected in the state machine. Journal is completed; inspect task history."
                    ledger.markRecoveryRequired(stepId, reason)
                    return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, reason))
                }
                val ledgerResult = ledger.transition(stepId, TaskStepState.MACHINE_VERIFIED, report.summary)
                if (ledgerResult is TransitionResult.Rejected) {
                    val reason = "Verified result could not be committed to the task ledger: ${ledgerResult.reason}"
                    return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, reason))
                }
                completeTaskStateIfAllDone()
            }
            VerificationStatus.RETRY_REQUIRED -> {
                val note = "Verification did not establish the expected result: ${report.summary}"
                if (!journal.recordRetryAttempt(journalEntryId, note) || !journal.markFailed(journalEntryId, note)) {
                    val reason = "Could not durably store the failed action outcome. Inspect the target app before another attempt."
                    ledger.markRecoveryRequired(stepId, reason)
                    transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, reason)
                    return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, reason))
                }
                val stateOk = transitionActionIfPossible(stepId, ExecutionState.RETRYABLE_FAILURE, note, result.error)
                val ledgerResult = ledger.recordFailure(stepId, note)
                if (!stateOk || ledgerResult is TransitionResult.Rejected) {
                    ledger.markRecoveryRequired(stepId, "Retry state could not be synchronized. Inspect target app.")
                }
                requestBoundedReplan(stepId, report, result.observation)
            }
            VerificationStatus.REPLAN_REQUIRED, VerificationStatus.NOT_VERIFIED -> {
                val note = "Action outcome was not verified: ${report.summary}"
                if (!journal.markFailed(journalEntryId, note)) {
                    val reason = "Could not durably store the unverified action outcome. Inspect the target app before another attempt."
                    ledger.markRecoveryRequired(stepId, reason)
                    transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, reason)
                    return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, reason))
                }
                transitionActionIfPossible(stepId, ExecutionState.FAILED, note, result.error)
                ledger.recordFailure(stepId, note)
                requestBoundedReplan(stepId, report, result.observation)
            }
            VerificationStatus.VERIFICATION_UNAVAILABLE -> {
                val note = "Adapter returned, but independent verification is unavailable: ${report.summary}"
                if (!journal.markWaitingForUser(journalEntryId, note)) {
                    val reason = "Journal could not persist the unverified action outcome."
                    ledger.markRecoveryRequired(stepId, reason)
                    transitionActionIfPossible(stepId, ExecutionState.RECOVERY_REQUIRED, reason)
                    return failure(request.actionId, ExecutionError(ExecutionErrorCategory.RECOVERY_REQUIRED, reason))
                }
                transitionActionIfPossible(stepId, ExecutionState.WAITING_USER, note)
                val ledgerResult = ledger.transition(stepId, TaskStepState.WAITING_USER, note)
                if (ledgerResult is TransitionResult.Rejected) {
                    ledger.markRecoveryRequired(stepId, "Task ledger rejected verification-unavailable state: ${ledgerResult.reason}")
                }
            }
        }
        return result
    }

    private suspend fun requestBoundedReplan(
        stepId: String,
        report: ActionVerificationReport,
        observation: NormalizedObservation?
    ) {
        val loop = feedbackLoop ?: return
        val machine = stateMachine ?: return
        if (!loop.canReplan()) return
        val result = try {
            loop.requestReplan(machine, stepId, report, observation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
        if (result.isSuccess) suggestedReplanPlan = result.getOrNull()
    }

    private fun prepareStateMachineForDispatch(stepId: String): Boolean {
        val machine = stateMachine ?: return true
        when (machine.getState()) {
            ExecutionState.CREATED -> {
                if (machine.transitionTask(ExecutionState.VALIDATING) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return false
                if (machine.transitionTask(ExecutionState.READY) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return false
                if (machine.transitionTask(ExecutionState.RUNNING) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return false
            }
            ExecutionState.VALIDATING -> {
                if (machine.transitionTask(ExecutionState.READY) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return false
                if (machine.transitionTask(ExecutionState.RUNNING) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return false
            }
            ExecutionState.READY -> if (machine.transitionTask(ExecutionState.RUNNING) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return false
            ExecutionState.RETRYABLE_FAILURE, ExecutionState.RECOVERY_REQUIRED -> {
                if (machine.transitionTask(ExecutionState.READY) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return false
                if (machine.transitionTask(ExecutionState.RUNNING) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return false
            }
            ExecutionState.RUNNING -> Unit
            ExecutionState.OBSERVING -> if (machine.transitionTask(ExecutionState.RUNNING) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return false
            else -> return false
        }
        val actionState = machine.getActionProgress(stepId)?.state ?: return false
        return when (actionState) {
            ExecutionState.CREATED -> {
                machine.transitionAction(stepId, ExecutionState.VALIDATING) is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted &&
                    machine.transitionAction(stepId, ExecutionState.READY) is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted &&
                    machine.transitionAction(stepId, ExecutionState.RUNNING, "Executing ${stepId}.") is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted
            }
            ExecutionState.READY -> machine.transitionAction(stepId, ExecutionState.RUNNING, "Executing ${stepId}.") is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted
            ExecutionState.RETRYABLE_FAILURE,
            ExecutionState.RECOVERY_REQUIRED,
            ExecutionState.FAILED -> {
                machine.transitionAction(stepId, ExecutionState.READY, "Reviewed recovery/replan for ${stepId}.") is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted &&
                    machine.transitionAction(stepId, ExecutionState.RUNNING, "Reviewed retry ${stepId}.") is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted
            }
            else -> false
        }
    }

    private fun transitionActionIfPossible(
        stepId: String,
        state: ExecutionState,
        reason: String,
        error: ExecutionError? = null,
        verificationEvidence: String? = null
    ): Boolean = stateMachine?.let {
        it.transitionAction(stepId, state, reason, error = error, verificationEvidence = verificationEvidence) is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted
    } ?: true

    private fun completeTaskStateIfAllDone() {
        val machine = stateMachine ?: return
        // User attestation is not independent machine verification. Only a task whose
        // every step reached MACHINE_VERIFIED may enter the machine SUCCEEDED state.
        if (!ledger.allMachineVerified()) return
        if (machine.getState() == ExecutionState.RUNNING) {
            if (machine.transitionTask(ExecutionState.OBSERVING) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return
        }
        if (machine.getState() == ExecutionState.OBSERVING) {
            if (machine.transitionTask(ExecutionState.VERIFIED) !is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) return
        }
        if (machine.getState() == ExecutionState.VERIFIED) {
            val transition = machine.transitionTask(ExecutionState.SUCCEEDED, "All task steps were independently machine-verified.")
            if (transition is com.eraassistant.mobile.statemachine.TransitionOutcome.Accepted) {
                onTaskVerified?.invoke(machine)
            }
        }
    }

    private fun failure(actionId: String, error: ExecutionError) =
        StructuredActionResult.failure(actionId, error)

    companion object {
        private const val MAX_PRE_OBSERVATION_AGE_MS = 15_000L
        private val SNAPSHOT_BOUND_ACTIONS = setOf(
            "accessibility.ui.approve_click",
            "accessibility.ui.approve_type",
            "accessibility.ui.approve_scroll",
            "browser.chrome.approve_click",
            "browser.chrome.approve_type",
            "browser.chrome.approve_scroll",
            "browser.webview.click",
            "browser.webview.fill",
            "browser.webview.type",
            "browser.webview.press_key",
            "browser.webview.check",
            "browser.webview.uncheck",
            "browser.webview.select_option",
            "browser.webview.scroll_into_view"
        )

        private fun TaskRisk.toActionRisk(): ActionRiskLevel = when (this) {
            TaskRisk.LOW -> ActionRiskLevel.LOW
            TaskRisk.MEDIUM -> ActionRiskLevel.MEDIUM
            TaskRisk.HIGH -> ActionRiskLevel.HIGH
            TaskRisk.CRITICAL -> ActionRiskLevel.CRITICAL
        }

        fun from(
            plan: TaskPlan,
            persistence: TaskLedgerPersistence? = null,
            capabilityRegistry: CapabilityRegistry? = null,
            stateMachine: TaskStateMachine? = null,
            verificationEngine: VerificationEngine? = null,
            executionJournal: ExecutionJournal? = null,
            feedbackLoop: PlannerFeedbackLoop? = null,
            onTaskVerified: ((TaskStateMachine) -> Unit)? = null,
            statePersistence: TaskStatePersistence? = null,
            taskRunId: String = "taskrun_${UUID.randomUUID()}",
            resumeLegacyFingerprintRun: Boolean = false
        ): TaskExecutionCoordinator {
            val errors = plan.validate()
            require(errors.isEmpty()) { "Invalid task plan: ${errors.joinToString()}" }
            require(taskRunId.isNotBlank() && taskRunId.length <= 128) { "Task run ID must be between 1 and 128 characters." }
            val steps = plan.steps.map { step ->
                AgentTaskStep(
                    id = step.id.toString(),
                    instruction = step.description,
                    expectedResult = step.acceptanceCriteria.joinToString("; "),
                    dependsOn = step.dependsOn.map { it.toString() }.toSet(),
                    retryLimit = step.retryLimit,
                    requiresFreshApproval = true,
                    requiredCapabilities = step.requiredCapabilities,
                    risk = step.risk,
                    reversible = step.reversible,
                    requiresUserApproval = step.requiresUserApproval
                )
            }
            val planFingerprint = fingerprint(plan)
            // New runs always get isolated durable namespaces. Existing fingerprint-only
            // stores remain accessible only through an explicit legacy-resume request.
            val ledgerNamespace = if (resumeLegacyFingerprintRun) planFingerprint else taskRunId
            val ledger = TaskExecutionLedger(steps, persistence, ledgerNamespace)
            val records = ledger.all()
            val restoredActionStates = records.associate { record ->
                record.step.id to when (record.state) {
                    TaskStepState.MACHINE_VERIFIED -> ExecutionState.SUCCEEDED
                    TaskStepState.FAILED, TaskStepState.REJECTED -> ExecutionState.FAILED
                    TaskStepState.NEEDS_REPLAN -> ExecutionState.RETRYABLE_FAILURE
                    TaskStepState.RECOVERY_REQUIRED,
                    TaskStepState.OBSERVING,
                    TaskStepState.READY_FOR_REVIEW,
                    TaskStepState.APPROVED,
                    TaskStepState.WAITING_USER,
                    TaskStepState.USER_REPORTED -> ExecutionState.RECOVERY_REQUIRED
                    TaskStepState.CANCELLED -> ExecutionState.CANCELLED
                    else -> ExecutionState.CREATED
                }
            }
            val restoredTaskState = when {
                records.any { it.state == TaskStepState.RECOVERY_REQUIRED } -> ExecutionState.RECOVERY_REQUIRED
                records.any { it.state == TaskStepState.FAILED || it.state == TaskStepState.REJECTED } -> ExecutionState.FAILED
                records.any { it.state == TaskStepState.NEEDS_REPLAN } -> ExecutionState.RETRYABLE_FAILURE
                records.isNotEmpty() && records.all { it.state == TaskStepState.MACHINE_VERIFIED } -> ExecutionState.SUCCEEDED
                records.isNotEmpty() && records.all { it.state == TaskStepState.CANCELLED } -> ExecutionState.CANCELLED
                else -> ExecutionState.CREATED
            }
            val sm = stateMachine ?: TaskStateMachine(
                taskId = if (resumeLegacyFingerprintRun) "task_${planFingerprint.take(8)}" else "task_$taskRunId",
                goal = plan.goal,
                initialActions = plan.steps.map { it.id.toString() to it.title },
                initialState = restoredTaskState,
                initialActionStates = restoredActionStates,
                initialActionMaxRetries = plan.steps.associate { it.id.toString() to it.retryLimit },
                persistence = statePersistence
            )
            return TaskExecutionCoordinator(
                plan = plan,
                ledger = ledger,
                capabilityRegistry = capabilityRegistry,
                stateMachine = sm,
                verificationEngine = verificationEngine ?: VerificationEngine(),
                executionJournal = executionJournal,
                feedbackLoop = feedbackLoop,
                onTaskVerified = onTaskVerified,
                taskRunId = if (resumeLegacyFingerprintRun) "legacy_$planFingerprint" else taskRunId
            )
        }

        /** Every execution-relevant field contributes to the persistence namespace. */
        fun fingerprint(plan: TaskPlan): String {
            val canonical = buildString {
                appendField(plan.planVersion)
                appendField(plan.goal)
                appendField(plan.overallRisk.name)
                appendFields(plan.acceptanceCriteria)
                plan.steps.sortedBy { it.id }.forEach { step ->
                    appendField(step.id.toString())
                    appendField(step.title)
                    appendField(step.description)
                    appendFields(step.dependsOn.sorted().map { it.toString() })
                    appendFields(step.requiredCapabilities.map { it.name }.sorted())
                    appendFields(step.acceptanceCriteria)
                    appendField(step.retryLimit.toString())
                    appendField(step.risk.name)
                    appendField(step.reversible.toString())
                    appendField(step.requiresUserApproval.toString())
                }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun StringBuilder.appendField(value: String) {
            append(value.length).append(':').append(value).append('|')
        }

        private fun StringBuilder.appendFields(values: Collection<String>) {
            append(values.size).append('[')
            values.forEach { appendField(it) }
            append(']')
        }
    }
}
