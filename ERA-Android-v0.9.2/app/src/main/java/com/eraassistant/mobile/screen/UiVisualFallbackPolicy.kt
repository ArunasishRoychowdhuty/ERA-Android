package com.eraassistant.mobile.screen

/** This policy chooses an observation mode but never captures a screenshot itself. */
data class UiUnderstandingInput(
    val explicitVisualRequest: Boolean,
    val semanticSnapshotAvailable: Boolean,
    val semanticSnapshotSufficient: Boolean
)

enum class UiUnderstandingDecision {
    USE_SEMANTIC_UI,
    VISUAL_REQUESTED_BY_USER,
    ASK_BEFORE_VISUAL_FALLBACK
}

object UiVisualFallbackPolicy {
    fun decide(input: UiUnderstandingInput): UiUnderstandingDecision = when {
        input.explicitVisualRequest -> UiUnderstandingDecision.VISUAL_REQUESTED_BY_USER
        input.semanticSnapshotAvailable && input.semanticSnapshotSufficient ->
            UiUnderstandingDecision.USE_SEMANTIC_UI
        else -> UiUnderstandingDecision.ASK_BEFORE_VISUAL_FALLBACK
    }
}
