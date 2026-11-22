package com.eraassistant.mobile.memory

/**
 * Strict privacy filter preventing secrets, OTPs, API keys, passwords, and tokens
 * from being written into durable memory or workflow logs.
 */
object PrivacyFilter {
    private val SENSITIVE_KEY_PATTERNS = listOf(
        "password", "passwd", "secret", "token", "apikey", "api_key",
        "auth", "bearer", "otp", "pin", "cvv", "credit_card", "payment_number",
        "private_key", "credential"
    )

    private val SENSITIVE_VALUE_REGEXES = listOf(
        Regex("(?i)\\b[a-z0-9_-]{32,}\\b"), // Potential API keys or long tokens
        Regex("(?i)\\b(?:otp|one[- ]time code|verification code|passcode|pin)\\s*(?:is|:|=)?\\s*\\d{4,8}\\b"),
        Regex("(?i)(?:card|credit(?:\\s+card)?|payment(?:\\s+number)?|account number)\\s*(?:is|:|=)?\\s*(?:\\d[ -]?){13,19}(?!\\d)") // Card/payment number in an explicit context
    )

    fun sanitizeText(input: String): String {
        var clean = input
        // Redact email addresses, bearer values and provider API keys first.
        clean = clean.replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,24}"), "[REDACTED_EMAIL]")
        clean = clean.replace(Regex("(?i)bearer\\s+[a-zA-Z0-9._~+/-]+"), "Bearer [REDACTED_TOKEN]")
        clean = clean.replace(Regex("(?i)(AIza[0-9A-Za-z_-]{20,}|sk-[a-zA-Z0-9_-]{20,})"), "[REDACTED_API_KEY]")
        // Apply the declared value patterns; contextual OTP/PIN matching avoids stripping years or ordinary IDs.
        SENSITIVE_VALUE_REGEXES.forEach { regex ->
            clean = clean.replace(regex, "[REDACTED_SENSITIVE_VALUE]")
        }
        return clean
    }

    fun isSensitiveKey(key: String): Boolean =
        SENSITIVE_KEY_PATTERNS.any { key.contains(it, ignoreCase = true) }

    fun sanitizeParameters(params: Map<String, Any?>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        params.forEach { (k, v) ->
            if (isSensitiveKey(k)) {
                result[k] = "[REDACTED_SENSITIVE]"
            } else {
                val str = v?.toString() ?: ""
                result[k] = sanitizeText(str)
            }
        }
        return result
    }
}
