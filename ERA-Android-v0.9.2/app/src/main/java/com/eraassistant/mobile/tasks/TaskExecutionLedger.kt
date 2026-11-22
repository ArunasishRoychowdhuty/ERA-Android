package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.planner.TaskCapability
import com.eraassistant.mobile.planner.TaskRisk

/** Durable task progress. USER_CONFIRMED is a user attestation; MACHINE_VERIFIED requires verifier evidence. */
enum class TaskStepState {
    PENDING, OBSERVING, READY_FOR_REVIEW, APPROVED, WAITING_USER,
    REJECTED, USER_REPORTED, USER_CONFIRMED, MACHINE_VERIFIED,
    FAILED, NEEDS_REPLAN, RECOVERY_REQUIRED, CANCELLED
}

data class AgentTaskStep(
    val id: String,
    val instruction: String,
    val expectedResult: String,
    val proposal: com.eraassistant.mobile.screen.UiActionProposal? = null,
    val dependsOn: Set<String> = emptySet(),
    val retryLimit: Int = 2,
    val requiresFreshApproval: Boolean = true,
    val requiredCapabilities: Set<TaskCapability> = emptySet(),
    val risk: TaskRisk = TaskRisk.LOW,
    val reversible: Boolean = true,
    val requiresUserApproval: Boolean = false
)

data class TaskStepTransition(
    val fromState: TaskStepState,
    val toState: TaskStepState,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val note: String = ""
)

data class TaskStepRecord(
    val step: AgentTaskStep,
    val state: TaskStepState = TaskStepState.PENDING,
    val note: String = "",
    val retryCount: Int = 0,
    val history: List<TaskStepTransition> = emptyList()
)

sealed interface TransitionResult {
    data class Updated(val record: TaskStepRecord) : TransitionResult
    data class Rejected(val reason: String) : TransitionResult
}

/**
 * Durable task-progress ledger. A dependent step becomes ready only after each prerequisite
 * has a user-confirmed or independently machine-verified outcome. State changes are persisted
 * before in-memory state is advanced, and every transition is retained in a bounded history.
 */
