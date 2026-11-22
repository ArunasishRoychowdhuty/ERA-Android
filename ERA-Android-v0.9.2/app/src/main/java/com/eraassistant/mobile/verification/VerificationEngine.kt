package com.eraassistant.mobile.verification

import com.eraassistant.mobile.capability.ActionResultStatus
import com.eraassistant.mobile.capability.StructuredActionResult
import com.eraassistant.mobile.observation.NormalizedObservation

/**
 * Typed verification statuses for action outcomes.
 */
enum class VerificationStatus {
    VERIFIED,
    NOT_VERIFIED,
    RETRY_REQUIRED,
    REPLAN_REQUIRED,
    VERIFICATION_UNAVAILABLE;

    val isSuccessful: Boolean get() = this == VERIFIED
}

/**
 * Detailed evidence of an individual verification evaluation.
 */
data class VerificationEvidence(
    val ruleName: String,
    val status: VerificationStatus,
    val expectedOutcome: String,
    val actualObservation: String,
    val rationale: String,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

/**
 * Complete verification report for an executed action.
 */
data class ActionVerificationReport(
    val actionId: String,
    val overallStatus: VerificationStatus,
    val evidences: List<VerificationEvidence>,
    val summary: String,
    val canRetry: Boolean = false,
    val suggestedReplanReason: String? = null,
    val completedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Contract for a verification rule.
 */
interface VerificationRule {
    val name: String
    fun evaluate(
        expectedCriteria: List<String>,
        preObservation: NormalizedObservation?,
        postObservation: NormalizedObservation?,
        actionResult: StructuredActionResult
    ): VerificationEvidence
}

/**
 * Verification Engine:
 * Evaluates action results against pre- and post-observations and explicit acceptance criteria.
 * Distinguishes between adapter success and actual goal attainment.
 */
class VerificationEngine(
    private val customRules: List<VerificationRule> = emptyList()
) {
    companion object {
        private const val MAX_POST_OBSERVATION_AGE_MS = 15_000L
    }
    /**
     * Verifies an executed action using pre-action observation, post-action observation,
     * and stated acceptance criteria.
     */
    fun verify(
        actionId: String,
        expectedCriteria: List<String>,
        preObservation: NormalizedObservation?,
        postObservation: NormalizedObservation?,
        actionResult: StructuredActionResult,
        retryCount: Int = 0,
        maxRetries: Int = 2
    ): ActionVerificationReport {
        // If the action adapter itself failed, verification status is failure/retryable
        if (actionResult.status == ActionResultStatus.FAILURE) {
            val error = actionResult.error
            val retryable = (error?.retryable == true) && (retryCount < maxRetries)
            val status = if (retryable) VerificationStatus.RETRY_REQUIRED else VerificationStatus.NOT_VERIFIED
            return ActionVerificationReport(
                actionId = actionId,
                overallStatus = status,
                evidences = listOf(
                    VerificationEvidence(
                        ruleName = "AdapterExecutionRule",
                        status = status,
                        expectedOutcome = "Adapter returns SUCCESS status.",
                        actualObservation = "Adapter returned ${actionResult.status}: ${error?.message}",
                        rationale = "Execution failed at the adapter level."
                    )
                ),
                summary = "Action failed execution: ${error?.message}",
                canRetry = retryable,
                suggestedReplanReason = if (!retryable) "Adapter execution failed non-retryably: ${error?.message}" else null
            )
        }

        // If the action is waiting for user, verification is paused pending user confirmation
        if (actionResult.status == ActionResultStatus.WAITING_USER) {
            return ActionVerificationReport(
                actionId = actionId,
                overallStatus = VerificationStatus.VERIFICATION_UNAVAILABLE,
                evidences = listOf(
                    VerificationEvidence(
                        ruleName = "WaitingUserRule",
                        status = VerificationStatus.VERIFICATION_UNAVAILABLE,
                        expectedOutcome = "User manual interaction or dispatch.",
                        actualObservation = actionResult.outputData["userPrompt"]?.toString() ?: "Waiting for user action.",
                        rationale = "Action requires manual user dispatch/confirmation before verification is possible."
                    )
                ),
                summary = "Waiting for user manual interaction."
            )
        }

        // A successful adapter response or non-empty output is not proof that the user's
        // expected outcome occurred. Without explicit criteria, report verification unavailable.
        if (expectedCriteria.isEmpty()) {
            return ActionVerificationReport(
                actionId = actionId,
                overallStatus = VerificationStatus.VERIFICATION_UNAVAILABLE,
                evidences = listOf(
                    VerificationEvidence(
                        ruleName = "MissingAcceptanceCriteriaRule",
                        status = VerificationStatus.VERIFICATION_UNAVAILABLE,
                        expectedOutcome = "Explicit action acceptance criteria.",
                        actualObservation = "No acceptance criteria were supplied.",
                        rationale = "An adapter result/output alone cannot establish that the requested outcome was achieved."
                    )
                ),
                summary = "Verification unavailable: no explicit acceptance criteria were provided."
            )
        }

        if (postObservation == null) {
            return ActionVerificationReport(
                actionId = actionId,
                overallStatus = VerificationStatus.VERIFICATION_UNAVAILABLE,
                evidences = expectedCriteria.map {
                    VerificationEvidence(
                        ruleName = "MissingPostObservationRule",
                        status = VerificationStatus.VERIFICATION_UNAVAILABLE,
                        expectedOutcome = it,
                        actualObservation = "No post-action observation was supplied.",
                        rationale = "The pre-action observation cannot be reused as evidence of a post-action result."
                    )
                },
                summary = "Verification unavailable: no post-action observation was captured."
            )
        }

        if (postObservation.isStale(MAX_POST_OBSERVATION_AGE_MS)) {
            return ActionVerificationReport(
                actionId = actionId,
                overallStatus = VerificationStatus.VERIFICATION_UNAVAILABLE,
                evidences = expectedCriteria.map {
                    VerificationEvidence(
                        ruleName = "StalePostObservationRule",
                        status = VerificationStatus.VERIFICATION_UNAVAILABLE,
                        expectedOutcome = it,
                        actualObservation = "Post-action observation is older than the allowed verification window.",
                        rationale = "A stale screen snapshot cannot establish the current result."
                    )
                },
                summary = "Verification unavailable: post-action observation is stale."
            )
        }

        if (preObservation != null &&
            (postObservation.observationId == preObservation.observationId ||
                postObservation.capturedAtEpochMs <= preObservation.capturedAtEpochMs)) {
            return ActionVerificationReport(
                actionId = actionId,
                overallStatus = VerificationStatus.VERIFICATION_UNAVAILABLE,
                evidences = expectedCriteria.map {
                    VerificationEvidence(
                        ruleName = "NonFreshPostObservationRule",
                        status = VerificationStatus.VERIFICATION_UNAVAILABLE,
                        expectedOutcome = it,
                        actualObservation = "Post-action observation is not newer than the pre-action observation.",
                        rationale = "Verification requires a distinct observation captured after dispatch."
                    )
                },
                summary = "Verification unavailable: the observation was not refreshed after the action."
            )
        }

        // Evaluate against expected acceptance criteria
        val evidences = mutableListOf<VerificationEvidence>()
        for (criterion in expectedCriteria) {
            val evidence = evaluateCriterion(criterion, preObservation, postObservation, actionResult)
            evidences.add(evidence)
        }

        // Decide overall status
        val allVerified = evidences.all { it.status == VerificationStatus.VERIFIED }
        val anyReplan = evidences.any { it.status == VerificationStatus.REPLAN_REQUIRED }
        val anyRetry = evidences.any { it.status == VerificationStatus.RETRY_REQUIRED }
        val allUnavailable = evidences.all { it.status == VerificationStatus.VERIFICATION_UNAVAILABLE }

        val overallStatus = when {
            allVerified -> VerificationStatus.VERIFIED
            anyReplan -> VerificationStatus.REPLAN_REQUIRED
            anyRetry -> if (retryCount < maxRetries) VerificationStatus.RETRY_REQUIRED else VerificationStatus.REPLAN_REQUIRED
            allUnavailable -> VerificationStatus.VERIFICATION_UNAVAILABLE
            else -> if (retryCount < maxRetries) VerificationStatus.RETRY_REQUIRED else VerificationStatus.NOT_VERIFIED
        }

        val canRetry = (overallStatus == VerificationStatus.RETRY_REQUIRED) && (retryCount < maxRetries)
        val replanReason = if (overallStatus == VerificationStatus.REPLAN_REQUIRED || (overallStatus == VerificationStatus.NOT_VERIFIED && !canRetry)) {
            evidences.firstOrNull { it.status != VerificationStatus.VERIFIED }?.rationale
                ?: "Verification criteria not satisfied."
        } else null

        return ActionVerificationReport(
            actionId = actionId,
            overallStatus = overallStatus,
            evidences = evidences,
            summary = "Verification evaluated ${evidences.size} criteria: overall status is $overallStatus.",
            canRetry = canRetry,
            suggestedReplanReason = replanReason
        )
    }

    private fun evaluateCriterion(
        criterion: String,
        preObs: NormalizedObservation?,
        postObs: NormalizedObservation?,
        actionResult: StructuredActionResult
    ): VerificationEvidence {

        // 1. Check if custom rules handle this
        for (rule in customRules) {
            val ev = rule.evaluate(listOf(criterion), preObs, postObs, actionResult)
            if (ev.status != VerificationStatus.VERIFICATION_UNAVAILABLE) return ev
        }

        // 2. Element presence / text matching in post observation
        if (postObs != null) {
            // Check title or header
            if (postObs.titleOrHeader?.contains(criterion, ignoreCase = true) == true) {
                return VerificationEvidence(
                    ruleName = "TitleMatchRule",
                    status = VerificationStatus.VERIFIED,
                    expectedOutcome = criterion,
                    actualObservation = "Title matched: '${postObs.titleOrHeader}'",
                    rationale = "Target title contains expected criterion."
                )
            }
            // Check package or url
            if (postObs.targetPackageOrUrl?.contains(criterion, ignoreCase = true) == true) {
                return VerificationEvidence(
                    ruleName = "TargetMatchRule",
                    status = VerificationStatus.VERIFIED,
                    expectedOutcome = criterion,
                    actualObservation = "Target matched: '${postObs.targetPackageOrUrl}'",
                    rationale = "Target package or URL matches expected criterion."
                )
            }
            // Check elements in snapshot
            val matchingElements = postObs.elements.filter { el ->
                (el.label?.contains(criterion, ignoreCase = true) == true) ||
                (el.text?.contains(criterion, ignoreCase = true) == true) ||
                (el.resourceId?.contains(criterion, ignoreCase = true) == true)
            }
            if (matchingElements.isNotEmpty()) {
                val matched = matchingElements.first()
                return VerificationEvidence(
                    ruleName = "ElementMatchRule",
                    status = VerificationStatus.VERIFIED,
                    expectedOutcome = criterion,
                    actualObservation = "Found element [${matched.elementId}] '${matched.label ?: matched.text ?: matched.resourceId}'",
                    rationale = "UI contains matching element establishing the criterion."
                )
            }
        }

        // Action output is diagnostic metadata only. It is deliberately not accepted as
        // independent evidence that a UI/system outcome occurred.

        // Not found in a fresh post-observation -> RETRY_REQUIRED
        return VerificationEvidence(
            ruleName = "UnsatisfiedCriterionRule",
            status = VerificationStatus.RETRY_REQUIRED,
            expectedOutcome = criterion,
            actualObservation = "Criterion was not found in post-observation (target: ${postObs?.targetPackageOrUrl}, elements: ${postObs?.elements?.size ?: 0}).",
            rationale = "Observed state does not exhibit the expected criteria."
        )
    }
}
