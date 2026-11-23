package com.eraassistant.mobile.tools

import com.eraassistant.mobile.capability.CapabilityAvailability
import com.eraassistant.mobile.capability.CapabilityId
import com.eraassistant.mobile.capability.CapabilityRegistry
import com.eraassistant.mobile.domain.AgentDomain
import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskRisk
import com.eraassistant.mobile.orchestration.TaskCapabilityActionPolicy
import com.eraassistant.mobile.integration.IntegrationCandidate
import com.eraassistant.mobile.integration.IntegrationRouteContext
import com.eraassistant.mobile.integration.IntegrationRoutePolicy
import com.eraassistant.mobile.integration.IntegrationTransport

enum class ToolDataSensitivity { PUBLIC, PERSONAL, SENSITIVE, SECRET }
enum class ToolSelectionStatus { SELECTED, PERMISSION_REQUIRED, UNAVAILABLE, UNSUPPORTED, PRIVACY_BLOCKED }

data class ToolSelectionRequest(
    val capability: TaskCapability,
    val domains: Set<AgentDomain>,
    val sensitivity: ToolDataSensitivity = ToolDataSensitivity.PERSONAL,
    val risk: TaskRisk = TaskRisk.LOW,
    val offlineRequired: Boolean = false,
    val preferredCapabilityIds: Set<CapabilityId> = emptySet(),
    val integrationContext: IntegrationRouteContext? = null
)

data class ToolCandidate(
    val capabilityId: CapabilityId,
    val capabilityName: String,
    val actionName: String,
    val score: Int,
    val isAvailable: Boolean,
    val missingPermissions: Set<String>,
    val explanation: String
)

data class ToolSelectionResult(
    val status: ToolSelectionStatus,
    val selected: ToolCandidate?,
    val alternatives: List<ToolCandidate>,
    val reasons: List<String>
)

/** Exact local capability/action selection; never executes or grants Android permissions. */
class AutonomyToolSelector(private val registry: CapabilityRegistry) {
    suspend fun select(request: ToolSelectionRequest): ToolSelectionResult =
        select(request, registry.discoverAvailableCapabilities())

    suspend fun selectAll(requests: List<ToolSelectionRequest>): List<ToolSelectionResult> {
        if (requests.isEmpty()) return emptyList()
        val snapshot = registry.discoverAvailableCapabilities()
        return requests.map { select(it, snapshot) }
    }

    private fun select(
        request: ToolSelectionRequest,
        availability: Map<com.eraassistant.mobile.capability.CapabilityDescriptor, CapabilityAvailability>
    ): ToolSelectionResult {
        val bindings = TaskCapabilityActionPolicy.mappedActions(request.capability)
        if (bindings.isEmpty()) return ToolSelectionResult(
            ToolSelectionStatus.UNSUPPORTED, null, emptyList(),
            listOf("No exact local action mapping exists for ${request.capability}.")
        )
        val descriptors = registry.allDescriptors().associateBy { it.id }
        val candidates = bindings.mapNotNull { (id, action) ->
            val descriptor = descriptors[id] ?: return@mapNotNull null
            if (action !in descriptor.supportedActions) return@mapNotNull null
            val state = availability[descriptor] ?: CapabilityAvailability.missing("Availability was not returned.")
            if (request.sensitivity.ordinal > privacyCeiling(id).ordinal) return@mapNotNull null
            val score = score(id, descriptor.worksOffline, request, state)
            ToolCandidate(
                capabilityId = id,
                capabilityName = descriptor.name,
                actionName = action,
                score = score,
                isAvailable = state.isAvailable,
                missingPermissions = state.missingPermissions,
                explanation = buildString {
                    append("Exact action binding")
                    if (request.offlineRequired && descriptor.worksOffline) append("; offline capable")
                    if (id in request.preferredCapabilityIds) append("; user preference matched")
                    if (!state.isAvailable) append("; unavailable: ${state.unavailableReason.orEmpty()}")
                    if (state.missingPermissions.isNotEmpty()) append("; missing: ${state.missingPermissions.joinToString()}")
                }
            )
        }.sortedWith(compareByDescending<ToolCandidate> { it.isAvailable }
            .thenByDescending { it.score }.thenBy { it.capabilityId.value }.thenBy { it.actionName })

        if (candidates.isEmpty()) return ToolSelectionResult(
            ToolSelectionStatus.PRIVACY_BLOCKED, null, emptyList(),
            listOf("No registered adapter meets the requested data-sensitivity policy.")
        )
        val selectionCandidates = request.integrationContext?.let { context ->
            val routeCandidates = candidates.mapNotNull { candidate ->
                transportFor(candidate)?.let { transport ->
                    IntegrationCandidate(
                        transport = transport,
                        available = candidate.isAvailable && candidate.missingPermissions.isEmpty(),
                        detail = candidate.explanation
                    )
                }
            }
            if (routeCandidates.isEmpty()) {
                candidates
            } else {
                val route = IntegrationRoutePolicy.choose(routeCandidates, context)
                if (route.selected == null) {
                    val needsScreenConsent = route.rejected.values.any { it.contains("consent", ignoreCase = true) }
                    val needsAndroidPermission = candidates.any { it.missingPermissions.isNotEmpty() }
                    return if (needsScreenConsent || needsAndroidPermission) ToolSelectionResult(
                        ToolSelectionStatus.PERMISSION_REQUIRED,
                        null,
                        candidates,
                        route.rejected.values.toList() + if (needsScreenConsent) {
                            listOf("Enable screen-inspection consent before selecting an Accessibility integration.")
                        } else emptyList()
                    ) else ToolSelectionResult(
                        ToolSelectionStatus.UNAVAILABLE,
                        null,
                        candidates,
                        route.rejected.values.toList().ifEmpty { listOf(route.explanation) }
                    )
                }
                val preferred = candidates.filter { transportFor(it) == route.selected }
                val remaining = candidates.filterNot { transportFor(it) == route.selected }
                preferred + remaining
            }
        } ?: candidates
        val selected = selectionCandidates.firstOrNull { it.isAvailable && it.missingPermissions.isEmpty() }
        if (selected != null) return ToolSelectionResult(
            ToolSelectionStatus.SELECTED, selected, selectionCandidates.drop(1), emptyList()
        )
        val permissionCandidate = candidates.firstOrNull { it.missingPermissions.isNotEmpty() }
        return if (permissionCandidate != null) ToolSelectionResult(
            ToolSelectionStatus.PERMISSION_REQUIRED, null, candidates,
            listOf("User permission setup is required. ERA must not request or grant it silently.")
        ) else ToolSelectionResult(
            ToolSelectionStatus.UNAVAILABLE, null, candidates,
            candidates.map { it.explanation }.distinct()
        )
    }

