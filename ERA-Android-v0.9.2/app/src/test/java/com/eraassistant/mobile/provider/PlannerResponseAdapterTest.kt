package com.eraassistant.mobile.provider

import com.eraassistant.mobile.ai.PlannerResponse
import com.eraassistant.mobile.ai.PlannerStep
import com.eraassistant.mobile.ai.Risk
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskRisk
import org.junit.Assert.*
import org.junit.Test

class PlannerResponseAdapterTest {
    @Test fun mapsRemoteDagIntoValidatedLocalPlan() {
        val response = PlannerResponse(
            steps = listOf(
                PlannerStep("1", "Open the requested app", "APP_LAUNCH"),
                PlannerStep("2", "Review the draft", "USER_CONFIRMATION", listOf("1"), Risk.REVIEW)
            ),
            successCriteria = listOf("User reviews the draft")
        )
        val plan = PlannerResponseAdapter.toTaskPlan("Prepare a draft", response)
        assertTrue(plan.validate().isEmpty())
        assertEquals("Prepare a draft", plan.goal)
        assertEquals(listOf(1), plan.steps[1].dependsOn)
        assertEquals(TaskRisk.MEDIUM, plan.steps[1].risk)
        assertTrue(plan.steps[1].requiresUserApproval)
    }

    @Test fun consequentialKeywordsCannotBeDowngradedByModel() {
        val response = PlannerResponse(
            listOf(PlannerStep("1", "Send the prepared message", "SEND_MESSAGE", risk = Risk.LOW)),
            emptyList()
        )
        val plan = PlannerResponseAdapter.toTaskPlan("Send a message", response)
        assertEquals(TaskRisk.HIGH, plan.steps.single().risk)
        assertEquals(setOf(TaskCapability.USER_CONFIRMATION), plan.steps.single().requiredCapabilities)
        assertTrue(plan.steps.single().requiresUserApproval)
        assertFalse(plan.steps.single().reversible)
    }

    @Test fun invalidDependencyIsRejected() {
        val response = PlannerResponse(
            listOf(PlannerStep("1", "Review", "USER_CONFIRMATION", listOf("2"))),
            emptyList()
        )
        try {
            PlannerResponseAdapter.toTaskPlan("goal", response)
            fail("Expected invalid dependency to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("earlier"))
        }
    }

    @Test fun oversizedPlanIsRejected() {
        val steps = (1..13).map { PlannerStep(it.toString(), "Step $it", "REVIEW") }
        try {
            PlannerResponseAdapter.toTaskPlan("goal", PlannerResponse(steps, emptyList()))
            fail("Expected plan exceeding max size to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("1 to 12"))
        }
    }

    @Test fun unknownProviderToolIsRejected() {
        val response = PlannerResponse(
            listOf(PlannerStep("1", "Run an unsupported tool", "UNRECOGNIZED_DEVICE_TOOL")),
            emptyList()
        )
        try {
            PlannerResponseAdapter.toTaskPlan("goal", response)
            fail("Expected unsupported provider tool to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Unsupported planner tool"))
        }
    }
}
