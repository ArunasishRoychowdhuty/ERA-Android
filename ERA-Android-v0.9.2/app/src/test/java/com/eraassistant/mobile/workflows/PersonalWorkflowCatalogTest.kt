package com.eraassistant.mobile.workflows
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskRisk
import org.junit.Assert.*
import org.junit.Test
class PersonalWorkflowCatalogTest {
    @Test fun accountWorkflowKeepsAuthenticationAndFinalSubmissionUserControlled() {
        val plan = PersonalWorkflowCatalog.createPlan("Create account on the official website")!!
        assertEquals("era.personal-workflow.v1", plan.planVersion)
        assertTrue(plan.validate().isEmpty())
        assertTrue(plan.steps.any { it.title.contains("Authenticate") && TaskCapability.USER_CONFIRMATION in it.requiredCapabilities })
        assertTrue(plan.steps.any { it.title.contains("Create account") && it.risk == TaskRisk.CRITICAL && it.requiresUserApproval })
        assertTrue(plan.steps.zipWithNext().all { (a, b) -> b.dependsOn == listOf(a.id) })
    }
    @Test fun routesFormsProfilesLeadsAndClientReplies() {
        assertEquals("website-form", PersonalWorkflowCatalog.match("Please complete form")?.id)
        assertEquals("professional-profile", PersonalWorkflowCatalog.match("improve my professional profile")?.id)
        assertEquals("lead-discovery", PersonalWorkflowCatalog.match("find clients for web design")?.id)
        assertEquals("client-reply", PersonalWorkflowCatalog.match("reply to client")?.id)
    }
    @Test fun doesNotForceAnUnrelatedOrAmbiguousWorkflow() {
        assertNull(PersonalWorkflowCatalog.match("hello there"))
        assertNull(PersonalWorkflowCatalog.match("client discovery and professional profile"))
    }
}
