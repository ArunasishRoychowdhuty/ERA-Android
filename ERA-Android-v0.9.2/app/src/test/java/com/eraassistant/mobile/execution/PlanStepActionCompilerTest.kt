package com.eraassistant.mobile.execution

import com.eraassistant.mobile.planner.PreviewPlanner
import com.eraassistant.mobile.planner.TaskRisk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanStepActionCompilerTest {
    @Test fun reviewedWhatsAppLaunchMapsToTypedAppLaunchOnly() = runBlocking {
        val plan = PreviewPlanner().createPlan("Open WhatsApp")
        val step = plan.steps.first()
        val compiled = PlanStepActionCompiler.compile(plan, step)
        assertNotNull(compiled)
        assertEquals("device.app.launch", compiled?.request?.actionName)
        assertEquals("WhatsApp", compiled?.request?.parameters?.get("appName"))
        assertTrue(compiled?.acceptanceCriteria?.single()?.startsWith("era.device.app.launch") == true)
    }

    @Test fun instagramLaunchMapsButShareStepNeverCompiles() = runBlocking {
        val plan = PreviewPlanner().createPlan("Open Instagram and prepare a post")
        val open = plan.steps.first()
        assertEquals("device.app.launch", PlanStepActionCompiler.compile(plan, open)?.request?.actionName)
        val share = plan.steps.last()
        assertNull(PlanStepActionCompiler.compile(plan, share))
    }

    @Test fun unsupportedComplexStepsRemainManualOnly() = runBlocking {
        val plan = PreviewPlanner().createPlan("WhatsApp খুলে message লিখে পাঠাও")
        val unsupported = plan.steps.firstOrNull { it.requiredCapabilities.size > 1 ||
            it.requiredCapabilities.any { capability -> capability.name in setOf("TEXT_ENTRY", "SCREEN_OBSERVATION", "USER_CONFIRMATION") }
        }
        if (unsupported != null) assertNull(PlanStepActionCompiler.compile(plan, unsupported))
    }

    @Test fun highRiskAndApprovalRequiredStepsCannotCompile() = runBlocking {
        val plan = PreviewPlanner().createPlan("Open WhatsApp then send message")
        val blocked = plan.steps.firstOrNull { it.risk >= TaskRisk.HIGH || it.requiresUserApproval }
        if (blocked != null) assertNull(PlanStepActionCompiler.compile(plan, blocked))
    }

    @Test fun enrichPlanOnlyChangesSupportedLaunchCriteria() = runBlocking {
        val plan = PreviewPlanner().createPlan("Open WhatsApp")
        val enriched = PlanStepActionCompiler.enrichPlan(plan)
        assertTrue(enriched.steps.first().acceptanceCriteria.single().startsWith("era.device.app.launch"))
        assertFalse(enriched.steps.first().acceptanceCriteria.isEmpty())
        assertEquals(plan.goal, enriched.goal)
    }
}