    private fun score(
        id: CapabilityId,
        offline: Boolean,
        request: ToolSelectionRequest,
        availability: CapabilityAvailability
    ): Int {
        var score = 100
        if (availability.isAvailable) score += 40
        if (offline) score += if (request.offlineRequired) 30 else 5
        if (id in request.preferredCapabilityIds) score += 15
        if (request.domains.contains(AgentDomain.ANDROID_OS) && id == CapabilityId.ANDROID_DEVICE) score += 10
        if (request.domains.contains(AgentDomain.WEB) && id in setOf(CapabilityId.NATIVE_WEBVIEW, CapabilityId.EXTERNAL_CHROME)) score += 10
        if (request.risk >= TaskRisk.HIGH && id == CapabilityId.VISUAL_INSPECTION) score -= 10
        score -= availability.missingPermissions.size * 5
        return score
    }

    private fun transportFor(candidate: ToolCandidate): IntegrationTransport? = when (candidate.capabilityId) {
        CapabilityId.ANDROID_DEVICE -> when (candidate.actionName) {
            "device.app.launch", "device.website.open", "device.dialer.open",
            "device.sms.create_draft", "device.whatsapp.share_draft",
            "device.camera.open", "device.settings.open" -> IntegrationTransport.ANDROID_INTENT
            "device.torch.set", "device.volume.adjust", "device.volume.set_percent" ->
                IntegrationTransport.ANDROID_PLATFORM_API
            else -> IntegrationTransport.ANDROID_PLATFORM_API
        }
        CapabilityId.NATIVE_WEBVIEW -> IntegrationTransport.BROWSER_DOM
        CapabilityId.EXTERNAL_CHROME,
        CapabilityId.ACCESSIBILITY_UI -> IntegrationTransport.ACCESSIBILITY_TREE
        else -> null
    }

    private fun privacyCeiling(id: CapabilityId): ToolDataSensitivity = when (id) {
        CapabilityId.ANDROID_DEVICE, CapabilityId.NATIVE_WEBVIEW, CapabilityId.EXTERNAL_CHROME,
        CapabilityId.ACCESSIBILITY_UI, CapabilityId.FILE_STORAGE -> ToolDataSensitivity.PERSONAL
        CapabilityId.VISUAL_INSPECTION, CapabilityId.SEARCH_AND_RECOGNITION -> ToolDataSensitivity.PUBLIC
        else -> ToolDataSensitivity.PUBLIC
    }
}
