package com.eraassistant.mobile.statemachine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TaskStateMachineTest {

    private lateinit var stateMachine: TaskStateMachine

    @Before
    fun setup() {
        stateMachine = TaskStateMachine(
            taskId = "task_test_001",
            goal = "Test user workflow",
            initialActions = listOf("a1" to "First Action", "a2" to "Second Action")
        )
    }

    @Test
    fun testValidLifecycleTransitions() {
        assertEquals(ExecutionState.CREATED, stateMachine.getState())

        val t1 = stateMachine.transitionTask(ExecutionState.VALIDATING, "Validating plan")
        assertTrue(t1 is TransitionOutcome.Accepted)
        assertEquals(ExecutionState.VALIDATING, stateMachine.getState())

        val t2 = stateMachine.transitionTask(ExecutionState.READY, "Plan valid")
        assertTrue(t2 is TransitionOutcome.Accepted)
        assertEquals(ExecutionState.READY, stateMachine.getState())

        val t3 = stateMachine.transitionTask(ExecutionState.RUNNING, "Starting run")
        assertTrue(t3 is TransitionOutcome.Accepted)
        assertEquals(ExecutionState.RUNNING, stateMachine.getState())

        val t4 = stateMachine.transitionTask(ExecutionState.OBSERVING, "Observing action outcome")
        assertTrue(t4 is TransitionOutcome.Accepted)
        assertEquals(ExecutionState.OBSERVING, stateMachine.getState())

        val t5 = stateMachine.transitionTask(ExecutionState.VERIFIED, "Outcome verified")
        assertTrue(t5 is TransitionOutcome.Accepted)
        assertEquals(ExecutionState.VERIFIED, stateMachine.getState())

        val t6 = stateMachine.transitionTask(ExecutionState.SUCCEEDED, "All actions completed successfully")
        assertTrue(t6 is TransitionOutcome.Accepted)
        assertEquals(ExecutionState.SUCCEEDED, stateMachine.getState())
        assertTrue(stateMachine.getState().isTerminal)
    }

    @Test
    fun testInvalidTransitionRejection() {
        // Cannot jump directly from CREATED to SUCCEEDED
        val invalidJump = stateMachine.transitionTask(ExecutionState.SUCCEEDED, "Attempt invalid jump")
        assertTrue(invalidJump is TransitionOutcome.Rejected)
        assertEquals(ExecutionState.CREATED, stateMachine.getState())

        // Move to SUCCEEDED through valid path
        stateMachine.transitionTask(ExecutionState.VALIDATING)
        stateMachine.transitionTask(ExecutionState.READY)
        stateMachine.transitionTask(ExecutionState.RUNNING)
        stateMachine.transitionTask(ExecutionState.OBSERVING)
        stateMachine.transitionTask(ExecutionState.VERIFIED)
        stateMachine.transitionTask(ExecutionState.SUCCEEDED)

        // Cannot transition out of terminal state SUCCEEDED
        val afterTerminal = stateMachine.transitionTask(ExecutionState.RUNNING, "Resume from terminal")
        assertTrue(afterTerminal is TransitionOutcome.Rejected)
        assertEquals(ExecutionState.SUCCEEDED, stateMachine.getState())
    }

    @Test
    fun testCancellationFromActiveState() {
        stateMachine.transitionTask(ExecutionState.VALIDATING)
        stateMachine.transitionTask(ExecutionState.READY)
        stateMachine.transitionTask(ExecutionState.RUNNING)

        val cancelOutcome = stateMachine.cancel("User cancelled operation")
        assertTrue(cancelOutcome is TransitionOutcome.Accepted)
        assertEquals(ExecutionState.CANCELLED, stateMachine.getState())
        assertTrue(stateMachine.getState().isTerminal)
    }

    @Test
    fun testCrashRecoveryReconciliation() {
        stateMachine.transitionTask(ExecutionState.VALIDATING)
        stateMachine.transitionTask(ExecutionState.READY)
        stateMachine.transitionTask(ExecutionState.RUNNING)
        stateMachine.transitionAction("a1", ExecutionState.RUNNING, "Action 1 running")

        // Simulate app crash / restart
        val reconciled = stateMachine.reconcileAfterRestart()
        assertTrue(reconciled)
        assertEquals(ExecutionState.RECOVERY_REQUIRED, stateMachine.getState())

        // The running action must also have been reconciled
        val actionProgress = stateMachine.getActionProgress("a1")
        assertNotNull(actionProgress)
        assertEquals(ExecutionState.RECOVERY_REQUIRED, actionProgress?.state)
    }

    @Test
    fun testActionLevelTransitions() {
        val a1Start = stateMachine.transitionAction("a1", ExecutionState.READY, "Action ready")
        assertTrue(a1Start is TransitionOutcome.Accepted)

        val a1Running = stateMachine.transitionAction("a1", ExecutionState.RUNNING, "Action running")
        assertTrue(a1Running is TransitionOutcome.Accepted)

        val a1Observing = stateMachine.transitionAction("a1", ExecutionState.OBSERVING, "Observing element")
        assertTrue(a1Observing is TransitionOutcome.Accepted)

        val a1Verified = stateMachine.transitionAction("a1", ExecutionState.VERIFIED, "Element found")
        assertTrue(a1Verified is TransitionOutcome.Accepted)

        val a1Succeeded = stateMachine.transitionAction("a1", ExecutionState.SUCCEEDED, "Action finished")
        assertTrue(a1Succeeded is TransitionOutcome.Accepted)

        assertEquals(ExecutionState.SUCCEEDED, stateMachine.getActionProgress("a1")?.state)
    }
}