class TaskExecutionLedger(
    steps: List<AgentTaskStep>,
    private val persistence: TaskLedgerPersistence? = null,
    private val namespace: String = "default"
) {
    private val records = LinkedHashMap<String, TaskStepRecord>().apply {
        val restored = persistence?.load(namespace).orEmpty().associateBy { it.step.id }
        steps.distinctBy { it.id }.forEach { step ->
            val saved = restored[step.id]
            val compatible = saved != null &&
                saved.step.instruction == step.instruction &&
                saved.step.expectedResult == step.expectedResult &&
                saved.step.dependsOn == step.dependsOn &&
                saved.step.retryLimit == step.retryLimit &&
                saved.step.requiresFreshApproval == step.requiresFreshApproval &&
                saved.step.requiredCapabilities == step.requiredCapabilities &&
                saved.step.risk == step.risk &&
                saved.step.reversible == step.reversible &&
                saved.step.requiresUserApproval == step.requiresUserApproval &&
                saved.step.proposal == step.proposal
            val restoredRecord = if (compatible) {
                // A live observation/review/approval cannot survive process recreation.
                val restoredState = when (saved.state) {
                    TaskStepState.OBSERVING,
                    TaskStepState.READY_FOR_REVIEW,
                    TaskStepState.APPROVED,
                    TaskStepState.WAITING_USER,
                    TaskStepState.USER_REPORTED -> TaskStepState.RECOVERY_REQUIRED
                    else -> saved.state
                }
                val restoreNote = if (restoredState != saved.state) {
                    "Restored after process recreation. Inspect the target app, capture a fresh observation and review before continuing."
                } else saved.note
                val restoredHistory = if (restoredState != saved.state) {
                    appendHistory(saved.history, saved.state, restoredState, restoreNote)
                } else saved.history.takeLast(MAX_HISTORY_ENTRIES)
                saved.copy(
                    step = step,
                    state = restoredState,
                    note = restoreNote,
                    history = restoredHistory
                )
            } else TaskStepRecord(step)
            put(step.id, restoredRecord)
        }
    }

    init {
        require(records.size == steps.size) { "Task step IDs must be unique." }
        require(records.values.all { it.step.id.isNotBlank() }) { "Task step IDs must not be blank." }
        require(records.values.all { it.step.retryLimit in 0..3 }) { "retryLimit must be between 0 and 3." }
        require(records.values.all { it.step.dependsOn.all(records::containsKey) }) { "All dependencies must reference known task steps." }
        require(!hasDependencyCycle()) { "Task dependency graph must be acyclic." }
        check(persist(records.values.toList())) { "Task ledger could not be durably initialized." }
    }

    @Synchronized fun get(stepId: String): TaskStepRecord? = records[stepId]
    @Synchronized fun all(): List<TaskStepRecord> = records.values.toList()
    @Synchronized fun history(stepId: String): List<TaskStepTransition> = records[stepId]?.history.orEmpty()

    @Synchronized
    fun readySteps(): List<TaskStepRecord> = records.values.filter { record ->
        record.state in setOf(TaskStepState.PENDING, TaskStepState.NEEDS_REPLAN) &&
            dependenciesCompleted(record.step)
    }

    @Synchronized
    fun transition(stepId: String, next: TaskStepState, note: String = ""): TransitionResult {
        val current = records[stepId] ?: return TransitionResult.Rejected("Unknown step.")
        if (next == TaskStepState.OBSERVING && !dependenciesCompleted(current.step)) {
            return TransitionResult.Rejected("Dependencies must have completed outcomes before this step can start.")
        }
        val allowed = ALLOWED[current.state].orEmpty()
        if (next !in allowed) return TransitionResult.Rejected("Invalid transition ${current.state} → $next.")
        if (next == TaskStepState.USER_CONFIRMED) {
            if (current.state != TaskStepState.USER_REPORTED) {
                return TransitionResult.Rejected("Only a user-reported step can receive user outcome confirmation.")
            }
            if (note.isBlank()) return TransitionResult.Rejected("User-confirmed outcome evidence is required.")
        }
        if (next == TaskStepState.APPROVED && current.state != TaskStepState.READY_FOR_REVIEW) {
            return TransitionResult.Rejected("Only a reviewed proposal can be approved.")
        }
        if (next == TaskStepState.MACHINE_VERIFIED && current.state != TaskStepState.APPROVED) {
            return TransitionResult.Rejected("Only an approved action can be marked machine-verified.")
        }
        if (next == TaskStepState.RECOVERY_REQUIRED && note.isBlank()) {
            return TransitionResult.Rejected("Recovery reason is required.")
        }
        val effectiveNote = if (next == TaskStepState.USER_REPORTED && note.isBlank()) {
            "User reports performing this step manually."
        } else note
        val updated = current.copy(
            state = next,
            note = effectiveNote.take(MAX_NOTE_LENGTH),
            history = appendHistory(current.history, current.state, next, effectiveNote)
        )
        return commit(stepId, updated)
    }

    @Synchronized
    fun recordFailure(stepId: String, reason: String): TransitionResult {
        val current = records[stepId] ?: return TransitionResult.Rejected("Unknown step.")
        if (current.state !in setOf(
                TaskStepState.OBSERVING,
                TaskStepState.APPROVED,
                TaskStepState.WAITING_USER,
                TaskStepState.USER_REPORTED,
                TaskStepState.FAILED
            )) {
            return TransitionResult.Rejected("The current step cannot record a failure from ${current.state}.")
        }
        val retries = current.retryCount + 1
        val next = if (retries <= current.step.retryLimit) TaskStepState.NEEDS_REPLAN else TaskStepState.FAILED
        val updated = current.copy(
            state = next,
            note = reason.take(MAX_NOTE_LENGTH),
            retryCount = retries,
            history = appendHistory(current.history, current.state, next, reason)
        )
        return commit(stepId, updated)
    }

    /** Marks an uncertain external side effect for manual reconciliation; it is never retried automatically. */
    @Synchronized
    fun markRecoveryRequired(stepId: String, reason: String): TransitionResult {
        val current = records[stepId] ?: return TransitionResult.Rejected("Unknown step.")
        if (current.state in COMPLETED_STATES || current.state == TaskStepState.CANCELLED) {
            return TransitionResult.Rejected("A completed or cancelled step cannot enter recovery.")
        }
        val updated = current.copy(
            state = TaskStepState.RECOVERY_REQUIRED,
            note = reason.take(MAX_NOTE_LENGTH),
            history = appendHistory(current.history, current.state, TaskStepState.RECOVERY_REQUIRED, reason)
        )
        return commit(stepId, updated)
    }

    /** Explicitly releases a recovery hold only after the user has inspected the target application. */
    @Synchronized
    fun confirmRecoveryInspection(stepId: String, evidence: String): TransitionResult {
        val current = records[stepId] ?: return TransitionResult.Rejected("Unknown step.")
        if (current.state != TaskStepState.RECOVERY_REQUIRED) {
            return TransitionResult.Rejected("This step is not waiting for recovery inspection.")
        }
        if (evidence.isBlank()) return TransitionResult.Rejected("Inspection evidence is required.")
        val note = ("Recovery inspection completed: " + evidence.trim()).take(MAX_NOTE_LENGTH)
        val updated = current.copy(
            state = TaskStepState.NEEDS_REPLAN,
            note = note,
            history = appendHistory(current.history, current.state, TaskStepState.NEEDS_REPLAN, note)
        )
        return commit(stepId, updated)
    }

    @Synchronized
    fun allCompleted(): Boolean = records.isNotEmpty() && records.values.all { it.state in COMPLETED_STATES }

    /** Backward-compatible method name; includes machine-verified steps but keeps the states distinct. */
    @Synchronized
    fun allUserConfirmed(): Boolean = allCompleted()

    @Synchronized
    fun allMachineVerified(): Boolean =
        records.isNotEmpty() && records.values.all { it.state == TaskStepState.MACHINE_VERIFIED }

    /**
     * Cancels steps that are certainly not dispatched. Steps that may have caused an
     * external side effect are retained as RECOVERY_REQUIRED and need manual inspection.
     */
    @Synchronized
    fun cancelPending(reason: String = "Cancelled by user.") {
        val candidate = records.values.map { record ->
            val next = when (record.state) {
                in COMPLETED_STATES, TaskStepState.CANCELLED -> record.state
                TaskStepState.OBSERVING,
                TaskStepState.APPROVED,
                TaskStepState.WAITING_USER,
                TaskStepState.USER_REPORTED,
                TaskStepState.FAILED,
                TaskStepState.RECOVERY_REQUIRED -> TaskStepState.RECOVERY_REQUIRED
                else -> TaskStepState.CANCELLED
            }
            if (next == record.state) record
            else {
                val note = if (next == TaskStepState.RECOVERY_REQUIRED) {
                    "Cancellation interrupted or followed an action whose external outcome may be uncertain. Inspect the target before retrying."
                } else reason
                record.copy(
                    state = next,
                    note = note.take(MAX_NOTE_LENGTH),
                    history = appendHistory(record.history, record.state, next, note)
                )
            }
        }
        check(persist(candidate)) { "Cancellation state could not be durably saved." }
        records.clear()
        candidate.forEach { records[it.step.id] = it }
    }

    @Synchronized
    fun clearPersistedState(): Boolean = persistence?.clear(namespace) ?: true

    private fun commit(stepId: String, updated: TaskStepRecord): TransitionResult {
        val candidate = records.values.map { if (it.step.id == stepId) updated else it }
        if (!persist(candidate)) return TransitionResult.Rejected("Task state could not be durably saved.")
        records[stepId] = updated
        return TransitionResult.Updated(updated)
    }

    private fun persist(candidate: List<TaskStepRecord> = records.values.toList()): Boolean =
        persistence?.save(namespace, candidate) ?: true

    private fun appendHistory(
        history: List<TaskStepTransition>,
        from: TaskStepState,
        to: TaskStepState,
        note: String
    ): List<TaskStepTransition> = (history + TaskStepTransition(
        fromState = from,
        toState = to,
        timestampEpochMs = System.currentTimeMillis(),
        note = note.take(MAX_NOTE_LENGTH)
    )).takeLast(MAX_HISTORY_ENTRIES)

    private fun dependenciesCompleted(step: AgentTaskStep): Boolean = step.dependsOn.all { id ->
        records[id]?.state in COMPLETED_STATES
    }

    private fun hasDependencyCycle(): Boolean {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            records[id]?.step?.dependsOn.orEmpty().forEach { if (visit(it)) return true }
            visiting -= id
            visited += id
            return false
        }
        return records.keys.any(::visit)
    }

    companion object {
        private const val MAX_NOTE_LENGTH = 500
        private const val MAX_HISTORY_ENTRIES = 100
        private val COMPLETED_STATES = setOf(TaskStepState.USER_CONFIRMED, TaskStepState.MACHINE_VERIFIED)
        private val ALLOWED = mapOf(
            TaskStepState.PENDING to setOf(TaskStepState.OBSERVING, TaskStepState.CANCELLED),
            TaskStepState.OBSERVING to setOf(TaskStepState.READY_FOR_REVIEW, TaskStepState.FAILED, TaskStepState.NEEDS_REPLAN, TaskStepState.RECOVERY_REQUIRED, TaskStepState.CANCELLED),
            TaskStepState.READY_FOR_REVIEW to setOf(TaskStepState.APPROVED, TaskStepState.REJECTED, TaskStepState.NEEDS_REPLAN, TaskStepState.CANCELLED),
            TaskStepState.APPROVED to setOf(TaskStepState.USER_REPORTED, TaskStepState.MACHINE_VERIFIED, TaskStepState.WAITING_USER, TaskStepState.FAILED, TaskStepState.NEEDS_REPLAN, TaskStepState.RECOVERY_REQUIRED, TaskStepState.CANCELLED),
            TaskStepState.WAITING_USER to setOf(TaskStepState.USER_REPORTED, TaskStepState.MACHINE_VERIFIED, TaskStepState.FAILED, TaskStepState.NEEDS_REPLAN, TaskStepState.RECOVERY_REQUIRED, TaskStepState.CANCELLED),
            TaskStepState.REJECTED to setOf(TaskStepState.NEEDS_REPLAN, TaskStepState.CANCELLED),
            TaskStepState.USER_REPORTED to setOf(TaskStepState.USER_CONFIRMED, TaskStepState.FAILED, TaskStepState.NEEDS_REPLAN, TaskStepState.RECOVERY_REQUIRED, TaskStepState.CANCELLED),
            TaskStepState.FAILED to setOf(TaskStepState.NEEDS_REPLAN, TaskStepState.RECOVERY_REQUIRED, TaskStepState.CANCELLED),
            TaskStepState.NEEDS_REPLAN to setOf(TaskStepState.OBSERVING, TaskStepState.CANCELLED),
            TaskStepState.RECOVERY_REQUIRED to setOf(TaskStepState.NEEDS_REPLAN, TaskStepState.CANCELLED),
            TaskStepState.USER_CONFIRMED to emptySet(),
            TaskStepState.MACHINE_VERIFIED to emptySet(),
            TaskStepState.CANCELLED to emptySet()
        )
    }
}
