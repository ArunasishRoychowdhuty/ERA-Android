package com.eraassistant.mobile.statemachine

import com.eraassistant.mobile.capability.ExecutionError
import com.eraassistant.mobile.memory.PrivacyFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Formal task and action execution lifecycle states.
 */
enum class ExecutionState {
    CREATED,
    VALIDATING,
    READY,
    RUNNING,
    OBSERVING,
    VERIFIED,
    SUCCEEDED,
    WAITING_USER,
    RETRYABLE_FAILURE,
    FAILED,
    CANCELLED,
    RECOVERY_REQUIRED;

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, FAILED, CANCELLED)

    val isActive: Boolean
        get() = this in setOf(RUNNING, OBSERVING, WAITING_USER, RETRYABLE_FAILURE, RECOVERY_REQUIRED)
}

/**
 * An individual recorded state transition with audit metadata.
 */
data class StateTransitionRecord(
    val fromState: ExecutionState,
    val toState: ExecutionState,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val reason: String = "",
    val triggeredBy: String = "system"
)

/**
 * Result of a transition attempt.
 */
sealed interface TransitionOutcome {
    data class Accepted(val previousState: ExecutionState, val newState: ExecutionState, val record: StateTransitionRecord) : TransitionOutcome
    data class Rejected(val currentState: ExecutionState, val attemptedState: ExecutionState, val reason: String) : TransitionOutcome
}

/**
 * Action-level progress tracking within a task.
 */
data class ActionExecutionProgress(
    val actionId: String,
    val name: String,
    val state: ExecutionState = ExecutionState.CREATED,
    val retryCount: Int = 0,
    val maxRetries: Int = 2,
    val lastError: ExecutionError? = null,
    val history: List<StateTransitionRecord> = emptyList(),
    val verificationEvidence: String? = null
) {
    val canRetry: Boolean get() = retryCount < maxRetries
}

/**
 * Snapshot of complete task execution state.
 */
