package com.eraassistant.mobile.execution

import com.eraassistant.mobile.planner.TaskRisk

enum class AutonomyGateDecision {
    ALLOW_REVERSIBLE_ACTION, ALLOW_APPROVED_ACTION, WAIT_FOR_EXACT_USER_APPROVAL, USER_HANDOFF_REQUIRED,
    BLOCK_MISSING_PERMISSION, BLOCK_TOOL_UNAVAILABLE, BLOCK_STALE_OBSERVATION, BLOCK_INVALID_APPROVAL
}

data class AutonomyGateInput(
    val risk: TaskRisk,
    val reversible: Boolean,
    val requiresUserApproval: Boolean,
    val exactApprovalGranted: Boolean,
    val approvalMatchesAction: Boolean,
    val approvalFresh: Boolean,
    val observationRequired: Boolean,
    val observationFresh: Boolean,
    val permissionGranted: Boolean,
    val toolAvailable: Boolean,
    val consequentialApprovalGranted: Boolean = false,
    /** Opt-in used only by the direct Accessibility overlay path after a separate spoken OK. */
    val allowConsequentialDispatch: Boolean = false
)

/** Central policy gate. Consequential actions never run silently in autonomous mode. */
object AutonomyExecutionPolicy {
    fun decide(input: AutonomyGateInput): AutonomyGateDecision = when {
        !input.permissionGranted -> AutonomyGateDecision.BLOCK_MISSING_PERMISSION
        !input.toolAvailable -> AutonomyGateDecision.BLOCK_TOOL_UNAVAILABLE
        input.observationRequired && !input.observationFresh -> AutonomyGateDecision.BLOCK_STALE_OBSERVATION
        input.requiresUserApproval && input.exactApprovalGranted &&
            (!input.approvalMatchesAction || !input.approvalFresh) -> AutonomyGateDecision.BLOCK_INVALID_APPROVAL
        input.risk == TaskRisk.CRITICAL -> {
            if (!input.allowConsequentialDispatch) {
                AutonomyGateDecision.USER_HANDOFF_REQUIRED
            } else if (input.exactApprovalGranted && input.approvalMatchesAction &&
                input.approvalFresh && input.consequentialApprovalGranted) {
                AutonomyGateDecision.ALLOW_APPROVED_ACTION
            } else {
                AutonomyGateDecision.WAIT_FOR_EXACT_USER_APPROVAL
            }
        }
        input.risk == TaskRisk.HIGH || input.requiresUserApproval || !input.reversible ->
            if (input.exactApprovalGranted && input.approvalMatchesAction && input.approvalFresh) {
                AutonomyGateDecision.ALLOW_APPROVED_ACTION
            } else AutonomyGateDecision.WAIT_FOR_EXACT_USER_APPROVAL
        else -> AutonomyGateDecision.ALLOW_REVERSIBLE_ACTION
    }
}
