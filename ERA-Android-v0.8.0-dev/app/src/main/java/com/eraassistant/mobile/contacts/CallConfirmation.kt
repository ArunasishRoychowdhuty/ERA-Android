package com.eraassistant.mobile.contacts

/** UI confirmation payload created only after the user selects a contact in Android's picker. */
data class PendingCallConfirmation(
    val contactName: String,
    val displayNumber: String,
    val dialNumber: String,
    val createdAtEpochMs: Long
)

object CallConfirmationPolicy {
    const val MAX_AGE_MS = 120_000L
    fun create(name: String?, number: String?, nowEpochMs: Long): PendingCallConfirmation? {
        val normalized = ContactMatchEngine.normalizeDigits(number.orEmpty())
        val digits = normalized.filter(Char::isDigit)
        val cleanNumber = (if (normalized.trimStart().startsWith("+")) "+" else "") + digits
        val digitCount = digits.length
        if (cleanNumber.isBlank() || digitCount !in 3..20) return null
        return PendingCallConfirmation(
            contactName = name?.trim()?.takeIf(String::isNotBlank) ?: "Selected contact",
            displayNumber = number.orEmpty().trim().ifBlank { cleanNumber },
            dialNumber = cleanNumber,
            createdAtEpochMs = nowEpochMs
        )
    }
    fun canConfirm(pending: PendingCallConfirmation, nowEpochMs: Long): Boolean {
        val age = nowEpochMs - pending.createdAtEpochMs
        return age in 0..MAX_AGE_MS && pending.dialNumber.count(Char::isDigit) in 3..20
    }
}
