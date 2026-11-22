package com.eraassistant.mobile.orchestration

import com.eraassistant.mobile.capability.CapabilityRegistry
import com.eraassistant.mobile.observation.NormalizedObservation
import com.eraassistant.mobile.planner.AIPlanner
import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.statemachine.ActionExecutionProgress
import com.eraassistant.mobile.statemachine.ExecutionState
import com.eraassistant.mobile.statemachine.TaskStateMachine
import com.eraassistant.mobile.verification.ActionVerificationReport
import java.util.concurrent.CancellationException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ReplanningContext(
    val goal: String,
    val completedActions: List<ActionExecutionProgress>,
    val failedAction: ActionExecutionProgress?,
    val verificationReport: ActionVerificationReport?,
    val latestObservation: NormalizedObservation?,
    val replanAttempt: Int,
    val maxReplans: Int = 3
)

/** Bounded, concurrent-safe planner feedback loop with plan-cycle detection. */
class PlannerFeedbackLoop(
    private val planner: AIPlanner,
    private val capabilityRegistry: CapabilityRegistry,
    private val maxReplans: Int = 3
) {
    private val replanCount = AtomicInteger(0)
    private val previousPlanFingerprints = ConcurrentHashMap.newKeySet<String>()

    init {
        require(maxReplans in 0..10) { "maxReplans must be between 0 and 10." }
    }

    fun currentReplanCount(): Int = replanCount.get()
    fun canReplan(): Boolean = replanCount.get() < maxReplans

    suspend fun requestReplan(
        stateMachine: TaskStateMachine,
        failedActionId: String,
        verificationReport: ActionVerificationReport?,
        latestObservation: NormalizedObservation?
    ): Result<TaskPlan> {
        val attempt = reserveAttempt()
            ?: return Result.failure(
                IllegalStateException("Replan budget exhausted: maximum $maxReplans attempts reached.")
            )

        val failedProgress = stateMachine.getActionProgress(failedActionId)
        val completed = stateMachine.allActionProgress().filter {
            it.state == ExecutionState.SUCCEEDED || it.state == ExecutionState.VERIFIED
        }
        val descriptors = capabilityRegistry.allDescriptors()
        val available = capabilityRegistry.discoverAvailableCapabilities()
        val availableIds = available.filterValues { it.isAvailable }.keys.map { it.id.value }.sorted()

        val prompt = buildString {
            appendLine("Replan attempt $attempt/$maxReplans for goal: \"${stateMachine.goal.take(1500)}\"")
            appendLine("Completed actions: ${completed.joinToString { it.name.take(100) }.take(1200)}")
            if (failedProgress != null) {
                appendLine("Failed action: \"${failedProgress.name.take(120)}\"")
                appendLine("Failure: ${failedProgress.lastError?.message.orEmpty().take(300)}")
            }
            verificationReport?.let {
                appendLine("Verification status: ${it.overallStatus}")
                appendLine("Verification reason: ${(it.suggestedReplanReason ?: it.summary).take(500)}")
            }
            latestObservation?.let {
                appendLine("Observed state (${it.source}, ${it.capturedAtEpochMs}): ${it.summary.take(500)}")
                appendLine("Target: ${it.targetPackageOrUrl.orEmpty().take(200)}")
            }
            appendLine("Registered capabilities: ${descriptors.joinToString { it.id.value }.take(600)}")
            appendLine("Currently available capabilities: ${availableIds.joinToString().take(400)}")
            appendLine("Return a different validated plan. Do not assume unavailable capabilities can run.")
        }

        val newPlan = try {
            planner.createPlan(prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return Result.failure(IllegalStateException("Planner failed during replan: ${safeMessage(error)}", error))
        }

        val errors = newPlan.validate()
        if (errors.isNotEmpty()) {
            return Result.failure(IllegalStateException("Replanned plan is invalid: ${errors.joinToString().take(1000)}"))
        }

        val capabilityErrors = TaskPlanDAG(newPlan).validateCapabilitySupport(capabilityRegistry)
        if (capabilityErrors.isNotEmpty()) {
            return Result.failure(IllegalStateException("Replanned plan requires unregistered capabilities: ${capabilityErrors.joinToString().take(1000)}"))
        }

        val fingerprint = computePlanFingerprint(newPlan)
        if (!previousPlanFingerprints.add(fingerprint)) {
            return Result.failure(
                IllegalStateException("Planner repeated a previously proposed plan. Replanning stopped to prevent a cycle.")
            )
        }
        return Result.success(newPlan)
    }

    private fun reserveAttempt(): Int? {
        while (true) {
            val current = replanCount.get()
            if (current >= maxReplans) return null
            if (replanCount.compareAndSet(current, current + 1)) return current + 1
        }
    }

    private fun computePlanFingerprint(plan: TaskPlan): String {
        val canonical = buildString {
            append(plan.planVersion).append('|').append(plan.goal).append('|')
            plan.steps.sortedBy { it.id }.forEach { step ->
                append(step.id).append(':').append(step.title).append(':').append(step.description)
                append(':').append(step.dependsOn.sorted().joinToString(","))
                append(':').append(step.requiredCapabilities.map { it.name }.sorted().joinToString(","))
                append(':').append(step.acceptanceCriteria.joinToString(";"))
                append(':').append(step.risk).append(':').append(step.requiresUserApproval).append('|')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "unknown").take(240)
}
