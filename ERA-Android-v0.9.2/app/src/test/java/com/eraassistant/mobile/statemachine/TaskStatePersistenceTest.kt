package com.eraassistant.mobile.statemachine

import org.junit.Assert.*
import org.junit.Test

class TaskStatePersistenceTest {
    private val actionList = listOf("step-1" to "Open app", "step-2" to "Inspect screen")

    @Test
    fun taskAndActionHistorySurviveRestartAndUnfinishedWorkRequiresRecovery() {
        val persistence = InMemoryTaskStatePersistence()
        val first = TaskStateMachine(
            taskId = "durable-task-1",
            goal = "Open app and inspect screen",
            initialActions = actionList,
            persistence = persistence
        )
        assertTrue(first.transitionTask(ExecutionState.VALIDATING) is TransitionOutcome.Accepted)
        assertTrue(first.transitionTask(ExecutionState.READY) is TransitionOutcome.Accepted)
        assertTrue(first.transitionTask(ExecutionState.RUNNING) is TransitionOutcome.Accepted)
        assertTrue(first.transitionAction("step-1", ExecutionState.READY) is TransitionOutcome.Accepted)
        assertTrue(first.transitionAction("step-1", ExecutionState.RUNNING) is TransitionOutcome.Accepted)

        val restored = TaskStateMachine(
            taskId = "durable-task-1",
            goal = "Open app and inspect screen",
            initialActions = actionList,
            persistence = persistence
        )
        assertEquals(ExecutionState.RECOVERY_REQUIRED, restored.getState())
        assertEquals(ExecutionState.RECOVERY_REQUIRED, restored.getActionProgress("step-1")?.state)
        assertTrue(restored.getHistory().any { it.toState == ExecutionState.RUNNING })
        assertTrue(restored.getHistory().any { it.toState == ExecutionState.RECOVERY_REQUIRED })
        assertTrue(restored.getActionProgress("step-1")!!.history.any { it.toState == ExecutionState.RUNNING })

        val historySize = restored.getHistory().size
        val restoredAgain = TaskStateMachine(
            taskId = "durable-task-1",
            goal = "Open app and inspect screen",
            initialActions = actionList,
            persistence = persistence
        )
        assertEquals(ExecutionState.RECOVERY_REQUIRED, restoredAgain.getState())
        assertEquals(historySize, restoredAgain.getHistory().size)
    }

    @Test
    fun failedDurableWriteDoesNotAdvanceLifecycleState() {
        var saves = 0
        val failingAfterInitialization = object : TaskStatePersistence {
            override fun load(taskId: String): TaskStateSnapshot? = null
            override fun save(snapshot: TaskStateSnapshot): Boolean {
                saves++
                return saves == 1
            }
            override fun clear(taskId: String): Boolean = true
        }
        val machine = TaskStateMachine(
            taskId = "durable-task-fail",
            goal = "Fail safely",
            initialActions = listOf("step-1" to "Open app"),
            persistence = failingAfterInitialization
        )
        val outcome = machine.transitionTask(ExecutionState.VALIDATING)
        assertTrue(outcome is TransitionOutcome.Rejected)
        assertEquals(ExecutionState.CREATED, machine.getState())
        assertEquals(1, machine.getHistory().size)
    }
}
