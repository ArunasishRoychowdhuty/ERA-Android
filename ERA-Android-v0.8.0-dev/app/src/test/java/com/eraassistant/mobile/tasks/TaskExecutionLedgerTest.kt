package com.eraassistant.mobile.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutionLedgerTest {
    @Test fun consequentialStepCannotSkipUserApproval() {
        val ledger = TaskExecutionLedger(listOf(AgentTaskStep("send", "Send message", "Message sent")))
        assertTrue(ledger.transition("send", TaskStepState.OBSERVING) is TransitionResult.Updated)
        assertTrue(ledger.transition("send", TaskStepState.READY_FOR_REVIEW) is TransitionResult.Updated)
        assertTrue(ledger.transition("send", TaskStepState.EXECUTION_REPORTED) is TransitionResult.Rejected)
    }

    @Test fun approvedStepCanBeMarkedAndVerified() {
        val ledger = TaskExecutionLedger(listOf(AgentTaskStep("draft", "Prepare draft", "Draft visible")))
        ledger.transition("draft", TaskStepState.OBSERVING)
        ledger.transition("draft", TaskStepState.READY_FOR_REVIEW)
        assertTrue(ledger.transition("draft", TaskStepState.APPROVED) is TransitionResult.Updated)
        assertTrue(ledger.transition("draft", TaskStepState.EXECUTION_REPORTED) is TransitionResult.Updated)
        assertTrue(ledger.transition("draft", TaskStepState.VERIFIED) is TransitionResult.Updated)
        assertTrue(ledger.allVerified())
    }

    @Test fun dependentStepCannotStartBeforeDependencyVerified() {
        val ledger = TaskExecutionLedger(listOf(
            AgentTaskStep("open", "Open app", "App open"),
            AgentTaskStep("draft", "Prepare draft", "Draft visible", dependsOn = setOf("open"))
        ))
        assertEquals(listOf("open"), ledger.readySteps().map { it.step.id })
        assertTrue(ledger.transition("draft", TaskStepState.OBSERVING) is TransitionResult.Rejected)
        ledger.transition("open", TaskStepState.OBSERVING)
        ledger.transition("open", TaskStepState.READY_FOR_REVIEW)
        ledger.transition("open", TaskStepState.APPROVED)
        ledger.transition("open", TaskStepState.EXECUTION_REPORTED)
        ledger.transition("open", TaskStepState.VERIFIED)
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

    @Test fun taskStateCanBeRestoredFromPersistence() {
        val persistence = InMemoryTaskLedgerPersistence()
        val steps = listOf(AgentTaskStep("one", "Inspect screen", "Screen is understood"))
        val firstRun = TaskExecutionLedger(steps, persistence, "resume-test")
        firstRun.transition("one", TaskStepState.OBSERVING)
        firstRun.transition("one", TaskStepState.READY_FOR_REVIEW, "Proposal reviewed")

        val restored = TaskExecutionLedger(steps, persistence, "resume-test")
        assertEquals(TaskStepState.READY_FOR_REVIEW, restored.get("one")?.state)
        assertEquals("Proposal reviewed", restored.get("one")?.note)
    }

    @Test fun inFlightStepRequiresFreshObservationAfterRestore() {
        val persistence = InMemoryTaskLedgerPersistence()
        val steps = listOf(AgentTaskStep("one", "Inspect screen", "Screen understood"))
        val firstRun = TaskExecutionLedger(steps, persistence, "in-flight")
        firstRun.transition("one", TaskStepState.OBSERVING)

        val restored = TaskExecutionLedger(steps, persistence, "in-flight")
        assertEquals(TaskStepState.NEEDS_REPLAN, restored.get("one")?.state)
        assertEquals("one", restored.readySteps().single().step.id)
    }

    @Test fun cancelStopsPendingSteps() {
        val ledger = TaskExecutionLedger(listOf(AgentTaskStep("one", "Inspect", "Tree")))
        ledger.cancelPending()
        assertTrue(ledger.get("one")?.state == TaskStepState.CANCELLED)
    }
}
