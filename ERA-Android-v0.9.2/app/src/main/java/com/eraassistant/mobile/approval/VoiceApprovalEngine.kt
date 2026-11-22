package com.eraassistant.mobile.approval

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Process-local voice approval gate for one exact, already-described action.
 * A spoken approval is valid only for a matching binding and a live pending request.
 * Approval state is deliberately not restored after process death.
 */
class VoiceApprovalEngine(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private data class Pending(val prompt: VoiceApprovalPrompt, val bindingHash: String)
    private var pending: Pending? = null

    @Synchronized
    fun request(
        actionId: String,
        actionSummary: String,
        targetSummary: String,
        binding: String,
        ttlMs: Long = DEFAULT_TTL_MS
    ): VoiceApprovalPrompt {
        require(actionId.isNotBlank() && actionId.length <= 96)
        require(actionSummary.isNotBlank() && actionSummary.length <= 180)
        require(targetSummary.length <= 120)
        require(binding.isNotBlank() && binding.length <= 8_192)
        require(ttlMs in MIN_TTL_MS..MAX_TTL_MS)

        val now = clock()
        val prompt = VoiceApprovalPrompt(
            requestId = idFactory(),
            actionId = actionId,
            actionSummary = actionSummary.trim(),
            targetSummary = targetSummary.trim(),
            createdAtEpochMs = now,
            expiresAtEpochMs = now + ttlMs
        )
        pending = Pending(prompt, fingerprint(binding))
        return prompt
    }

    /**
     * Consumes the request on approval, denial, expiry, binding change, or unrelated
     * speech. Unrelated speech cannot leave an old approval armed.
     */
    @Synchronized
    fun consumeSpokenCommand(transcript: String, currentBinding: String?): VoiceApprovalResult {
        val active = pending ?: return VoiceApprovalResult.NoPending
        val now = clock()
        if (now < active.prompt.createdAtEpochMs || now > active.prompt.expiresAtEpochMs) {
            pending = null
            return VoiceApprovalResult.Expired(active.prompt.requestId)
        }
        if (currentBinding.isNullOrBlank() || fingerprint(currentBinding) != active.bindingHash) {
            pending = null
            return VoiceApprovalResult.BindingChanged(active.prompt.requestId)
        }
        return when (normalize(transcript)) {
            in APPROVE_PHRASES -> {
                pending = null
                VoiceApprovalResult.Approved(active.prompt.requestId)
            }
            in DENY_PHRASES -> {
                pending = null
                VoiceApprovalResult.Denied(active.prompt.requestId)
            }
            else -> {
                pending = null
                VoiceApprovalResult.Unrecognized(active.prompt.requestId)
            }
        }
    }

    @Synchronized
    fun activePrompt(): VoiceApprovalPrompt? {
        val active = pending ?: return null
        val now = clock()
        if (now < active.prompt.createdAtEpochMs || now > active.prompt.expiresAtEpochMs) {
            pending = null
            return null
        }
        return active.prompt
    }

    @Synchronized
    fun invalidate(requestId: String? = null): Boolean {
        val active = pending ?: return false
        if (requestId != null && active.prompt.requestId != requestId) return false
        pending = null
        return true
    }

    @Synchronized
    fun clear() {
        pending = null
    }

    fun isPending(): Boolean = activePrompt() != null

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun normalize(value: String): String = normalizePhrase(value)

    companion object {
        const val DEFAULT_TTL_MS = 30_000L
        const val MIN_TTL_MS = 3_000L
        const val MAX_TTL_MS = 120_000L

        private val APPROVE_PHRASES = setOf("approved", "approve", "অনুমোদিত", "অনুমোদন করছি")
        private val DENY_PHRASES = setOf("cancel", "cancelled", "deny", "denied", "বাতিল", "না")

        private fun normalizePhrase(value: String): String =
            value.trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[.!?,;:।]+$"), "")
                .trim()
                .replace(Regex("\\s+"), " ")

        fun isApprovalOrCancellationPhrase(transcript: String): Boolean {
            val normalized = normalizePhrase(transcript)
            return normalized in APPROVE_PHRASES || normalized in DENY_PHRASES
        }
    }
}

data class VoiceApprovalPrompt(
    val requestId: String,
    val actionId: String,
    val actionSummary: String,
    val targetSummary: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

sealed interface VoiceApprovalResult {
    data class Approved(val requestId: String) : VoiceApprovalResult
    data class Denied(val requestId: String) : VoiceApprovalResult
    data class Expired(val requestId: String) : VoiceApprovalResult
    data class BindingChanged(val requestId: String) : VoiceApprovalResult
    data class Unrecognized(val requestId: String) : VoiceApprovalResult
    data object NoPending : VoiceApprovalResult
}
