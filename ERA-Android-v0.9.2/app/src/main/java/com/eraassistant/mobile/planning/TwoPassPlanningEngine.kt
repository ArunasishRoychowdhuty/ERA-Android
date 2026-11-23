package com.eraassistant.mobile.planning

import com.eraassistant.mobile.domain.DomainIntentClassifier
import com.eraassistant.mobile.domain.DomainIntentProfile
import com.eraassistant.mobile.domain.GoalRisk
import com.eraassistant.mobile.planner.AIPlanner
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.planner.TaskRisk

data class TwoPassPlanResult(
    val intent: DomainIntentProfile,
    val plan: TaskPlan,
    val validationErrors: List<String>,
    val policyWarnings: List<String>,
    val blockedStepIds: Set<Int>
) {
    val isValid: Boolean get() = validationErrors.isEmpty()
}

/** First extracts goal/domain/risk/constraints; second builds and locally guards a DAG. */
class TwoPassPlanningEngine(private val planner: AIPlanner) {
    suspend fun createPlan(request: String): TwoPassPlanResult {
        require(request.isNotBlank()) { "Goal request must not be blank." }
        val intent = DomainIntentClassifier.classify(request)
        val proposed = planner.createPlan(request)
        return enforcePlan(request, proposed, intent)
    }

    fun enforcePlan(
        request: String,
        proposed: TaskPlan,
        intent: DomainIntentProfile = DomainIntentClassifier.classify(request)
    ): TwoPassPlanResult {
        val warnings = mutableListOf<String>()
        val blocked = linkedSetOf<Int>()

        val steps = proposed.steps.map { step ->
            val actionText = "${step.title} ${step.description}".lowercase()
            val prohibited = when {
                intent.constraints.contains("DRAFT_ONLY") || intent.constraints.contains("NO_SEND") ->
                    containsAny(actionText, "send", "পাঠাও", "send message")
                intent.constraints.contains("NO_PUBLISH") ->
                    containsAny(actionText, "publish", "post", "প্রকাশ", "পোস্ট")
                intent.constraints.contains("NO_DELETE") ->
                    containsAny(actionText, "delete", "remove", "মুছে ফেল", "ডিলিট")
                intent.constraints.contains("EXPLANATION_ONLY") ->
                    containsAny(actionText, "open ", "launch", "execute", "চালু কর", "খুল")
                else -> false
            }
            when {
                prohibited -> {
                    blocked += step.id
                    warnings += "Step ${step.id} blocked because it conflicts with an explicit user constraint."
                    step.copy(
                        title = "BLOCKED — ${step.title}",
                        description = "Blocked by the user's explicit constraint. No capability may dispatch this step.",
                        requiredCapabilities = emptySet(), retryLimit = 0,
                        risk = TaskRisk.CRITICAL, reversible = false, requiresUserApproval = true
                    )
                }
                isConsequential(actionText) -> {
                    warnings += "Step ${step.id} is consequential and requires fresh, exact user approval."
                    step.copy(
                        retryLimit = 0,
                        risk = if (step.risk.ordinal < TaskRisk.HIGH.ordinal) TaskRisk.HIGH else step.risk,
                        reversible = false,
                        requiresUserApproval = true
                    )
                }
                else -> step
            }
        }
        val overallRisk = steps.maxByOrNull { it.risk.ordinal }?.risk ?: proposed.overallRisk
        val plan = proposed.copy(
            steps = steps,
            overallRisk = overallRisk,
            note = proposed.note + " Two-pass policy applied: domain/risk extraction, explicit-constraint enforcement, exact capability validation and review-gated consequential actions."
        )
        val errors = plan.validate()
        if (intent.risk == GoalRisk.CRITICAL) warnings += "Critical financial/consequential intent detected; final action remains a user handoff."
        return TwoPassPlanResult(intent, plan, errors, warnings, blocked)
    }

    private fun isConsequential(text: String): Boolean = containsAny(
        text, "send", "publish", "post", "submit", "delete", "remove", "pay",
        "purchase", "buy", "transfer", "install", "পাঠাও", "প্রকাশ", "পোস্ট",
        "জমা দাও", "মুছে ফেল", "পেমেন্ট", "ট্রান্সফার"
    )

    private fun containsAny(text: String, vararg values: String): Boolean = values.any(text::contains)
}
