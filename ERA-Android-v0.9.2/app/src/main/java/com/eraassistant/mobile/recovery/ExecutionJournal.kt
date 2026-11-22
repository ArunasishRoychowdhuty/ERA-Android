package com.eraassistant.mobile.recovery

import com.eraassistant.mobile.capability.ExecutionError
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class JournalEntryState {
    PENDING,
    IN_PROGRESS,
    WAITING_USER,
    COMPLETED,
    FAILED,
    INTERRUPTED_RECOVERY_REQUIRED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED
}

data class JournalEntry(
    val journalId: String,
    val taskId: String,
    val actionId: String,
    val idempotencyKey: String,
    val actionFingerprint: String,
    val actionName: String,
    val state: JournalEntryState,
    val isConsequential: Boolean = false,
    val retryCount: Int = 0,
    val checkpointData: String? = null,
    val failureReason: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = createdAtEpochMs
)

sealed interface JournalClaimResult {
    data object Acquired : JournalClaimResult
    data class Existing(val entry: JournalEntry) : JournalClaimResult
    data class Conflict(val message: String) : JournalClaimResult
    data class RecoveryRequired(val entry: JournalEntry, val reason: String) : JournalClaimResult
    data class PersistenceFailure(val reason: String) : JournalClaimResult
}

interface ExecutionJournalPersistence {
    fun loadAll(): List<JournalEntry>
    /** Must return true only after the record has been durably written. */
    fun save(entry: JournalEntry): Boolean
    fun remove(journalId: String)
    fun clear()
}

class InMemoryExecutionJournalPersistence : ExecutionJournalPersistence {
    private val entries = ConcurrentHashMap<String, JournalEntry>()
    override fun loadAll(): List<JournalEntry> = entries.values.toList()
    override fun save(entry: JournalEntry): Boolean {
        entries[entry.journalId] = entry
        return true
    }
    override fun remove(journalId: String) { entries.remove(journalId) }
    override fun clear() { entries.clear() }
}

data class RetryPolicy(
    val maxRetries: Int = 2,
    val initialBackoffMs: Long = 300L,
    val maxBackoffMs: Long = 3000L,
    val backoffMultiplier: Double = 1.5
) {
    init {
        require(maxRetries in 0..5)
        require(initialBackoffMs >= 0L && maxBackoffMs >= initialBackoffMs)
        require(backoffMultiplier >= 1.0 && backoffMultiplier <= 5.0)
    }

    fun computeBackoff(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val factor = Math.pow(backoffMultiplier, (attempt - 1).toDouble())
        return (initialBackoffMs.toDouble() * factor).toLong().coerceAtMost(maxBackoffMs)
    }

    fun isRetryable(error: ExecutionError, currentAttempt: Int): Boolean =
        error.retryable && currentAttempt < maxRetries
}

/**
 * Durable action journal. A dispatch claim is persisted BEFORE the adapter can run.
 * Persistence corruption/failure is fail-closed. FAILED/uncertain idempotency keys are
 * never silently recycled; a new reviewed attempt needs a new key after target inspection.
 */
