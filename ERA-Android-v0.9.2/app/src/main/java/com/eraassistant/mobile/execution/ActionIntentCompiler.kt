package com.eraassistant.mobile.execution

import com.eraassistant.mobile.capability.ActionRiskLevel
import com.eraassistant.mobile.capability.CapabilityId
import com.eraassistant.mobile.capability.StructuredActionRequest
import com.eraassistant.mobile.core.AssistantCommand
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.planner.TaskStep
import java.net.URI
import java.util.UUID

/**
 * Compiles only explicit, allow-listed instant commands into typed capability requests.
 *
 * It deliberately does not interpret planner prose, scripts, selectors or arbitrary tool
 * names. Unsupported commands return null and must remain on the existing preview/manual
 * path until a dedicated compiler exists.
 */
object ActionIntentCompiler {
    data class CompiledAction(
        val plan: TaskPlan,
        val stepId: String,
        val request: StructuredActionRequest,
        val displaySummary: String
    )

    fun compile(
        command: AssistantCommand,
        actionId: String = "era_action_${UUID.randomUUID()}",
        idempotencyKey: String = "era_idem_${UUID.randomUUID()}",
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): CompiledAction? {
        if (actionId.isBlank() || actionId.length > 128 ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            createdAtEpochMs <= 0L
        ) return null

        val spec = when (command) {
            is AssistantCommand.SetTorch -> Spec(
                CapabilityId.ANDROID_DEVICE, "device.torch.set",
                mapOf("enabled" to command.enabled), TaskCapability.DEVICE_CONTROL,
                TaskRisk.LOW, ActionRiskLevel.LOW,
                if (command.enabled) "Turn on flashlight" else "Turn off flashlight"
            )
            AssistantCommand.VolumeUp -> volumeSpec("UP")
            AssistantCommand.VolumeDown -> volumeSpec("DOWN")
            is AssistantCommand.AdjustVolume -> Spec(
                CapabilityId.ANDROID_DEVICE, "device.volume.adjust",
                mapOf("stream" to streamName(command.stream), "direction" to command.direction.name),
                TaskCapability.DEVICE_CONTROL, TaskRisk.LOW, ActionRiskLevel.LOW,
                "Adjust ${command.stream.label} volume ${command.direction.name.lowercase()}"
            )
            is AssistantCommand.SetVolumePercent -> Spec(
                CapabilityId.ANDROID_DEVICE, "device.volume.set_percent",
                mapOf("stream" to streamName(command.stream), "percent" to command.percent),
                TaskCapability.DEVICE_CONTROL, TaskRisk.LOW, ActionRiskLevel.LOW,
                "Set ${command.stream.label} volume to ${command.percent}%"
            )
            is AssistantCommand.SetMediaVolumePercent -> Spec(
                CapabilityId.ANDROID_DEVICE, "device.volume.set_percent",
                mapOf("stream" to "MEDIA", "percent" to command.percent),
                TaskCapability.DEVICE_CONTROL, TaskRisk.LOW, ActionRiskLevel.LOW,
                "Set media volume to ${command.percent}%"
            )
            is AssistantCommand.LaunchApp -> Spec(
                CapabilityId.ANDROID_DEVICE, "device.app.launch",
                mapOf("appName" to command.app.displayName),
                TaskCapability.APP_LAUNCH, TaskRisk.MEDIUM, ActionRiskLevel.MEDIUM,
                "Open ${command.app.displayName}"
            )
            is AssistantCommand.LaunchAppByName -> {
                val name = command.appName.trim().takeIf { it.isNotBlank() && it.length <= 60 } ?: return null
                Spec(
                    CapabilityId.ANDROID_DEVICE, "device.app.launch",
                    mapOf("appName" to name),
                    TaskCapability.APP_LAUNCH, TaskRisk.MEDIUM, ActionRiskLevel.MEDIUM,
                    "Open app: $name"
                )
            }
            is AssistantCommand.OpenSettings -> Spec(
                CapabilityId.ANDROID_DEVICE, "device.settings.open",
                mapOf(
                    "settingsAction" to command.action.intentAction,
                    "label" to command.action.label
                ),
                TaskCapability.DEVICE_CONTROL, TaskRisk.MEDIUM, ActionRiskLevel.MEDIUM,
                "Open ${command.action.label}"
            )
            AssistantCommand.OpenCamera -> Spec(
                CapabilityId.ANDROID_DEVICE, "device.camera.open", emptyMap(),
                TaskCapability.DEVICE_CONTROL, TaskRisk.MEDIUM, ActionRiskLevel.MEDIUM,
                "Open camera"
            )
            is AssistantCommand.DialNumber -> {
                val number = normalizePhoneNumber(command.number) ?: return null
                Spec(
                    CapabilityId.ANDROID_DEVICE, "device.dialer.open",
                    mapOf("phoneNumber" to number),
                    TaskCapability.DIALER_OPEN, TaskRisk.MEDIUM, ActionRiskLevel.MEDIUM,
                    "Open dialer for the supplied number"
                )
            }
            is AssistantCommand.CreateSmsDraft -> draftSpec(
                "device.sms.create_draft", command.text, "Prepare an SMS draft"
            ) ?: return null
            is AssistantCommand.ShareTextToWhatsApp -> draftSpec(
                "device.whatsapp.share_draft", command.text, "Prepare a WhatsApp draft"
            ) ?: return null
            is AssistantCommand.OpenWebSearch -> searchSpec(
                "device.web.search", command.query, "Open a web search"
            ) ?: return null
            is AssistantCommand.OpenMapSearch -> searchSpec(
                "device.maps.search", command.query, "Open a map search"
            ) ?: return null
            is AssistantCommand.OpenWebsite -> {
                val url = validateHttpsUrl(command.url) ?: return null
                Spec(
                    CapabilityId.ANDROID_DEVICE, "device.website.open",
                    mapOf("url" to url),
                    TaskCapability.WEB_NAVIGATION, TaskRisk.MEDIUM, ActionRiskLevel.MEDIUM,
                    "Open the requested HTTPS website"
                )
            }
            // Read-only system summaries, contact/media pickers and compound commands
            // keep their existing explicit handlers until their adapters have contracts.
            AssistantCommand.ReadBattery,
            AssistantCommand.ReadDateTime,
            AssistantCommand.PickContactForDial,
            is AssistantCommand.RunSequence,
            AssistantCommand.CancelCurrentTask,
            is AssistantCommand.UnsupportedComplexTask,
            AssistantCommand.Help -> return null
        }

        val step = TaskStep(
            id = 1,
            title = spec.summary.take(120),
            description = spec.summary.take(500),
            requiredCapabilities = setOf(spec.taskCapability),
            acceptanceCriteria = acceptanceCriteria(spec),
            retryLimit = 0,
            risk = spec.taskRisk,
            reversible = true,
            requiresUserApproval = false
        )
        val plan = TaskPlan(
            goal = spec.summary,
            steps = listOf(step),
            note = "Direct user command compiled by ERA's local allow-list. Adapter output is not independent outcome verification.",
            acceptanceCriteria = listOf("Every direct action must satisfy its action-specific acceptance criteria."),
            overallRisk = spec.taskRisk,
            estimatedActions = 1,
            planVersion = "era.direct.v1.$actionId"
        )
        if (plan.validate().isNotEmpty()) return null

        val request = StructuredActionRequest(
            actionId = actionId,
            idempotencyKey = idempotencyKey,
            capabilityId = spec.capabilityId,
            actionName = spec.actionName,
            parameters = spec.parameters,
            timeoutMs = 15_000L,
            requiresUserApproval = false,
            riskLevel = spec.actionRisk,
            createdAtEpochMs = createdAtEpochMs
        )
        return CompiledAction(plan, step.id.toString(), request, spec.summary)
    }

