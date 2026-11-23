package com.eraassistant.mobile.planning

import com.eraassistant.mobile.planner.AIPlanner
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.planner.TaskStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TwoPassPlanningEngineTest {
    private val sendPlan = TaskPlan(
        goal = "draft only",
        steps = listOf(
            TaskStep(1, "Prepare draft", "Prepare a draft."),
            TaskStep(2, "Send message", "Send after review.", dependsOn = listOf(1),
                requiredCapabilities = setOf(TaskCapability.USER_CONFIRMATION))
        ),
        note = "test"
    )

    @Test fun explicitDraftOnlyConstraintBlocksSendStep() = runBlocking {
        val planner = object : AIPlanner {
            override suspend fun createPlan(userRequest: String) = sendPlan
        }
        val result = TwoPassPlanningEngine(planner).createPlan("Prepare this message draft only; do not send")
        assertEquals(setOf(2), result.blockedStepIds)
        assertTrue(result.plan.steps.first { it.id == 2 }.title.startsWith("BLOCKED"))
        assertTrue(result.plan.steps.first { it.id == 2 }.requiredCapabilities.isEmpty())
        assertTrue(result.isValid)
    }

    @Test fun doNotSendIsEnforcedEvenWhenPlannerReturnsSendStep() = runBlocking {
        val planner = object : AIPlanner {
            override suspend fun createPlan(userRequest: String) = sendPlan
        }
        val result = TwoPassPlanningEngine(planner).createPlan("Do not send this message")
        assertEquals(setOf(2), result.blockedStepIds)
        assertTrue(result.plan.steps.first { it.id == 2 }.title.startsWith("BLOCKED"))
    }

    @Test fun consequentialStepsBecomeApprovalGated() {
        val planner = object : AIPlanner {
            override suspend fun createPlan(userRequest: String) = sendPlan
        }
        val result = TwoPassPlanningEngine(planner).enforcePlan("send message", sendPlan)
        val step = result.plan.steps.first { it.id == 2 }
        assertEquals(TaskRisk.HIGH, step.risk)
        assertTrue(step.requiresUserApproval)
        assertFalse(step.reversible)
        assertEquals(0, step.retryLimit)
    }
}
