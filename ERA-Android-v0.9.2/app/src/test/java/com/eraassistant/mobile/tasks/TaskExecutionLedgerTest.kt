package com.eraassistant.mobile.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionLedgerTest {
    @Test fun consequentialStepCannotSkipUserApproval() {
        val ledger = TaskExecutionLedger(listOf(AgentTaskStep("send", "Send message", "Message sent")))
        assertTrue(ledger.transition("send", TaskStepState.OBSERVING) is TransitionResult.Updated)
        assertTrue(ledger.transition("send", TaskStepState.READY_FOR_REVIEW) is TransitionResult.Updated)
        assertTrue(ledger.transition("send", TaskStepState.USER_REPORTED) is TransitionResult.Rejected)
    }

    @Test fun userReportedStepNeedsOutcomeEvidenceBeforeConfirmation() {
        val ledger = TaskExecutionLedger(listOf(AgentTaskStep("draft", "Prepare draft", "Draft visible")))
        ledger.transition("draft", TaskStepState.OBSERVING)
        ledger.transition("draft", TaskStepState.READY_FOR_REVIEW)
        assertTrue(ledger.transition("draft", TaskStepState.APPROVED) is TransitionResult.Updated)
        assertTrue(ledger.transition("draft", TaskStepState.USER_REPORTED) is TransitionResult.Updated)
        assertTrue(ledger.transition("draft", TaskStepState.USER_CONFIRMED) is TransitionResult.Rejected)
        assertTrue(ledger.transition("draft", TaskStepState.USER_CONFIRMED, "I checked the draft in the target app.") is TransitionResult.Updated)
        assertTrue(ledger.allUserConfirmed())
    }

    @Test fun dependentStepCannotStartBeforeDependencyHasUserConfirmedOutcome() {
        val ledger = TaskExecutionLedger(listOf(
            AgentTaskStep("open", "Open app", "App open"),
            AgentTaskStep("draft", "Prepare draft", "Draft visible", dependsOn = setOf("open"))
        ))
        assertEquals(listOf("open"), ledger.readySteps().map { it.step.id })
        assertTrue(ledger.transition("draft", TaskStepState.OBSERVING) is TransitionResult.Rejected)
        ledger.transition("open", TaskStepState.OBSERVING)
        ledger.transition("open", TaskStepState.READY_FOR_REVIEW)
        ledger.transition("open", TaskStepState.APPROVED)
        ledger.transition("open", TaskStepState.USER_REPORTED)
        ledger.transition("open", TaskStepState.USER_CONFIRMED, "I checked the app is open.")
        assertEquals(listOf("draft"), ledger.readySteps().map { it.step.id })
    }

    @Test fun retryBudgetIsBounded() {
        val ledger = TaskExecutionLedger(listOf(AgentTaskStep("step", "Observe", "Visible", retryLimit = 1)))
        ledger.transition("step", TaskStepState.OBSERVING)
        assertEquals(TaskStepState.NEEDS_REPLAN, (ledger.recordFailure("step", "first") as TransitionResult.Updated).record.state)
        ledger.transition("step", TaskStepState.OBSERVING, "Replanned after first failure.")
        assertEquals(TaskStepState.FAILED, (ledger.recordFailure("step", "second") as TransitionResult.Updated).record.state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dependencyCyclesAreRejected() {
        TaskExecutionLedger(listOf(
            AgentTaskStep("a", "A", "A", dependsOn = setOf("b")),
            AgentTaskStep("b", "B", "B", dependsOn = setOf("a"))
        ))
    }

    @Test fun reviewedStepRequiresFreshObservationAfterProcessRecreation() {
        val persistence = InMemoryTaskLedgerPersistence()
        val steps = listOf(AgentTaskStep("one", "Inspect screen", "Screen is understood"))
        val firstRun = TaskExecutionLedger(steps, persistence, "resume-test")
        firstRun.transition("one", TaskStepState.OBSERVING)
        firstRun.transition("one", TaskStepState.READY_FOR_REVIEW, "Old screen proposal reviewed")

        val restored = TaskExecutionLedger(steps, persistence, "resume-test")
        assertEquals(TaskStepState.RECOVERY_REQUIRED, restored.get("one")?.state)
        assertTrue(restored.get("one")?.note.orEmpty().contains("fresh observation"))
        assertTrue(restored.readySteps().isEmpty())
        assertTrue(restored.confirmRecoveryInspection("one", "Checked that no navigation was completed.") is TransitionResult.Updated)
        assertEquals(TaskStepState.NEEDS_REPLAN, restored.get("one")?.state)
        assertEquals("one", restored.readySteps().single().step.id)
    }

    @Test fun approvalIsNotReusedAfterProcessRecreation() {
        val persistence = InMemoryTaskLedgerPersistence()
        val steps = listOf(AgentTaskStep("one", "Inspect screen", "Screen is understood"))
        val firstRun = TaskExecutionLedger(steps, persistence, "approved-resume")
        firstRun.transition("one", TaskStepState.OBSERVING)
        firstRun.transition("one", TaskStepState.READY_FOR_REVIEW, "Reviewed")
        firstRun.transition("one", TaskStepState.APPROVED, "Approved")

        val restored = TaskExecutionLedger(steps, persistence, "approved-resume")
        assertEquals(TaskStepState.RECOVERY_REQUIRED, restored.get("one")?.state)
        assertTrue(restored.transition("one", TaskStepState.USER_REPORTED) is TransitionResult.Rejected)
        assertTrue(restored.confirmRecoveryInspection("one", "Inspected the target app.") is TransitionResult.Updated)
        assertTrue(restored.transition("one", TaskStepState.USER_REPORTED) is TransitionResult.Rejected)
    }

    @Test fun inFlightStepRequiresFreshObservationAfterRestore() {
        val persistence = InMemoryTaskLedgerPersistence()
        val steps = listOf(AgentTaskStep("one", "Inspect screen", "Screen understood"))
        val firstRun = TaskExecutionLedger(steps, persistence, "in-flight")
        firstRun.transition("one", TaskStepState.OBSERVING)

        val restored = TaskExecutionLedger(steps, persistence, "in-flight")
        assertEquals(TaskStepState.RECOVERY_REQUIRED, restored.get("one")?.state)
        assertTrue(restored.readySteps().isEmpty())
        assertTrue(restored.confirmRecoveryInspection("one", "Target app was inspected.") is TransitionResult.Updated)
        assertEquals("one", restored.readySteps().single().step.id)
    }

    @Test fun cancelStopsAllUnfinishedStepsButKeepsConfirmedOnes() {
        val ledger = TaskExecutionLedger(listOf(
            AgentTaskStep("done", "Done", "Done"),
            AgentTaskStep("pending", "Pending", "Pending")
        ))
        ledger.transition("done", TaskStepState.OBSERVING)
        ledger.transition("done", TaskStepState.READY_FOR_REVIEW)
        ledger.transition("done", TaskStepState.APPROVED)
        ledger.transition("done", TaskStepState.USER_REPORTED)
        ledger.transition("done", TaskStepState.USER_CONFIRMED, "I checked the result.")
        ledger.cancelPending()
        assertEquals(TaskStepState.USER_CONFIRMED, ledger.get("done")?.state)
        assertEquals(TaskStepState.CANCELLED, ledger.get("pending")?.state)
    }

    @Test fun machineVerifiedStepSatisfiesDependenciesWithoutPretendingItWasUserConfirmed() {
        val ledger = TaskExecutionLedger(listOf(
            AgentTaskStep("first", "Open page", "Page open"),
            AgentTaskStep("second", "Inspect page", "Page inspected", dependsOn = setOf("first"))
        ))
        ledger.transition("first", TaskStepState.OBSERVING)
        ledger.transition("first", TaskStepState.READY_FOR_REVIEW)
        ledger.transition("first", TaskStepState.APPROVED)
        assertTrue(ledger.transition("first", TaskStepState.MACHINE_VERIFIED, "Fresh observation matched expected page title.") is TransitionResult.Updated)
        assertEquals(TaskStepState.MACHINE_VERIFIED, ledger.get("first")?.state)
        assertEquals("second", ledger.readySteps().single().step.id)
    }

    @Test fun rejectedStepCanReturnToReview() {
        val ledger = TaskExecutionLedger(listOf(AgentTaskStep("one", "Review", "Reviewed")))
        ledger.transition("one", TaskStepState.OBSERVING)
        ledger.transition("one", TaskStepState.READY_FOR_REVIEW)
        assertTrue(ledger.transition("one", TaskStepState.REJECTED, "Not approved.") is TransitionResult.Updated)
        assertTrue(ledger.transition("one", TaskStepState.NEEDS_REPLAN, "Reconsider step.") is TransitionResult.Updated)
        assertEquals("one", ledger.readySteps().single().step.id)
    }
}
