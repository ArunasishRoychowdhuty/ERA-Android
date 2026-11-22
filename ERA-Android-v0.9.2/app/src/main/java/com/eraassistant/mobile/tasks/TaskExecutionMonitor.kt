package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.planner.TaskPlan
import com.eraassistant.mobile.statemachine.ExecutionState
import com.eraassistant.mobile.statemachine.TaskStateMachine

enum class TaskMonitorPhase {
    QUEUED, UNDERSTANDING, PLANNING, WAITING_FOR_APPROVAL,
    READY_FOR_DISPATCH, EXECUTING, VERIFYING, NEEDS_REPLAN, COMPLETED, FAILED, CANCELLED, RECOVERY_REQUIRED
}

data class TaskMonitorSnapshot(
    val goal: String,
    val phase: TaskMonitorPhase,
    val totalSteps: Int,
    val userConfirmedSteps: Int,
    val waitingApprovalSteps: Int,
    val approvedSteps: Int,
    val observingSteps: Int,
    val userReportedSteps: Int,
    val pendingSteps: Int,
    val needsReplanSteps: Int,
    val rejectedSteps: Int,
    val failedSteps: Int,
    val cancelledSteps: Int,
    val updatedAtEpochMs: Long,
    val detail: String,
    val currentExecutionState: ExecutionState = ExecutionState.CREATED,
    val activeCapabilityName: String? = null,
    val currentActionName: String? = null,
    val verificationStatusText: String? = null,
    val totalRetriesCount: Int = 0,
    val failureReason: String? = null,
    val recoveryRequired: Boolean = false,
    val machineVerifiedSteps: Int = 0,
    val recoveryRequiredSteps: Int = 0,
    val waitingUserSteps: Int = 0
) {
    val completedSteps: Int get() = userConfirmedSteps + machineVerifiedSteps

    fun toDisplayText(): String = buildString {
        appendLine("Status: ${phase.name.replace('_', ' ')} (${currentExecutionState.name})")
        currentActionName?.let { appendLine("Action: $it") }
        activeCapabilityName?.let { appendLine("Capability: $it") }
        if (recoveryRequired) {
            appendLine("RECOVERY REQUIRED: Inspect the target app and confirm its current state before retrying.")
        }
        if (totalRetriesCount > 0) appendLine("Retries: $totalRetriesCount")
        failureReason?.let { appendLine("Failure reason: $it") }
        verificationStatusText?.let { appendLine("Latest step detail: $it") }
        appendLine(
            "Steps: $completedSteps/$totalSteps completed " +
                "($userConfirmedSteps user-confirmed, $machineVerifiedSteps machine-verified) · " +
                "$pendingSteps pending · $observingSteps observing · " +
                "$waitingApprovalSteps awaiting review · $approvedSteps approved · " +
                "$waitingUserSteps waiting for user action · $userReportedSteps awaiting outcome · " +
                "$needsReplanSteps need replan · $recoveryRequiredSteps recovery required · " +
                "$rejectedSteps rejected · $failedSteps failed · $cancelledSteps cancelled"
        )
        appendLine(detail)
    }.trim()
}

