package com.eraassistant.mobile.integration

/**
 * Transport preference for an app or website task. The router always prefers the narrowest
 * supported integration and only falls back to screen automation when explicitly allowed.
 */
enum class IntegrationTransport {
    OFFICIAL_API,
    APP_FUNCTIONS,
    ANDROID_PLATFORM_API,
    ANDROID_INTENT,
    DEEP_LINK,
    BROWSER_DOM,
    ACCESSIBILITY_TREE,
    SCREENSHOT_GESTURE
}

data class IntegrationCandidate(
    val transport: IntegrationTransport,
    val available: Boolean,
    val supportsRequestedAction: Boolean = true,
    val detail: String = "",
    val confidence: Double = 1.0
)

data class IntegrationRouteContext(
    val androidApiLevel: Int,
    val screenInspectionConsented: Boolean = false,
    val explicitVisualCaptureRequested: Boolean = false,
    val visualActionApproved: Boolean = false,
    val visualObservationFresh: Boolean = false,
    val appFunctionsExtensionAvailable: Boolean = false
)

data class IntegrationRouteDecision(
    val selected: IntegrationTransport?,
    val rejected: Map<IntegrationTransport, String>,
    val explanation: String
)

object IntegrationRoutePolicy {
    const val APP_FUNCTIONS_MIN_API = 36
    const val APP_FUNCTIONS_EXTENSION_MIN_API = 34
    const val MIN_VISUAL_CONFIDENCE = 0.90

    private val preference = listOf(
        IntegrationTransport.OFFICIAL_API,
        IntegrationTransport.APP_FUNCTIONS,
        IntegrationTransport.ANDROID_PLATFORM_API,
        IntegrationTransport.ANDROID_INTENT,
        IntegrationTransport.DEEP_LINK,
        IntegrationTransport.BROWSER_DOM,
        IntegrationTransport.ACCESSIBILITY_TREE,
        IntegrationTransport.SCREENSHOT_GESTURE
    )

    fun choose(
        candidates: List<IntegrationCandidate>,
        context: IntegrationRouteContext
    ): IntegrationRouteDecision {
        val byTransport = candidates.associateBy { it.transport }
        val rejected = linkedMapOf<IntegrationTransport, String>()

        for (transport in preference) {
            val candidate = byTransport[transport] ?: continue
            val reason = rejectionReason(candidate, context)
            if (reason != null) {
                rejected[transport] = reason
                continue
            }
            return IntegrationRouteDecision(
                selected = transport,
                rejected = rejected,
                explanation = "Selected $transport using the highest-priority available integration."
            )
        }

        return IntegrationRouteDecision(
            selected = null,
            rejected = rejected,
            explanation = "No permitted integration can perform this action. ERA must ask the user or report it unsupported."
        )
    }

    private fun rejectionReason(
        candidate: IntegrationCandidate,
        context: IntegrationRouteContext
    ): String? = when {
        !candidate.available -> "Transport is unavailable."
        !candidate.supportsRequestedAction -> "Transport does not support the requested action."
        candidate.transport == IntegrationTransport.APP_FUNCTIONS &&
            context.androidApiLevel < APP_FUNCTIONS_MIN_API &&
            !(context.androidApiLevel >= APP_FUNCTIONS_EXTENSION_MIN_API &&
                context.appFunctionsExtensionAvailable) ->
            "AppFunctions requires API $APP_FUNCTIONS_MIN_API+, or API $APP_FUNCTIONS_EXTENSION_MIN_API+ with the App Function Extension available."
        candidate.transport == IntegrationTransport.ACCESSIBILITY_TREE &&
            !context.screenInspectionConsented ->
            "Screen-inspection consent is not active."
        candidate.transport == IntegrationTransport.SCREENSHOT_GESTURE &&
            !context.screenInspectionConsented ->
            "Screen-inspection consent is not active."
        candidate.transport == IntegrationTransport.SCREENSHOT_GESTURE &&
            !context.explicitVisualCaptureRequested ->
            "A screenshot was not explicitly requested for this task."
        candidate.transport == IntegrationTransport.SCREENSHOT_GESTURE &&
            !context.visualObservationFresh ->
            "The screenshot observation is stale."
        candidate.transport == IntegrationTransport.SCREENSHOT_GESTURE &&
            candidate.confidence < MIN_VISUAL_CONFIDENCE ->
            "Visual target confidence is below the safe threshold."
        candidate.transport == IntegrationTransport.SCREENSHOT_GESTURE &&
            !context.visualActionApproved ->
            "The exact visual target has not received one-time user approval."
        else -> null
    }
}
