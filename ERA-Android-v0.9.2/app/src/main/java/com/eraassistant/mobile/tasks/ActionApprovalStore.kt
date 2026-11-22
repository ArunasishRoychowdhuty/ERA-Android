package com.eraassistant.mobile.tasks

import com.eraassistant.mobile.capability.StructuredActionRequest
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral, exact-action, one-time approval receipts.
 *
 * Receipts are intentionally process-local: after process death the user must review and
 * approve again against a fresh observation. No receipt is stored with task history.
 */
class ActionApprovalStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 60_000L
) {
    private data class Binding(
        val stepId: String,
        val fingerprint: String,
        val observationId: String,
        val approvedAtEpochMs: Long
    )

    private val receipts = ConcurrentHashMap<String, Binding>()

    init {
        require(maxAgeMs in 5_000L..120_000L)
    }

    fun issue(
        stepId: String,
        request: StructuredActionRequest,
        observationId: String
    ): String {
        require(stepId.isNotBlank() && observationId.isNotBlank())
        pruneExpired()
        val token = "era_approval_${UUID.randomUUID()}"
        receipts[token] = Binding(
            stepId = stepId,
            fingerprint = fingerprint(stepId, request, observationId),
            observationId = observationId,
            approvedAtEpochMs = clock()
        )
        return token
    }

    /** Atomically consumes the receipt. It cannot be replayed or transferred to another action. */
    fun consume(
        receiptId: String?,
        stepId: String,
        request: StructuredActionRequest,
        observationId: String?
    ): Boolean {
        if (receiptId.isNullOrBlank() || observationId.isNullOrBlank()) return false
        val binding = receipts.remove(receiptId) ?: return false
        val age = clock() - binding.approvedAtEpochMs
        return binding.stepId == stepId &&
            binding.observationId == observationId &&
            age in 0..maxAgeMs &&
            binding.fingerprint == fingerprint(stepId, request, observationId)
    }

    fun clear() = receipts.clear()

    private fun pruneExpired() {
        val now = clock()
        receipts.entries.removeIf { now - it.value.approvedAtEpochMs !in 0..maxAgeMs }
    }

    private fun fingerprint(
        stepId: String,
        request: StructuredActionRequest,
        observationId: String
    ): String {
        val canonical = buildString {
            appendField(stepId)
            appendField(request.actionId)
            appendField(request.idempotencyKey)
            appendField(request.capabilityId.value)
            appendField(request.actionName)
            appendField(observationId)
            appendField(request.targetObservationId.orEmpty())
            appendField(request.riskLevel.name)
            appendField(request.requiresUserApproval.toString())
            request.parameters.toSortedMap().forEach { (key, value) ->
                appendField(key)
                appendField(canonicalValue(value))
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value).append('|')
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries
            .map { (key, item) -> key.toString() to canonicalValue(item) }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}") { "${it.first}=${it.second}" }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalValue(it) }
        else -> "${value::class.java.name}:$value"
    }
}
