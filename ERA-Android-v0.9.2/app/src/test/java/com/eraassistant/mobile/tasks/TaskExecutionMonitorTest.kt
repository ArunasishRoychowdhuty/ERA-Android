package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.planner.PreviewPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskExecutionMonitorTest {
    @Test fun planWithConsequentialStepWaitsForUserApproval() = runBlocking {
        val plan = PreviewPlanner().createPlan("Instagram-এ পোস্ট কর")
        val coordinator = TaskExecutionCoordinator.from(plan)
        val snapshot = TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all(), 123L)
        assertEquals(TaskMonitorPhase.PLANNING, snapshot.phase)
        assertEquals(plan.steps.size, snapshot.totalSteps)
        assertEquals(0, snapshot.userConfirmedSteps)
        assertEquals(0, snapshot.waitingApprovalSteps)
        assertTrue(snapshot.toDisplayText().contains("does not dispatch"))
    }

    @Test fun monitorShowsUserConfirmedProgress() = runBlocking {
        val plan = PreviewPlanner().createPlan("WhatsApp-এ message draft তৈরি কর")
        val coordinator = TaskExecutionCoordinator.from(plan)
        coordinator.start("1")
        coordinator.readyForReview("1", "WhatsApp opened")
        coordinator.approve("1")
        coordinator.reportUserPerformed("1")
        coordinator.confirmUserOutcome("1", "I checked WhatsApp is foreground.")
        val snapshot = TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all())
        assertEquals(1, snapshot.userConfirmedSteps)
        assertTrue(snapshot.totalSteps > snapshot.userConfirmedSteps)
        assertTrue(snapshot.detail.contains("not independently verified"))
    }

    @Test fun monitorShowsReviewAndOutcomeConfirmationPhases() = runBlocking {
        val plan = PreviewPlanner().createPlan("Instagram-এ পোস্ট কর")
        val coordinator = TaskExecutionCoordinator.from(plan)
        coordinator.start("1")
        assertEquals(TaskMonitorPhase.UNDERSTANDING, TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all()).phase)
        coordinator.readyForReview("1", "Reviewed")
        assertEquals(TaskMonitorPhase.WAITING_FOR_APPROVAL, TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all()).phase)
        coordinator.approve("1")
        assertEquals(TaskMonitorPhase.READY_FOR_DISPATCH, TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all()).phase)
        coordinator.reportUserPerformed("1")
        assertEquals(TaskMonitorPhase.VERIFYING, TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all()).phase)
    }

    @Test fun monitorExposesReplanRejectedAndCancelledCounts() = runBlocking {
        val plan = PreviewPlanner().createPlan("Open browser and search")
        val coordinator = TaskExecutionCoordinator.from(plan)
        coordinator.start("1")
        coordinator.readyForReview("1", "Reviewed")
        coordinator.ledger.transition("1", TaskStepState.REJECTED, "User declined this step.")
        val rejected = TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all())
        assertEquals(TaskMonitorPhase.NEEDS_REPLAN, rejected.phase)
        assertEquals(1, rejected.rejectedSteps)

        coordinator.cancel()
        val cancelled = TaskExecutionMonitor.snapshot(plan, coordinator.ledger.all())
        assertEquals(TaskMonitorPhase.CANCELLED, cancelled.phase)
        assertTrue(cancelled.cancelledSteps > 0)
    }

    @Test fun changedPlanDoesNotReusePreviousTaskProgress() = runBlocking {
        val original = PreviewPlanner().createPlan("WhatsApp-এ draft তৈরি কর")
        val changed = original.copy(
            steps = original.steps.mapIndexed { index, step ->
                if (index == 0) step.copy(description = step.description + " Changed plan intent.") else step
            }
        )
        val persistence = InMemoryTaskLedgerPersistence()
        val first = TaskExecutionCoordinator.from(original, persistence)
        first.start("1")
        val resumed = TaskExecutionCoordinator.from(changed, persistence)
        assertEquals(TaskStepState.PENDING, resumed.ledger.get("1")?.state)
    }
}