class ExecutionJournal(
    private val persistence: ExecutionJournalPersistence = InMemoryExecutionJournalPersistence(),
    val defaultRetryPolicy: RetryPolicy = RetryPolicy()
) {
    private val memoryCache = ConcurrentHashMap<String, JournalEntry>()
    @Volatile private var storageFailure: String? = null

    init {
        try {
            persistence.loadAll().forEach { entry ->
                memoryCache[entry.journalId] = entry
            }
        } catch (error: Throwable) {
            storageFailure = "Execution journal could not be loaded: ${safeReason(error)}"
        }
    }

    fun isAvailable(): Boolean = storageFailure == null
    fun unavailableReason(): String? = storageFailure

    @Synchronized
    fun claim(
        taskId: String,
        actionId: String,
        idempotencyKey: String,
        actionFingerprint: String,
        actionName: String,
        isConsequential: Boolean = false
    ): JournalClaimResult {
        storageFailure?.let { return JournalClaimResult.PersistenceFailure(it) }
        if (taskId.isBlank() || actionId.isBlank() || idempotencyKey.isBlank() ||
            actionFingerprint.isBlank() || actionName.isBlank()) {
            return JournalClaimResult.Conflict("Journal claim contains an empty identity field.")
        }
        if (listOf(taskId, actionId, idempotencyKey, actionFingerprint, actionName).any { it.length > 256 }) {
            return JournalClaimResult.Conflict("Journal claim identity exceeds its supported length.")
        }

        val existing = memoryCache.values.firstOrNull { it.idempotencyKey == idempotencyKey }
        if (existing != null) {
            if (existing.actionFingerprint != actionFingerprint) {
                return JournalClaimResult.Conflict("Idempotency key was already bound to a different action.")
            }
            return when (existing.state) {
                JournalEntryState.COMPLETED -> JournalClaimResult.Existing(existing)
                JournalEntryState.FAILED,
                JournalEntryState.IN_PROGRESS,
                JournalEntryState.WAITING_USER,
                JournalEntryState.PENDING,
                JournalEntryState.INTERRUPTED_RECOVERY_REQUIRED -> JournalClaimResult.RecoveryRequired(
                    existing,
                    "This idempotency key has already been attempted (${existing.state}). Inspect the target app and create a new reviewed attempt; automatic replay is blocked."
                )
            }
        }

        val sameActionId = memoryCache.values.firstOrNull { it.actionId == actionId }
        if (sameActionId != null) {
            return JournalClaimResult.Conflict("Action ID is already present in the execution journal.")
        }

        val now = System.currentTimeMillis()
        val entry = JournalEntry(
            journalId = "j_${UUID.randomUUID()}",
            taskId = taskId,
            actionId = actionId,
            idempotencyKey = idempotencyKey,
            actionFingerprint = actionFingerprint,
            actionName = actionName,
            state = JournalEntryState.IN_PROGRESS,
            isConsequential = isConsequential,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        val saved = runCatching { persistence.save(entry) }.getOrElse { error ->
            storageFailure = "Execution journal write failed: ${safeReason(error)}"
            return JournalClaimResult.PersistenceFailure(storageFailure!!)
        }
        if (!saved) {
            storageFailure = "Execution journal did not confirm durable storage. No action may be dispatched until journal health is restored."
            return JournalClaimResult.PersistenceFailure(storageFailure!!)
        }
        memoryCache[entry.journalId] = entry
        return JournalClaimResult.Acquired
    }

    @Synchronized
    fun recordCheckpoint(journalId: String, checkpointData: String): Boolean {
        if (storageFailure != null) return false
        if (checkpointData.length > MAX_CHECKPOINT_CHARS) return false
        return update(journalId) { it.copy(checkpointData = checkpointData, updatedAtEpochMs = System.currentTimeMillis()) }
    }

    @Synchronized
    fun recordRetryAttempt(journalId: String, reason: String): Boolean =
        update(journalId) {
            it.copy(
                retryCount = (it.retryCount + 1).coerceAtMost(10),
                failureReason = reason.take(MAX_REASON_CHARS),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }

    @Synchronized
    fun markWaitingForUser(journalId: String, reason: String): Boolean =
        update(journalId) {
            it.copy(
                state = JournalEntryState.WAITING_USER,
                failureReason = reason.take(MAX_REASON_CHARS),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }

    @Synchronized
    fun markCompleted(journalId: String): Boolean =
        update(journalId) {
            it.copy(
                state = JournalEntryState.COMPLETED,
                failureReason = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }

    @Synchronized
    fun markFailed(journalId: String, failureReason: String): Boolean =
        update(journalId) {
            it.copy(
                state = JournalEntryState.FAILED,
                failureReason = failureReason.take(MAX_REASON_CHARS),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }

    @Synchronized
    fun markRecoveryRequired(journalId: String, reason: String): Boolean =
        update(journalId) {
            it.copy(
                state = JournalEntryState.INTERRUPTED_RECOVERY_REQUIRED,
                failureReason = reason.take(MAX_REASON_CHARS),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }

    /** Reconciles in-flight and waiting-user records whenever the runtime is recreated. */
    @Synchronized
    fun reconcileAfterRestart(): List<JournalEntry> {
        if (storageFailure != null) return emptyList()
        val recovered = mutableListOf<JournalEntry>()
        val candidates = memoryCache.values.filter {
            it.state in setOf(JournalEntryState.IN_PROGRESS, JournalEntryState.WAITING_USER, JournalEntryState.PENDING)
        }
        for (entry in candidates) {
            val updated = entry.copy(
                state = JournalEntryState.INTERRUPTED_RECOVERY_REQUIRED,
                failureReason = "Runtime restarted while action was unresolved. Inspect target app before any new attempt.",
                updatedAtEpochMs = System.currentTimeMillis()
            )
            val saved = runCatching { persistence.save(updated) }.getOrDefault(false)
            if (!saved) {
                storageFailure = "Could not persist restart reconciliation for journal entry ${entry.journalId}."
                break
            }
            memoryCache[entry.journalId] = updated
            recovered += updated
        }
        return recovered
    }

    @Synchronized
    fun findByActionId(actionId: String): JournalEntry? =
        memoryCache.values.firstOrNull { it.actionId == actionId }

    @Synchronized
    fun allEntries(): List<JournalEntry> = memoryCache.values.sortedBy { it.createdAtEpochMs }

    private fun update(journalId: String, transform: (JournalEntry) -> JournalEntry): Boolean {
        if (storageFailure != null) return false
        val existing = memoryCache[journalId] ?: return false
        val updated = transform(existing)
        val saved = runCatching { persistence.save(updated) }.getOrElse { error ->
            storageFailure = "Execution journal update failed: ${safeReason(error)}"
            return false
        }
        if (!saved) {
            storageFailure = "Execution journal update was not durably committed."
            return false
        }
        memoryCache[journalId] = updated
        return true
    }

    private fun safeReason(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "unknown").take(180)

    companion object {
        private const val MAX_CHECKPOINT_CHARS = 2_000
        private const val MAX_REASON_CHARS = 500

        fun computeFingerprint(actionName: String, params: Map<String, Any?>): String {
            fun canonical(value: Any?): String = when (value) {
                null -> "null"
                is Map<*, *> -> value.entries
                    .sortedBy { it.key?.toString().orEmpty() }
                    .joinToString(prefix = "{", postfix = "}") { "${it.key}:${canonical(it.value)}" }
                is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
                is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
                else -> value.toString()
            }
            val content = buildString {
                append(actionName).append(':')
                params.toSortedMap().forEach { (key, value) ->
                    append(key.length).append(':').append(key).append('=')
                    append(canonical(value).length).append(':').append(canonical(value)).append(';')
                }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