data class TaskStateSnapshot(
    val taskId: String,
    val goal: String,
    val state: ExecutionState,
    val actions: List<ActionExecutionProgress>,
    val history: List<StateTransitionRecord>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

/**
 * Strict, inspectable Execution State Machine.
 * Validates every state transition, rejects invalid transitions,
 * tracks action-level and task-level state, prevents duplicate runs,
 * and reconciles crash states.
 */
class TaskStateMachine(
    val taskId: String,
    val goal: String,
    initialActions: List<Pair<String, String>> = emptyList(), // id to name
    initialState: ExecutionState = ExecutionState.CREATED,
    initialActionStates: Map<String, ExecutionState> = emptyMap(),
    initialActionMaxRetries: Map<String, Int> = emptyMap(),
    private val persistence: TaskStatePersistence? = null
) {
    private val restoredSnapshot: TaskStateSnapshot? = persistence?.load(taskId)
    private var currentState: ExecutionState = restoredSnapshot?.state ?: initialState
    private val taskHistory = mutableListOf<StateTransitionRecord>()
    private val actionProgressMap = ConcurrentHashMap<String, ActionExecutionProgress>()
    private val createdAtEpochMs = restoredSnapshot?.createdAtEpochMs ?: System.currentTimeMillis()
    private var updatedAtEpochMs = restoredSnapshot?.updatedAtEpochMs ?: createdAtEpochMs

    init {
        require(taskId.isNotBlank()) { "Task ID must not be blank." }
        val expectedActions = initialActions.toMap()
        if (restoredSnapshot != null) {
            require(restoredSnapshot.taskId == taskId &&
                restoredSnapshot.goal == PrivacyFilter.sanitizeText(goal).take(2_000)) {
                "Persisted task state does not match the requested task identity."
            }
            val restoredActions = restoredSnapshot.actions.associateBy { it.actionId }
            val sanitizedExpectedActions = expectedActions.mapValues { PrivacyFilter.sanitizeText(it.value).take(2_000) }
            require(restoredActions.mapValues { it.value.name } == sanitizedExpectedActions) {
                "Persisted task actions do not match the current plan; refusing to merge unrelated history."
            }
            taskHistory.addAll(restoredSnapshot.history.takeLast(MAX_TASK_HISTORY))
            restoredSnapshot.actions.forEach { action ->
                val interrupted = action.state in RESTART_INTERRUPTED_STATES
                val history = if (interrupted) {
                    action.history + StateTransitionRecord(
                        action.state,
                        ExecutionState.RECOVERY_REQUIRED,
                        System.currentTimeMillis(),
                        "Action was interrupted by process restart; inspect the target app before retrying.",
                        "recovery_reconciliation"
                    )
                } else action.history
                actionProgressMap[action.actionId] = if (interrupted) {
                    action.copy(
                        state = ExecutionState.RECOVERY_REQUIRED,
                        history = history.takeLast(MAX_ACTION_HISTORY)
                    )
                } else action.copy(history = history.takeLast(MAX_ACTION_HISTORY))
            }
            if (currentState in RESTART_INTERRUPTED_STATES ||
                (actionProgressMap.values.any { it.state == ExecutionState.RECOVERY_REQUIRED } &&
                    currentState !in setOf(ExecutionState.SUCCEEDED, ExecutionState.FAILED, ExecutionState.CANCELLED, ExecutionState.RECOVERY_REQUIRED))) {
                val from = currentState
                currentState = ExecutionState.RECOVERY_REQUIRED
                taskHistory.add(
                    StateTransitionRecord(
                        from,
                        ExecutionState.RECOVERY_REQUIRED,
                        System.currentTimeMillis(),
                        "Process restarted while task was unfinished. Automatic continuation is blocked until recovery review.",
                        "recovery_reconciliation"
                    )
                )
            }
        } else {
            initialActions.forEach { (id, name) ->
                val restored = initialActionStates[id] ?: ExecutionState.CREATED
                val history = if (restored == ExecutionState.CREATED) emptyList() else listOf(
                    StateTransitionRecord(
                        ExecutionState.CREATED,
                        restored,
                        createdAtEpochMs,
                        "Reconstructed from durable task ledger.",
                        "ledger_restore"
                    )
                )
                actionProgressMap[id] = ActionExecutionProgress(
                    actionId = id,
                    name = name,
                    state = restored,
                    maxRetries = (initialActionMaxRetries[id] ?: 2).coerceIn(0, 5),
                    history = history
                )
            }
            taskHistory.add(StateTransitionRecord(ExecutionState.CREATED, currentState, createdAtEpochMs, "Task created"))
        }
        check(persistCurrentSnapshot()) {
            "Task state could not be durably committed; lifecycle transitions are disabled."
        }
    }

    @Synchronized
    fun getState(): ExecutionState = currentState

    @Synchronized
    fun getHistory(): List<StateTransitionRecord> = taskHistory.toList()

    @Synchronized
    fun getActionProgress(actionId: String): ActionExecutionProgress? = actionProgressMap[actionId]

    @Synchronized
    fun allActionProgress(): List<ActionExecutionProgress> = actionProgressMap.values.toList()

    @Synchronized
    fun snapshot(): TaskStateSnapshot = TaskStateSnapshot(
        taskId = taskId,
        goal = goal,
        state = currentState,
        actions = actionProgressMap.values.toList(),
        history = taskHistory.toList(),
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    /**
     * Attempts a task-level transition. Validates against the transition rules.
     */
    @Synchronized
    fun transitionTask(toState: ExecutionState, reason: String = "", triggeredBy: String = "system"): TransitionOutcome {
        if (currentState == toState) {
            return TransitionOutcome.Rejected(currentState, toState, "Already in state $toState.")
        }
        val allowedTargets = VALID_TASK_TRANSITIONS[currentState] ?: emptySet()
        if (toState !in allowedTargets) {
            return TransitionOutcome.Rejected(
                currentState,
                toState,
                "Invalid task transition: cannot move from $currentState to $toState. Allowed: $allowedTargets"
            )
        }
        val from = currentState
        val now = System.currentTimeMillis()
        val record = StateTransitionRecord(from, toState, now, reason.take(MAX_REASON), triggeredBy.take(80))
        val nextHistory = (taskHistory + record).takeLast(MAX_TASK_HISTORY)
        if (!persistSnapshot(toState, actionProgressMap.values.toList(), nextHistory, now)) {
            return TransitionOutcome.Rejected(currentState, toState, "Durable lifecycle write failed; state was not changed.")
        }
        currentState = toState
        updatedAtEpochMs = now
        taskHistory.clear()
        taskHistory.addAll(nextHistory)
        return TransitionOutcome.Accepted(from, toState, record)
    }

    /**
     * Attempts an action-level transition for an individual action within this task.
     */
    @Synchronized
    fun transitionAction(
        actionId: String,
        toState: ExecutionState,
        reason: String = "",
        error: ExecutionError? = null,
        verificationEvidence: String? = null
    ): TransitionOutcome {
        val current = actionProgressMap[actionId]
            ?: return TransitionOutcome.Rejected(
                ExecutionState.CREATED,
                toState,
                "Unknown actionId '$actionId'."
            )

        if (current.state == toState) {
            return TransitionOutcome.Rejected(current.state, toState, "Action already in state $toState.")
        }
        val allowed = VALID_ACTION_TRANSITIONS[current.state] ?: emptySet()
        if (toState !in allowed) {
            return TransitionOutcome.Rejected(
                current.state,
                toState,
                "Invalid action transition: cannot move from ${current.state} to $toState. Allowed: $allowed"
            )
        }

        if (toState == ExecutionState.RETRYABLE_FAILURE && !current.canRetry) {
            return TransitionOutcome.Rejected(current.state, toState, "Action retry budget is exhausted.")
        }
        val now = System.currentTimeMillis()
        val record = StateTransitionRecord(current.state, toState, now, reason.take(MAX_REASON))
        val newRetryCount = if (toState == ExecutionState.RETRYABLE_FAILURE) current.retryCount + 1 else current.retryCount
        val updated = current.copy(
            state = toState,
            retryCount = newRetryCount,
            lastError = error ?: current.lastError,
            history = (current.history + record).takeLast(MAX_ACTION_HISTORY),
            verificationEvidence = verificationEvidence?.take(MAX_REASON) ?: current.verificationEvidence
        )
        val nextActions = actionProgressMap.values.filterNot { it.actionId == actionId } + updated
        if (!persistSnapshot(currentState, nextActions, taskHistory, now)) {
            return TransitionOutcome.Rejected(current.state, toState, "Durable lifecycle write failed; action state was not changed.")
        }
        actionProgressMap[actionId] = updated
        updatedAtEpochMs = now
        return TransitionOutcome.Accepted(current.state, toState, record)
    }

    /**
     * Registers a new action into the state machine dynamically if needed.
     */
    @Synchronized
    fun registerAction(actionId: String, name: String, maxRetries: Int = 2) {
        if (!actionProgressMap.containsKey(actionId)) {
            val action = ActionExecutionProgress(
                actionId = actionId,
                name = name,
                maxRetries = maxRetries.coerceIn(0, 5)
            )
            val nextActions = actionProgressMap.values.toList() + action
            val now = System.currentTimeMillis()
            check(persistSnapshot(currentState, nextActions, taskHistory, now)) {
                "Could not persist the newly registered task action."
            }
            actionProgressMap[actionId] = action
            updatedAtEpochMs = now
        }
    }

    /**
     * Cancels the task if not already in a terminal state.
     */
    @Synchronized
    fun cancel(reason: String = "Cancelled by user."): TransitionOutcome =
        transitionTask(ExecutionState.CANCELLED, reason, triggeredBy = "user")

    /**
     * Reconciles process restart recovery.
     * Any active unfinished state (RUNNING, OBSERVING, WAITING_USER) must transition to RECOVERY_REQUIRED.
     */
    @Synchronized
    fun reconcileAfterRestart(): Boolean {
        val now = System.currentTimeMillis()
        val taskInterrupted = currentState in RESTART_INTERRUPTED_STATES
        val nextState = if (taskInterrupted) ExecutionState.RECOVERY_REQUIRED else currentState
        val transition = if (taskInterrupted) {
            listOf(
                StateTransitionRecord(
                    currentState,
                    ExecutionState.RECOVERY_REQUIRED,
                    now,
                    "Process was interrupted while $currentState. Inspect the target app before retrying.",
                    "recovery_reconciliation"
                )
            )
        } else emptyList()
        val nextActions = actionProgressMap.values.map { action ->
            if (action.state in RESTART_INTERRUPTED_STATES) {
                action.copy(
                    state = ExecutionState.RECOVERY_REQUIRED,
                    history = (action.history + StateTransitionRecord(
                        action.state,
                        ExecutionState.RECOVERY_REQUIRED,
                        now,
                        "Action interrupted by process death.",
                        "recovery_reconciliation"
                    )).takeLast(MAX_ACTION_HISTORY)
                )
            } else action
        }
        val changed = taskInterrupted || nextActions.zip(actionProgressMap.values).any { (a, b) -> a.state != b.state }
        if (!changed) return false
        val nextHistory = (taskHistory + transition).takeLast(MAX_TASK_HISTORY)
        if (!persistSnapshot(nextState, nextActions, nextHistory, now)) return false
        currentState = nextState
        actionProgressMap.clear()
        nextActions.forEach { actionProgressMap[it.actionId] = it }
        taskHistory.clear()
        taskHistory.addAll(nextHistory)
        updatedAtEpochMs = now
        return true
    }

    private fun persistCurrentSnapshot(): Boolean =
        persistSnapshot(currentState, actionProgressMap.values.toList(), taskHistory, updatedAtEpochMs)

    private fun persistSnapshot(
        state: ExecutionState,
        actions: List<ActionExecutionProgress>,
        history: List<StateTransitionRecord>,
        updatedAt: Long
    ): Boolean {
        val store = persistence ?: return true
        return store.save(
            TaskStateSnapshot(
                taskId = taskId,
                goal = goal,
                state = state,
                actions = actions.toList(),
                history = history.takeLast(MAX_TASK_HISTORY),
                createdAtEpochMs = createdAtEpochMs,
                updatedAtEpochMs = updatedAt
            )
        )
    }


    companion object {
        private const val MAX_TASK_HISTORY = 100
        private const val MAX_ACTION_HISTORY = 50
        private const val MAX_REASON = 2_000
        private val RESTART_INTERRUPTED_STATES = setOf(
            ExecutionState.VALIDATING,
            ExecutionState.READY,
            ExecutionState.RUNNING,
            ExecutionState.OBSERVING,
            ExecutionState.WAITING_USER,
            ExecutionState.RETRYABLE_FAILURE
        )

        /**
         * Valid Task-level state transitions.
         * CREATED → VALIDATING → READY → RUNNING → OBSERVING → VERIFIED → SUCCEEDED
         * Plus WAITING_USER, RETRYABLE_FAILURE, FAILED, CANCELLED, RECOVERY_REQUIRED.
         */
        val VALID_TASK_TRANSITIONS: Map<ExecutionState, Set<ExecutionState>> = mapOf(
            ExecutionState.CREATED to setOf(ExecutionState.VALIDATING, ExecutionState.CANCELLED),
            ExecutionState.VALIDATING to setOf(ExecutionState.READY, ExecutionState.FAILED, ExecutionState.CANCELLED),
            ExecutionState.READY to setOf(ExecutionState.RUNNING, ExecutionState.CANCELLED),
            ExecutionState.RUNNING to setOf(
                ExecutionState.OBSERVING,
                ExecutionState.VERIFIED,
                ExecutionState.SUCCEEDED,
                ExecutionState.WAITING_USER,
                ExecutionState.RETRYABLE_FAILURE,
                ExecutionState.FAILED,
                ExecutionState.CANCELLED,
                ExecutionState.RECOVERY_REQUIRED
            ),
            ExecutionState.OBSERVING to setOf(
                ExecutionState.VERIFIED,
                ExecutionState.RUNNING,
                ExecutionState.WAITING_USER,
                ExecutionState.RETRYABLE_FAILURE,
                ExecutionState.FAILED,
                ExecutionState.CANCELLED,
                ExecutionState.RECOVERY_REQUIRED
            ),
            ExecutionState.VERIFIED to setOf(
                ExecutionState.SUCCEEDED,
                ExecutionState.RUNNING, // Next action in multi-action plan
                ExecutionState.WAITING_USER,
                ExecutionState.CANCELLED
            ),
            ExecutionState.WAITING_USER to setOf(
                ExecutionState.RUNNING,
                ExecutionState.OBSERVING,
                ExecutionState.CANCELLED,
                ExecutionState.FAILED,
                ExecutionState.RECOVERY_REQUIRED
            ),
            ExecutionState.RETRYABLE_FAILURE to setOf(
                ExecutionState.RUNNING,
                ExecutionState.READY,
                ExecutionState.FAILED,
                ExecutionState.CANCELLED
            ),
            ExecutionState.RECOVERY_REQUIRED to setOf(
                ExecutionState.READY,
                ExecutionState.RUNNING,
                ExecutionState.FAILED,
                ExecutionState.CANCELLED
            ),
            // Terminal states have no valid subsequent transitions
            ExecutionState.SUCCEEDED to emptySet(),
            ExecutionState.FAILED to emptySet(),
            ExecutionState.CANCELLED to emptySet()
        )

        /**
         * Valid Action-level state transitions.
         */
        val VALID_ACTION_TRANSITIONS: Map<ExecutionState, Set<ExecutionState>> = mapOf(
            ExecutionState.CREATED to setOf(ExecutionState.VALIDATING, ExecutionState.READY, ExecutionState.RUNNING, ExecutionState.FAILED, ExecutionState.RECOVERY_REQUIRED, ExecutionState.CANCELLED),
            ExecutionState.VALIDATING to setOf(ExecutionState.READY, ExecutionState.FAILED, ExecutionState.CANCELLED),
            ExecutionState.READY to setOf(ExecutionState.RUNNING, ExecutionState.CANCELLED),
            ExecutionState.RUNNING to setOf(
                ExecutionState.OBSERVING,
                ExecutionState.WAITING_USER,
                ExecutionState.VERIFIED,
                ExecutionState.SUCCEEDED,
                ExecutionState.RETRYABLE_FAILURE,
                ExecutionState.FAILED,
                ExecutionState.CANCELLED,
                ExecutionState.RECOVERY_REQUIRED
            ),
            ExecutionState.OBSERVING to setOf(
                ExecutionState.VERIFIED,
                ExecutionState.WAITING_USER,
                ExecutionState.RETRYABLE_FAILURE,
                ExecutionState.FAILED,
                ExecutionState.CANCELLED,
                ExecutionState.RECOVERY_REQUIRED
            ),
            ExecutionState.WAITING_USER to setOf(
                ExecutionState.RUNNING,
                ExecutionState.OBSERVING,
                ExecutionState.VERIFIED,
                ExecutionState.FAILED,
                ExecutionState.CANCELLED
            ),
            ExecutionState.RETRYABLE_FAILURE to setOf(ExecutionState.READY, ExecutionState.RUNNING, ExecutionState.FAILED, ExecutionState.CANCELLED),
            ExecutionState.VERIFIED to setOf(ExecutionState.SUCCEEDED),
            ExecutionState.RECOVERY_REQUIRED to setOf(ExecutionState.READY, ExecutionState.FAILED, ExecutionState.CANCELLED),
            ExecutionState.SUCCEEDED to emptySet(),
            ExecutionState.FAILED to setOf(ExecutionState.READY, ExecutionState.CANCELLED),
            ExecutionState.CANCELLED to emptySet()
        )
    }
}
