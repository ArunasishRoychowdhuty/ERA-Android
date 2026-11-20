package com.eraassistant.mobile.screen

/** Proposed action from a planner. It is data only and is never executed by this class. */
enum class SemanticUiAction { CLICK, LONG_CLICK, FOCUS, SCROLL_FORWARD, SCROLL_BACKWARD, TYPE_TEXT }

data class UiActionProposal(
    val selector: SemanticUiSelector,
    val action: SemanticUiAction,
    val text: String? = null,
    val rationale: String
)

sealed interface UiActionValidation {
    data class ReadyForUserReview(val target: UiElement, val action: SemanticUiAction) : UiActionValidation
    data class Rejected(val reason: String) : UiActionValidation
    data object NeedsDisambiguation : UiActionValidation
    data object NotFound : UiActionValidation
}

/** Validation only. The app must present the target and obtain a fresh user approval before acting. */
object UiActionProposalValidator {
    fun validate(root: UiElement, proposal: UiActionProposal): UiActionValidation {
        val resolution = SemanticUiSelectorEngine.resolve(root, proposal.selector)
        val target = when (resolution) {
            SelectorResolution.NotFound -> return UiActionValidation.NotFound
            is SelectorResolution.Ambiguous -> return UiActionValidation.NeedsDisambiguation
            is SelectorResolution.Unique -> resolution.element
        }
        if (!target.enabled) return UiActionValidation.Rejected("Target is disabled.")
        val supported = when (proposal.action) {
            SemanticUiAction.CLICK -> target.clickable && "click" in target.actions
            SemanticUiAction.LONG_CLICK -> target.longClickable && "long_click" in target.actions
            SemanticUiAction.FOCUS -> target.focusable && "focus" in target.actions
            SemanticUiAction.SCROLL_FORWARD -> target.scrollable && "scroll_forward" in target.actions
            SemanticUiAction.SCROLL_BACKWARD -> target.scrollable && "scroll_backward" in target.actions
            SemanticUiAction.TYPE_TEXT -> target.editable && !target.password && !proposal.text.isNullOrEmpty() && "set_text" in target.actions
        }
        if (!supported) return UiActionValidation.Rejected("The requested action is not exposed by this UI element.")
        return UiActionValidation.ReadyForUserReview(target, proposal.action)
    }
}
