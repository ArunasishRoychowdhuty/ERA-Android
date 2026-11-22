package com.eraassistant.mobile.skills

import com.eraassistant.mobile.capability.CapabilityId
import com.eraassistant.mobile.capability.CapabilityRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * An individual step defined within a reusable skill.
 */
data class SkillStep(
    val stepIndex: Int,
    val title: String,
    val actionName: String,
    val capabilityId: CapabilityId,
    val parametersTemplate: Map<String, String> = emptyMap(),
    val acceptanceCriteria: List<String> = emptyList(),
    val requiresApproval: Boolean = false
)

/**
 * Reusable, validated agent skill package.
 */
data class SkillDefinition(
    val id: String,
    val name: String,
    val version: String,
    val goalPattern: String,
    val description: String,
    val preconditions: List<String> = emptyList(),
    val requiredCapabilities: Set<CapabilityId> = emptySet(),
    val steps: List<SkillStep> = emptyList(),
    val verificationRules: List<String> = emptyList(),
    val failureHandlingPolicy: String = "RETRY_ONCE_THEN_REPLAN",
    val author: String = "ERA Assistant"
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (id.isBlank() || id.length > 64) errors += "Skill ID must be between 1 and 64 characters."
        if (name.isBlank() || name.length > 100) errors += "Skill name must not be blank."
        if (version.isBlank()) errors += "Skill version must not be blank."
        if (goalPattern.isBlank()) errors += "Skill goal pattern must not be blank."
        if (steps.isEmpty()) errors += "Skill must contain at least one step."
        if (steps.map { it.stepIndex }.distinct().size != steps.size) errors += "Skill step indexes must be unique."
        if (steps.any { it.stepIndex <= 0 }) errors += "Skill step indexes must be positive."
        if (steps.map { it.stepIndex }.sorted() != steps.map { it.stepIndex }) errors += "Skill steps must be stored in execution order."
        if (failureHandlingPolicy !in setOf("STOP_ON_FAILURE", "RETRY_ONCE_THEN_REPLAN", "MANUAL_RECOVERY")) {
            errors += "Unsupported failure handling policy."
        }
        if (preconditions.size > 32 || verificationRules.size > 32) errors += "Skill preconditions and verification rules must each be bounded to 32 entries."

        // Disallow dangerous command patterns
        val dangerousTokens = listOf("rm -rf", "su", "reboot", "format", "dd if=", "chmod 777", "eval", "exec")
        steps.forEach { step ->
            if (step.title.isBlank() || step.title.length > 120) errors += "Step ${step.stepIndex} has an invalid title."
            if (step.actionName.isBlank() || step.actionName.length > 64) errors += "Step ${step.stepIndex} has a blank or overlong actionName."
            if (step.parametersTemplate.size > 32) errors += "Step ${step.stepIndex} has too many parameters."
            if (step.acceptanceCriteria.size > 16) errors += "Step ${step.stepIndex} has too many acceptance criteria."
            step.parametersTemplate.forEach { (k, v) ->
                if (dangerousTokens.any { v.contains(it, ignoreCase = true) }) {
                    errors += "Step ${step.stepIndex} parameter '$k' contains unauthorized shell execution pattern."
                }
            }
        }
        return errors
    }
}

/**
 * Registry and validation manager for reusable skills.
 */
class SkillEngine(private val capabilityRegistry: CapabilityRegistry? = null) {
    private val skills = ConcurrentHashMap<String, SkillDefinition>()

    init {
        // Register default verified built-in skills
        registerBuiltInSkills()
    }

    fun registerSkill(skill: SkillDefinition): Result<Unit> {
        val errors = skill.validate()
        if (errors.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("Invalid skill: ${errors.joinToString()}"))
        }

        // Validate every concrete step against its exact registered adapter and action contract.
        if (capabilityRegistry != null) {
            val declaredIds = skill.requiredCapabilities
            val missing = declaredIds.filter { capabilityRegistry.getAdapter(it) == null }
            if (missing.isNotEmpty()) {
                return Result.failure(
                    IllegalStateException("Skill requires capabilities not registered in environment: $missing")
                )
            }
            for (step in skill.steps) {
                val adapter = capabilityRegistry.getAdapter(step.capabilityId)
                    ?: return Result.failure(IllegalStateException("Step ${step.stepIndex} requires unregistered capability ${step.capabilityId}."))
                if (step.actionName !in adapter.descriptor.supportedActions) {
                    return Result.failure(
                        IllegalStateException("Step ${step.stepIndex} action '${step.actionName}' is not supported by capability '${step.capabilityId}'.")
                    )
                }
                if (declaredIds.isNotEmpty() && step.capabilityId !in declaredIds) {
                    return Result.failure(
                        IllegalStateException("Step ${step.stepIndex} capability '${step.capabilityId}' is missing from the skill's requiredCapabilities list.")
                    )
                }
            }
        }

        skills[skill.id] = skill
        return Result.success(Unit)
    }

    fun findSkillForGoal(goal: String): SkillDefinition? {
        val lowerGoal = goal.lowercase()
        return skills.values.firstOrNull { skill ->
            val pattern = skill.goalPattern.lowercase()
            pattern.split('|').any { lowerGoal.contains(it.trim()) }
        }
    }

    fun allSkills(): List<SkillDefinition> = skills.values.toList()

    private fun registerBuiltInSkills() {
        // Built-in Skill 1: Web Information Search
        registerSkill(
            SkillDefinition(
                id = "skill.web.search_topic",
                name = "Web Topic Search",
                version = "1.0.0",
                goalPattern = "search|browse|look up|খোঁজ|সার্চ",
                description = "Navigates to an HTTPS website or search engine inside ERA WebView and captures a DOM snapshot.",
                requiredCapabilities = setOf(CapabilityId.NATIVE_WEBVIEW),
                steps = listOf(
                    SkillStep(
                        stepIndex = 1,
                        title = "Open Web Address",
                        actionName = "browser.webview.navigate",
                        capabilityId = CapabilityId.NATIVE_WEBVIEW,
                        parametersTemplate = mapOf("url" to "{query_url}"),
                        acceptanceCriteria = listOf("Page loaded in active tab")
                    ),
                    SkillStep(
                        stepIndex = 2,
                        title = "Inspect Page Elements",
                        actionName = "browser.webview.snapshot",
                        capabilityId = CapabilityId.NATIVE_WEBVIEW,
                        acceptanceCriteria = listOf("Snapshot nodes collected")
                    )
                ),
                verificationRules = listOf("NodeCount > 0", "Target matches query_url")
            )
        )

        // Built-in Skill 2: Device Flashlight Toggle
        registerSkill(
            SkillDefinition(
                id = "skill.device.torch",
                name = "Toggle Device Flashlight",
                version = "1.0.0",
                goalPattern = "torch|flashlight|টর্চ",
                description = "Turns the device camera flashlight on or off safely.",
                requiredCapabilities = setOf(CapabilityId.ANDROID_DEVICE),
                steps = listOf(
                    SkillStep(
                        stepIndex = 1,
                        title = "Set Torch State",
                        actionName = "device.torch.set",
                        capabilityId = CapabilityId.ANDROID_DEVICE,
                        parametersTemplate = mapOf("enabled" to "{torch_state}"),
                        acceptanceCriteria = listOf("Torch state applied")
                    )
                ),
                verificationRules = listOf("System response confirms torch change")
            )
        )
    }
}
