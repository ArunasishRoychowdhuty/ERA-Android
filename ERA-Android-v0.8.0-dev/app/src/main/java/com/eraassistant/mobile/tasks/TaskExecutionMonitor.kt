package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.planner.TaskPlan

enum class TaskMonitorPhase {
    QUEUED, UNDERSTANDING, PLANNING, WAITING_FOR_APPROVAL,
    EXECUTING, VERIFYING, COMPLETED, FAILED, CANCELLED
}

data class TaskMonitorSnapshot(
    val goal: String,
    val phase: TaskMonitorPhase,
    val totalSteps: Int,
    val verifiedSteps: Int,
    val waitingApprovalSteps: Int,
    val failedSteps: Int,
    val updatedAtEpochMs: Long,
    val detail: String
) {
    fun toDisplayText(): String = buildString {
        appendLine("Status: ${phase.name.replace('_', ' ')}")
        appendLine("Steps: $verifiedSteps/$totalSteps verified · $waitingApprovalSteps awaiting review · $failedSteps failed")
        appendLine(detail)
    }.trim()
}

/** Read-only monitor projection over a validated plan and its execution ledger. */
object TaskExecutionMonitor {
    fun snapshot(
        plan: TaskPlan,
        records: List<TaskStepRecord>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): TaskMonitorSnapshot {
        val verified = records.count { it.state == TaskStepState.VERIFIED }
        val waiting = records.count { it.state == TaskStepState.READY_FOR_REVIEW || it.state == TaskStepState.APPROVED }
        val failed = records.count { it.state == TaskStepState.FAILED }
        val phase = when {
            records.isNotEmpty() && records.all { it.state == TaskStepState.CANCELLED } -> TaskMonitorPhase.CANCELLED
            failed > 0 -> TaskMonitorPhase.FAILED
            records.isNotEmpty() && verified == records.size -> TaskMonitorPhase.COMPLETED
            waiting > 0 -> TaskMonitorPhase.WAITING_FOR_APPROVAL
            else -> TaskMonitorPhase.PLANNING
        }
        val detail = when (phase) {
            TaskMonitorPhase.WAITING_FOR_APPROVAL -> "Plan preview only. Review required before any consequential step."
            TaskMonitorPhase.COMPLETED -> "All tracked steps have verified evidence."
            TaskMonitorPhase.FAILED -> "At least one step failed. Replan or cancel; no automatic retry is implied."
            TaskMonitorPhase.CANCELLED -> "Pending steps were cancelled by the user."
            else -> "Task is planned; this monitor does not dispatch Android actions."
        }
        return TaskMonitorSnapshot(plan.goal, phase, records.size, verified, waiting, failed, nowEpochMs, detail)
    }
}
