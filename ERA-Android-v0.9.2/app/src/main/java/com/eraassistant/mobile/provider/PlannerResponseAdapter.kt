package com.eraassistant.mobile.provider

import com.eraassistant.mobile.ai.PlannerResponse
import com.eraassistant.mobile.ai.Risk
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.planner.TaskStep

/** Converts untrusted provider JSON into a validated, preview-only local TaskPlan. */
object PlannerResponseAdapter {
    fun toTaskPlan(goal: String, response: PlannerResponse): TaskPlan {
        require(goal.isNotBlank() && goal.length <= 2_000) { "Task goal is empty or too long." }
        require(response.steps.size in 1..12) { "Provider plan must contain 1 to 12 steps." }
        val ids = response.steps.map { it.id.toIntOrNull() ?: error("Provider step ID is not numeric.") }
        require(ids.distinct().size == ids.size) { "Provider step IDs must be unique." }
        require(ids.all { it in 1..999 }) { "Provider step IDs are outside the allowed range." }

        val steps = response.steps.map { remote ->
            val id = remote.id.toInt()
            val dependencies = remote.dependsOn.map { it.toIntOrNull() ?: error("Provider dependency ID is invalid.") }
            require(dependencies.all { it in ids && it < id }) { "Provider dependencies must point to earlier plan steps." }
            val manualOnly = remote.risk == Risk.CONSEQUENTIAL ||
                HIGH_IMPACT.containsMatchIn("${remote.tool} ${remote.description}")
            val risk = when {
                manualOnly -> TaskRisk.HIGH
                remote.risk == Risk.REVIEW -> TaskRisk.MEDIUM
                else -> TaskRisk.LOW
            }
            val tool = remote.tool.trim().uppercase()
            require(tool.isNotBlank()) { "Provider step $id has no tool." }
            val capability = capabilityFor(tool)
                ?: throw IllegalArgumentException("Unsupported planner tool: ${remote.tool.take(80)}")
            val description = remote.description.trim().take(500)
            require(description.isNotBlank()) { "Provider step $id has no description." }
            val acceptance = listOf("User reviews the step result before proceeding.")
            TaskStep(
                id = id,
                title = remote.tool.trim().take(80).ifBlank { "Review task step $id" },
                description = description,
                dependsOn = dependencies,
                requiredCapabilities = setOf(capability),
                acceptanceCriteria = acceptance,
                retryLimit = if (risk.ordinal >= TaskRisk.HIGH.ordinal) 0 else 1,
                risk = risk,
                reversible = !manualOnly,
                requiresUserApproval = manualOnly || remote.risk != Risk.LOW
            )
        }
        val plan = TaskPlan(
            goal = goal.trim(),
            steps = steps,
            note = "Remote AI plan converted to a local preview. Treat provider output as untrusted suggestions. No actions have been executed; review every step. Consequential steps remain manual-only.",
            acceptanceCriteria = response.successCriteria.take(8).map { it.take(200) }.ifEmpty {
                listOf("Review every step and confirm the intended outcome.", "Perform all consequential actions manually.")
            },
            overallRisk = steps.maxByOrNull { it.risk.ordinal }?.risk ?: TaskRisk.LOW,
            estimatedActions = steps.size,
            planVersion = "era.plan.remote.v1"
        )
        val errors = plan.validate()
        require(errors.isEmpty()) { "Provider plan failed validation: ${errors.joinToString()}" }
        return plan
    }

    private fun capabilityFor(tool: String): TaskCapability? = when (tool.trim().uppercase()) {
        "APP_LAUNCH" -> TaskCapability.APP_LAUNCH
        "SCREEN_OBSERVATION" -> TaskCapability.SCREEN_OBSERVATION
        "CONTACT_PICKER" -> TaskCapability.CONTACT_PICKER
        "STRUCTURED_UI_ACTION" -> TaskCapability.STRUCTURED_UI_ACTION
        "TEXT_ENTRY" -> TaskCapability.TEXT_ENTRY
        "MEDIA_PICKER" -> TaskCapability.MEDIA_PICKER
        "SHARE_DRAFT" -> TaskCapability.SHARE_DRAFT
        "USER_CONFIRMATION",
        "SEND", "SEND_MESSAGE", "SEND_EMAIL",
        "PUBLISH", "PUBLISH_POST", "POST",
        "SUBMIT", "SUBMIT_FORM",
        "CALL",
        "BUY", "PAY", "PAYMENT", "PURCHASE",
        "DELETE", "REMOVE", "SHARE", "TRANSFER", "TRANSFER_FUNDS",
        "INSTALL", "INSTALL_APP", "AUTHENTICATE" -> TaskCapability.USER_CONFIRMATION
        "WEB_NAVIGATION" -> TaskCapability.WEB_NAVIGATION
        else -> null
    }

    private val HIGH_IMPACT = Regex(
        "(?i)\\b(send|publish|post|call|pay|payment|purchase|buy|delete|remove|share|transfer|submit|install|authenticate)\\b|পাঠাও|প্রকাশ|কল কর|পেমেন্ট|মুছ|জমা দাও|শেয়ার"
    )
}
