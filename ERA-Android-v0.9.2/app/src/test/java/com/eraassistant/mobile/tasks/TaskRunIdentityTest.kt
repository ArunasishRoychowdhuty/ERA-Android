package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.planner.TaskStep
import com.eraassistant.mobile.statemachine.InMemoryTaskStatePersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRunIdentityTest {
    private fun plan() = TaskPlan(
        goal = "Open the same app",
        steps = listOf(TaskStep(
            id = 1,
            title = "Open app",
            description = "Open the selected app",
            retryLimit = 0
        )),
        note = "identity test",
        overallRisk = TaskRisk.LOW
    )

    @Test fun identicalPlansCreateIndependentRunsByDefault() {
        val ledgerPersistence = InMemoryTaskLedgerPersistence()
        val statePersistence = InMemoryTaskStatePersistence()
        val first = TaskExecutionCoordinator.from(
            plan(), persistence = ledgerPersistence, statePersistence = statePersistence
        )
        val second = TaskExecutionCoordinator.from(
            plan(), persistence = ledgerPersistence, statePersistence = statePersistence
        )
        assertNotEquals(first.taskRunId, second.taskRunId)
        assertNotEquals(first.stateMachine?.taskId, second.stateMachine?.taskId)
        assertTrue(first.start("1") is TransitionResult.Updated)
        assertEquals(TaskStepState.PENDING, second.ledger.get("1")?.state)
    }

    @Test fun explicitRunIdResumesOnlyThatRun() {
        val ledgerPersistence = InMemoryTaskLedgerPersistence()
        val statePersistence = InMemoryTaskStatePersistence()
        val first = TaskExecutionCoordinator.from(
            plan(), persistence = ledgerPersistence, statePersistence = statePersistence,
            taskRunId = "resume-this-run"
        )
        first.start("1")
        val newRun = TaskExecutionCoordinator.from(
            plan(), persistence = ledgerPersistence, statePersistence = statePersistence,
            taskRunId = "brand-new-run"
        )
        val resumed = TaskExecutionCoordinator.from(
            plan(), persistence = ledgerPersistence, statePersistence = statePersistence,
            taskRunId = "resume-this-run"
        )
        assertEquals(TaskStepState.PENDING, newRun.ledger.get("1")?.state)
        assertEquals(TaskStepState.RECOVERY_REQUIRED, resumed.ledger.get("1")?.state)
        assertEquals("resume-this-run", resumed.taskRunId)
    }
}
