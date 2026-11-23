package com.eraassistant.mobile.execution

import com.eraassistant.mobile.planner.TaskRisk
import org.junit.Assert.*
import org.junit.Test

class AutonomyExecutionPolicyTest {
    private fun input(
        risk: TaskRisk = TaskRisk.LOW,
        reversible: Boolean = true,
        requiresApproval: Boolean = false,
        approved: Boolean = false,
        matching: Boolean = false,
        freshApproval: Boolean = false,
        observationRequired: Boolean = true,
        freshObservation: Boolean = true,
        permission: Boolean = true,
        available: Boolean = true,
        consequenceApproval: Boolean = false,
        allowConsequentialDispatch: Boolean = false
    ) = AutonomyGateInput(
        risk, reversible, requiresApproval, approved, matching, freshApproval,
        observationRequired, freshObservation, permission, available, consequenceApproval,
        allowConsequentialDispatch
    )

    @Test fun allowsOnlyReadyReversibleLowRiskAction() {
        assertEquals(
            AutonomyGateDecision.ALLOW_REVERSIBLE_ACTION,
            AutonomyExecutionPolicy.decide(input())
        )
    }

    @Test fun highRiskWaitsForFreshExactApprovalAndThenHandsOff() {
        assertEquals(
            AutonomyGateDecision.WAIT_FOR_EXACT_USER_APPROVAL,
            AutonomyExecutionPolicy.decide(input(risk = TaskRisk.HIGH, reversible = false, requiresApproval = true))
        )
        assertEquals(
            AutonomyGateDecision.ALLOW_APPROVED_ACTION,
            AutonomyExecutionPolicy.decide(input(risk = TaskRisk.HIGH, reversible = false, requiresApproval = true,
                approved = true, matching = true, freshApproval = true))
        )
    }

    @Test fun criticalRiskPreservesCoordinatorHandoffAndScopesVoiceOverrideToAccessibility() {
        val exactApproval = input(risk = TaskRisk.CRITICAL, approved = true, matching = true, freshApproval = true)
        assertEquals(
            AutonomyGateDecision.USER_HANDOFF_REQUIRED,
            AutonomyExecutionPolicy.decide(exactApproval)
        )
        assertEquals(
            AutonomyGateDecision.WAIT_FOR_EXACT_USER_APPROVAL,
            AutonomyExecutionPolicy.decide(exactApproval.copy(allowConsequentialDispatch = true))
        )
        assertEquals(
            AutonomyGateDecision.ALLOW_APPROVED_ACTION,
            AutonomyExecutionPolicy.decide(exactApproval.copy(
                allowConsequentialDispatch = true,
                consequentialApprovalGranted = true
            ))
        )
    }

    @Test fun staleObservationAndMissingPermissionBlock() {
        assertEquals(AutonomyGateDecision.BLOCK_MISSING_PERMISSION,
            AutonomyExecutionPolicy.decide(input(permission = false)))
        assertEquals(AutonomyGateDecision.BLOCK_STALE_OBSERVATION,
            AutonomyExecutionPolicy.decide(input(freshObservation = false)))
    }

    @Test fun mismatchedApprovalCannotAuthorize() {
        assertEquals(
            AutonomyGateDecision.BLOCK_INVALID_APPROVAL,
            AutonomyExecutionPolicy.decide(input(risk = TaskRisk.HIGH, requiresApproval = true,
                approved = true, matching = false, freshApproval = true))
        )
    }
}
