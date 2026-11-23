package com.eraassistant.mobile.execution

import com.eraassistant.mobile.capability.StructuredActionRequest
import com.eraassistant.mobile.core.AssistantCommand
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.planner.TaskStep

/**
 * Converts only a small, exact allowlist of reversible plan steps into trusted typed requests.
 * It never executes plan descriptions, model tool names, arbitrary URLs, selectors or text.
 */
object PlanStepActionCompiler {
    data class CompiledPlanStep(
        val stepId: String,
        val request: StructuredActionRequest,
        val acceptanceCriteria: List<String>
    )

    fun compile(plan: TaskPlan, step: TaskStep): CompiledPlanStep? {
        if (step.requiresUserApproval || step.risk >= TaskRisk.HIGH || !step.reversible) return null
        if (step.requiredCapabilities != setOf(TaskCapability.APP_LAUNCH)) return null

        val command = when (step.title) {
            "Open WhatsApp" -> AssistantCommand.LaunchAppByName("WhatsApp")
            "Open Instagram" -> AssistantCommand.LaunchAppByName("Instagram")
            "Open email app" -> {
                val goal = plan.goal.lowercase()
                when {
                    "gmail" in goal -> AssistantCommand.LaunchAppByName("Gmail")
                    else -> return null
                }
            }
            else -> return null
        }
        val compiled = ActionIntentCompiler.compile(command) ?: return null
        if (compiled.request.actionName != "device.app.launch" ||
            compiled.request.riskLevel.ordinal < step.risk.ordinal
        ) return null

        return CompiledPlanStep(
            stepId = step.id.toString(),
            request = compiled.request,
            acceptanceCriteria = compiled.plan.steps.single().acceptanceCriteria
        )
    }

    /** Enrich only steps for which a trusted local compiler exists. */
    fun enrichPlan(plan: TaskPlan): TaskPlan = plan.copy(
        steps = plan.steps.map { step ->
            val compiled = compile(plan, step) ?: return@map step
            step.copy(acceptanceCriteria = compiled.acceptanceCriteria)
        }
    )
}
