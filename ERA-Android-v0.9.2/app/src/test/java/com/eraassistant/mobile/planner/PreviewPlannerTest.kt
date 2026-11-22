package com.eraassistant.mobile.planner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.eraassistant.mobile.tasks.TaskExecutionCoordinator
import com.eraassistant.mobile.tasks.TaskStepState
import com.eraassistant.mobile.tasks.TransitionResult

class PreviewPlannerTest {
    @Test fun whatsappPlanHasOrderedDependenciesAndSendApproval() = runBlocking {
        val plan = PreviewPlanner().createPlan("WhatsApp খুলে সাহেবকে Hi লিখে দাও")
        assertTrue(plan.validate().isEmpty())
        assertEquals(listOf(5), plan.steps.last().dependsOn)
        assertTrue(plan.steps.last().requiresUserApproval)
        assertEquals(TaskRisk.HIGH, plan.steps.last().risk)
    }

    @Test fun missingCapabilitiesAreReportedInPlan() = runBlocking {
        val plan = PreviewPlanner(emptySet()).createPlan("WhatsApp খুলে message লেখো")
        assertTrue(plan.steps.any { it.description.contains("Missing local capabilities") })
    }

    @Test fun plansHaveAcceptanceCriteria() = runBlocking {
        val plan = PreviewPlanner().createPlan("Instagram-এ পোস্ট কর")
        assertTrue(plan.acceptanceCriteria.isNotEmpty())
        assertTrue(plan.steps.all { it.retryLimit in 0..3 })
    }

    @Test fun instagramPlanIncludesCaptionHashtagsAndManualShareGate() = runBlocking {
        val plan = PreviewPlanner().createPlan("Instagram-এ ছবি পোস্ট কর")
        assertEquals(5, plan.steps.size)
        assertTrue(plan.steps[2].title.contains("caption", ignoreCase = true))
        assertTrue(plan.steps[2].title.contains("hashtags", ignoreCase = true))
        assertTrue(plan.steps.last().requiresUserApproval)
        assertTrue(plan.steps.last().description.contains("manually tap Share"))
        assertTrue(plan.validate().isEmpty())
    }

    @Test fun planCanCreateDependencyAwareExecutionCoordinator() = runBlocking {
        val plan = PreviewPlanner().createPlan("WhatsApp খুলে সাহেবকে Hi লিখে দাও")
        val coordinator = TaskExecutionCoordinator.from(plan)
        assertEquals(listOf("1"), coordinator.readySteps().map { it.step.id })
        assertTrue(coordinator.start("2") is TransitionResult.Rejected)
        coordinator.start("1")
        coordinator.readyForReview("1", "App launched")
        coordinator.approve("1")
        coordinator.reportUserPerformed("1")
        coordinator.confirmUserOutcome("1", "WhatsApp is visible and I checked the target page.")
        assertEquals("2", coordinator.readySteps().single().step.id)
    }
}
