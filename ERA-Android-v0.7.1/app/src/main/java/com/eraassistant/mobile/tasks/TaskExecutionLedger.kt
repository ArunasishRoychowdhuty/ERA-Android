package com.eraassistant.mobile.tasks

/** State model for bounded execution with explicit dependencies and verification. */
enum class TaskStepState {
    PENDING, OBSERVING, READY_FOR_REVIEW, APPROVED, REJECTED,
    EXECUTION_REPORTED, VERIFIED, FAILED, NEEDS_REPLAN, CANCELLED
}

data class AgentTaskStep(
    val id: String,
    val instruction: String,
    val expectedResult: String,
    val proposal: com.eraassistant.mobile.screen.UiActionProposal? = null,
    val dependsOn: Set<String> = emptySet(),
    val retryLimit: Int = 2,
    val requiresFreshApproval: Boolean = true
)

data class TaskStepRecord(
    val step: AgentTaskStep,
    val state: TaskStepState = TaskStepState.PENDING,
    val note: String = "",
    val retryCount: Int = 0
)

sealed interface TransitionResult {
    data class Updated(val record: TaskStepRecord) : TransitionResult
    data class Rejected(val reason: String) : TransitionResult
}

/**
 * Local task ledger. It never executes Android actions itself. A step becomes
 * runnable only after all dependencies have been verified.
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
                saved.step.retryLimit == step.retryLimit
            val restoredRecord = if (compatible) {
                val restoredState = when (saved!!.state) {
                    TaskStepState.APPROVED -> TaskStepState.READY_FOR_REVIEW
                    TaskStepState.OBSERVING, TaskStepState.EXECUTION_REPORTED -> TaskStepState.NEEDS_REPLAN
                    else -> saved.state
                }
                val restoreNote = if (restoredState != saved.state) {
                    "Restored after process recreation; take a fresh observation and review again."
                } else saved.note
                saved.copy(step = step, state = restoredState, note = restoreNote)
            } else TaskStepRecord(step)
            put(step.id, restoredRecord)
        }
    }

    init {
        require(records.size == steps.size) { "Task step IDs must be unique." }
        require(records.values.all { it.step.retryLimit in 0..3 }) { "retryLimit must be between 0 and 3." }
        require(records.values.all { it.step.dependsOn.all(records::containsKey) }) { "All dependencies must reference known task steps." }
        require(!hasDependencyCycle()) { "Task dependency graph must be acyclic." }
    }

    @Synchronized fun get(stepId: String): TaskStepRecord? = records[stepId]
    @Synchronized fun all(): List<TaskStepRecord> = records.values.toList()

    @Synchronized
    fun readySteps(): List<TaskStepRecord> = records.values.filter { record ->
        record.state in setOf(TaskStepState.PENDING, TaskStepState.NEEDS_REPLAN) && dependenciesVerified(record.step)
    }

    @Synchronized
    fun transition(stepId: String, next: TaskStepState, note: String = ""): TransitionResult {
        val current = records[stepId] ?: return TransitionResult.Rejected("Unknown step.")
        if (next == TaskStepState.OBSERVING && !dependenciesVerified(current.step)) {
            return TransitionResult.Rejected("Dependencies must be verified before this step can start.")
        }
        val allowed = ALLOWED[current.state].orEmpty()
        if (next !in allowed) return TransitionResult.Rejected("Invalid transition ${current.state} → $next.")
        if (next == TaskStepState.EXECUTION_REPORTED && current.state != TaskStepState.APPROVED) {
            return TransitionResult.Rejected("A fresh user approval is required before reporting any UI action.")
        }
        if (next == TaskStepState.APPROVED && current.state != TaskStepState.READY_FOR_REVIEW) {
            return TransitionResult.Rejected("Only a reviewed proposal can be approved.")
        }
        val updated = current.copy(state = next, note = note.take(MAX_NOTE_LENGTH))
        records[stepId] = updated
        persist()
        return TransitionResult.Updated(updated)
    }

    @Synchronized
    fun recordFailure(stepId: String, reason: String): TransitionResult {
        val current = records[stepId] ?: return TransitionResult.Rejected("Unknown step.")
        if (current.state !in setOf(TaskStepState.OBSERVING, TaskStepState.EXECUTION_REPORTED, TaskStepState.FAILED)) {
            return TransitionResult.Rejected("The current step cannot record a failure from ${current.state}.")
        }
        val retries = current.retryCount + 1
        val next = if (retries <= current.step.retryLimit) TaskStepState.NEEDS_REPLAN else TaskStepState.FAILED
        val updated = current.copy(state = next, note = reason.take(MAX_NOTE_LENGTH), retryCount = retries)
        records[stepId] = updated
        persist()
        return TransitionResult.Updated(updated)
    }

    @Synchronized
    fun allVerified(): Boolean = records.values.all { it.state == TaskStepState.VERIFIED }

    @Synchronized
    fun cancelPending(reason: String = "Cancelled by user.") {
        records.replaceAll { _, record ->
            if (record.state in setOf(TaskStepState.PENDING, TaskStepState.OBSERVING, TaskStepState.READY_FOR_REVIEW, TaskStepState.APPROVED, TaskStepState.NEEDS_REPLAN)) {
                record.copy(state = TaskStepState.CANCELLED, note = reason.take(MAX_NOTE_LENGTH))
            } else record
        }
        persist()
    }

    @Synchronized
    fun clearPersistedState() {
        persistence?.clear(namespace)
    }

    private fun persist() {
        persistence?.save(namespace, records.values.toList())
    }

    private fun dependenciesVerified(step: AgentTaskStep): Boolean = step.dependsOn.all { id ->
        records[id]?.state == TaskStepState.VERIFIED
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
        private val ALLOWED = mapOf(
            TaskStepState.PENDING to setOf(TaskStepState.OBSERVING, TaskStepState.CANCELLED),
            TaskStepState.OBSERVING to setOf(TaskStepState.READY_FOR_REVIEW, TaskStepState.FAILED, TaskStepState.NEEDS_REPLAN, TaskStepState.CANCELLED),
            TaskStepState.READY_FOR_REVIEW to setOf(TaskStepState.APPROVED, TaskStepState.REJECTED, TaskStepState.NEEDS_REPLAN, TaskStepState.CANCELLED),
            TaskStepState.APPROVED to setOf(TaskStepState.EXECUTION_REPORTED, TaskStepState.CANCELLED),
            TaskStepState.EXECUTION_REPORTED to setOf(TaskStepState.VERIFIED, TaskStepState.FAILED, TaskStepState.NEEDS_REPLAN),
            TaskStepState.FAILED to setOf(TaskStepState.NEEDS_REPLAN, TaskStepState.CANCELLED),
            TaskStepState.NEEDS_REPLAN to setOf(TaskStepState.OBSERVING, TaskStepState.CANCELLED)
        )
    }
}