    private fun acceptanceCriteria(spec: Spec): List<String> = when (spec.actionName) {
        "device.torch.set" -> listOf(
            "era.device.torch.enabled=${spec.parameters["enabled"]}"
        )
        "device.volume.set_percent" -> listOf(
            "era.device.volume.percent|stream=${spec.parameters["stream"]}|percent=${spec.parameters["percent"]}"
        )
        "device.volume.adjust" -> listOf(
            "era.device.volume.adjust|stream=${spec.parameters["stream"]}|direction=${spec.parameters["direction"]}"
        )
        "device.app.launch" -> listOf(
            "era.device.app.launch|name=${spec.parameters["appName"]}"
        )
        else -> listOf("era.device.intent|action=${spec.actionName}")
    }

    private fun volumeSpec(direction: String) = Spec(
        CapabilityId.ANDROID_DEVICE, "device.volume.adjust",
        mapOf("stream" to "MEDIA", "direction" to direction),
        TaskCapability.DEVICE_CONTROL, TaskRisk.LOW, ActionRiskLevel.LOW,
        "Adjust media volume ${direction.lowercase()}"
    )

    private fun streamName(stream: com.eraassistant.mobile.core.VolumeStream): String =
        if (stream == com.eraassistant.mobile.core.VolumeStream.MEDIA) "MEDIA"
        else stream.name

    private fun draftSpec(action: String, text: String, summary: String): Spec? {
        val clean = text.trim().takeIf { it.isNotBlank() && it.length <= 4_000 } ?: return null
        return Spec(
            CapabilityId.ANDROID_DEVICE, action, mapOf("message" to clean),
            TaskCapability.MESSAGE_DRAFT, TaskRisk.MEDIUM, ActionRiskLevel.MEDIUM, summary
        )
    }

    private fun searchSpec(action: String, query: String, summary: String): Spec? {
        val clean = query.trim().takeIf { it.isNotBlank() && it.length <= 500 } ?: return null
        return Spec(
            CapabilityId.ANDROID_DEVICE, action, mapOf("query" to clean),
            TaskCapability.WEB_NAVIGATION, TaskRisk.LOW, ActionRiskLevel.LOW, summary
        )
    }

    private fun normalizePhoneNumber(raw: String): String? {
        val clean = raw.trim().filter { it.isDigit() || it == '+' }
        val digits = clean.count(Char::isDigit)
        if (digits !in 3..20 || clean.count { it == '+' } > 1 || (clean.contains('+') && !clean.startsWith('+'))) return null
        return clean
    }

    private fun validateHttpsUrl(raw: String): String? {
        val value = raw.trim().takeIf { it.length in 9..2_048 } ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return uri.toASCIIString()
    }

    private data class Spec(
        val capabilityId: CapabilityId,
        val actionName: String,
        val parameters: Map<String, Any?>,
        val taskCapability: TaskCapability,
        val taskRisk: TaskRisk,
        val actionRisk: ActionRiskLevel,
        val summary: String
    )
}