/** Read-only monitor projection. Manual user attestation and independent verification stay distinct. */
object TaskExecutionMonitor {
    fun snapshot(
        plan: TaskPlan,
        records: List<TaskStepRecord>,
        nowEpochMs: Long = System.currentTimeMillis(),
        stateMachine: TaskStateMachine? = null,
        activeCapability: String? = null
    ): TaskMonitorSnapshot {
        val confirmed = records.count { it.state == TaskStepState.USER_CONFIRMED }
        val machineVerified = records.count { it.state == TaskStepState.MACHINE_VERIFIED }
        val completed = confirmed + machineVerified
        val waiting = records.count { it.state == TaskStepState.READY_FOR_REVIEW }
        val approved = records.count { it.state == TaskStepState.APPROVED }
        val observing = records.count { it.state == TaskStepState.OBSERVING }
        val reported = records.count { it.state == TaskStepState.USER_REPORTED }
        val waitingUser = records.count { it.state == TaskStepState.WAITING_USER }
        val pending = records.count { it.state == TaskStepState.PENDING }
        val needsReplan = records.count { it.state == TaskStepState.NEEDS_REPLAN }
        val recoverySteps = records.count { it.state == TaskStepState.RECOVERY_REQUIRED }
        val rejected = records.count { it.state == TaskStepState.REJECTED }
        val failed = records.count { it.state == TaskStepState.FAILED }
        val cancelled = records.count { it.state == TaskStepState.CANCELLED }
        val totalRetries = records.sumOf { it.retryCount }

        val smState = stateMachine?.getState() ?: ExecutionState.CREATED
        val recoveryReq = smState == ExecutionState.RECOVERY_REQUIRED || recoverySteps > 0
        val allCompleted = records.isNotEmpty() && records.all {
            it.state in setOf(TaskStepState.USER_CONFIRMED, TaskStepState.MACHINE_VERIFIED)
        }

        val phase = when {
            recoveryReq -> TaskMonitorPhase.RECOVERY_REQUIRED
            records.isEmpty() -> TaskMonitorPhase.PLANNING
            records.all { it.state == TaskStepState.CANCELLED } || smState == ExecutionState.CANCELLED -> TaskMonitorPhase.CANCELLED
            allCompleted -> TaskMonitorPhase.COMPLETED
            failed > 0 || smState == ExecutionState.FAILED -> TaskMonitorPhase.FAILED
            needsReplan > 0 || rejected > 0 || smState == ExecutionState.RETRYABLE_FAILURE -> TaskMonitorPhase.NEEDS_REPLAN
            waitingUser > 0 || smState == ExecutionState.WAITING_USER -> TaskMonitorPhase.VERIFYING
            reported > 0 -> TaskMonitorPhase.VERIFYING
            smState == ExecutionState.RUNNING -> TaskMonitorPhase.EXECUTING
            approved > 0 -> TaskMonitorPhase.READY_FOR_DISPATCH
            waiting > 0 -> TaskMonitorPhase.WAITING_FOR_APPROVAL
            observing > 0 || smState == ExecutionState.OBSERVING -> TaskMonitorPhase.UNDERSTANDING
            cancelled > 0 && pending == 0 -> TaskMonitorPhase.CANCELLED
            else -> TaskMonitorPhase.PLANNING
        }

        val detail = when (phase) {
            TaskMonitorPhase.RECOVERY_REQUIRED ->
                "An action was interrupted or its outcome is uncertain. Inspect the target app before a new attempt."
            TaskMonitorPhase.WAITING_FOR_APPROVAL ->
                "A step is ready for user review or explicit approval."
            TaskMonitorPhase.READY_FOR_DISPATCH ->
                "Plan step approved for review only. A trusted typed request and fresh action-specific approval are still required."
            TaskMonitorPhase.EXECUTING ->
                "A typed action request is being dispatched through a registered capability adapter."
            TaskMonitorPhase.VERIFYING ->
                "An action is waiting for manual follow-through or independent outcome verification."
            TaskMonitorPhase.NEEDS_REPLAN ->
                "A step was rejected or its expected outcome was not established; review a revised plan."
            TaskMonitorPhase.COMPLETED ->
                "All steps have completed outcomes. The counts distinguish user attestation from machine verification."
            TaskMonitorPhase.FAILED ->
                "At least one step failed. Inspect its evidence before creating another attempt."
            TaskMonitorPhase.CANCELLED ->
                "Execution was cancelled. Previously completed records were retained."
            TaskMonitorPhase.UNDERSTANDING ->
                "Collecting a fresh observation or reviewing the target state."
            else ->
                if (completed > 0) "Some steps are user-confirmed; ERA has not independently verified user-attested outcomes."
                else "Task is planned. The monitor itself does not dispatch Android actions."
        }

        val activeAction = records.firstOrNull {
            it.state in setOf(
                TaskStepState.OBSERVING,
                TaskStepState.READY_FOR_REVIEW,
                TaskStepState.APPROVED,
                TaskStepState.WAITING_USER,
                TaskStepState.USER_REPORTED,
                TaskStepState.RECOVERY_REQUIRED
            )
        }
        val currentError = records.firstOrNull {
            it.state in setOf(TaskStepState.FAILED, TaskStepState.RECOVERY_REQUIRED)
        }?.note?.takeIf(String::isNotBlank)

        return TaskMonitorSnapshot(
            goal = plan.goal,
            phase = phase,
            totalSteps = records.size,
            userConfirmedSteps = confirmed,
            waitingApprovalSteps = waiting,
            approvedSteps = approved,
            observingSteps = observing,
            userReportedSteps = reported,
            pendingSteps = pending,
            needsReplanSteps = needsReplan,
            rejectedSteps = rejected,
            failedSteps = failed,
            cancelledSteps = cancelled,
            updatedAtEpochMs = nowEpochMs,
            detail = detail,
            currentExecutionState = smState,
            activeCapabilityName = activeCapability,
            currentActionName = activeAction?.step?.instruction,
            verificationStatusText = activeAction?.note?.takeIf(String::isNotBlank),
            totalRetriesCount = totalRetries,
            failureReason = currentError,
            recoveryRequired = recoveryReq,
            machineVerifiedSteps = machineVerified,
            recoveryRequiredSteps = recoverySteps,
            waitingUserSteps = waitingUser
        )
    }
}
