package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.planner.TaskPlan
import java.security.MessageDigest

/** Bridges a validated planner DAG into the bounded local execution ledger. */
class TaskExecutionCoordinator private constructor(
    val plan: TaskPlan,
    val ledger: TaskExecutionLedger
) {
    fun readySteps(): List<TaskStepRecord> = ledger.readySteps()
    fun start(stepId: String) = ledger.transition(stepId, TaskStepState.OBSERVING, "Fresh screen observation requested.")
    fun readyForReview(stepId: String, summary: String) = ledger.transition(stepId, TaskStepState.READY_FOR_REVIEW, summary)
    fun approve(stepId: String) = ledger.transition(stepId, TaskStepState.APPROVED, "User approved this step.")
    fun reportDispatched(stepId: String) = ledger.transition(stepId, TaskStepState.EXECUTION_REPORTED, "Android action API accepted the request.")
    fun verify(stepId: String, evidence: String) = ledger.transition(stepId, TaskStepState.VERIFIED, evidence)
    fun fail(stepId: String, reason: String) = ledger.recordFailure(stepId, reason)
    fun cancel() = ledger.cancelPending("Cancelled by user.")

    companion object {
        fun from(plan: TaskPlan, persistence: TaskLedgerPersistence? = null): TaskExecutionCoordinator {
            val errors = plan.validate()
            require(errors.isEmpty()) { "Invalid task plan: ${errors.joinToString()}" }
            val steps = plan.steps.map { step ->
                AgentTaskStep(
                    id = step.id.toString(),
                    instruction = step.description,
                    expectedResult = step.acceptanceCriteria.joinToString("; "),
                    dependsOn = step.dependsOn.map { it.toString() }.toSet(),
                    retryLimit = step.retryLimit,
                    requiresFreshApproval = true
                )
            }
            val namespace = MessageDigest.getInstance("SHA-256")
                .digest("${plan.planVersion}|${plan.goal}".toByteArray())
                .take(16).joinToString("") { byte -> "%02x".format(byte) }
            return TaskExecutionCoordinator(plan, TaskExecutionLedger(steps, persistence, namespace))
        }
    }
}
