package com.eraassistant.mobile.screen

/**
 * Last-mile policy for text entry into third-party UI.
 *
 * This policy uses only visible field metadata and the pending payload; it never needs
 * to read or persist the value already present in an editable field.
 */
object SensitiveInputPolicy {
    private val englishFieldMarkers = listOf(
        "password", "passcode", "one time password", "one-time password", "otp",
        "verification code", "security code", "pin", "cvv", "cvc",
        "card number", "credit card", "debit card", "bank account",
        "account number", "upi", "payment", "wallet", "api key",
        "access token", "refresh token", "bearer token", "authorization",
        "secret key", "recovery code"
    )

    private val regionalFieldMarkers = listOf(
        "পাসওয়ার্ড", "পাসওয়ার্ড", "ওটিপি", "ও টি পি", "পিন", "যাচাইকরণ কোড",
        "নিরাপত্তা কোড", "কার্ড নম্বর", "ক্রেডিট কার্ড", "ডেবিট কার্ড",
        "ব্যাংক অ্যাকাউন্ট", "ব্যাংক একাউন্ট", "পেমেন্ট", "টাকা পরিশোধ",
        "ইউপিআই", "গোপন কোড", "অ্যাক্সেস টোকেন"
    )

    private val credentialPayloadPatterns = listOf(
        Regex("(?i)\\b(?:password|passcode|otp|one[- ]time code|pin|cvv|cvc)\\s*[:=]\\s*\\S+"),
        Regex("(?i)\\b(?:bearer|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|authorization)\\s*[:=]?\\s*\\S+"),
        Regex("(?i)\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{16,}|xox[baprs]-[A-Za-z0-9-]{12,})\\b"),
        // Raw card-number-like sequences and common UPI virtual-address suffixes are
        // rejected even when the prompt omits a credential label.
        Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)"),
        Regex("(?i)\\b[a-z0-9._-]{2,}@(oksbi|okaxis|okhdfcbank|okicici|ybl|ibl|axl|paytm|upi|apl|fbl|kotak)\\b")
    )

    fun isSensitiveField(
        label: String?,
        resourceId: String? = null,
        hint: String? = null,
        role: String? = null
    ): Boolean {
        val raw = listOfNotNull(label, resourceId, hint, role)
            .joinToString(" ")
            .lowercase()
            .replace(Regex("[._:/-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (raw.isBlank()) return false

        if (regionalFieldMarkers.any(raw::contains)) return true
        if (englishFieldMarkers.any { marker ->
                if (marker in setOf("otp", "pin", "upi", "cvv", "cvc")) {
                    Regex("(?i)(^|[^a-z0-9])${Regex.escape(marker)}([^a-z0-9]|$)").containsMatchIn(raw)
                } else raw.contains(marker)
            }) return true
        return false
    }

    fun containsCredentialLikePayload(payload: String): Boolean =
        payload.isNotBlank() && credentialPayloadPatterns.any { it.containsMatchIn(payload) }

    fun isSensitiveField(element: UiElement): Boolean = isSensitiveField(
        label = element.text ?: element.contentDescription,
        resourceId = element.resourceId,
        hint = element.hintText,
        role = element.role
    )

    /**
     * Returns a safe, user-facing rejection reason, or null when text entry may proceed
     * to the separate approval and final-observation gates.
     */
    fun rejectionReason(
        label: String?,
        resourceId: String?,
        hint: String?,
        role: String?,
        payload: String
    ): String? {
        if (isSensitiveField(label, resourceId, hint, role)) {
            return "Sensitive credential, payment or verification fields are blocked. ERA did not store or enter text."
        }
        if (credentialPayloadPatterns.any { it.containsMatchIn(payload) }) {
            return "The proposed text resembles a credential or access token. ERA did not store or enter it."
        }
        return null
    }

    fun rejectionReason(element: UiElement, payload: String): String? = rejectionReason(
        label = element.text ?: element.contentDescription,
        resourceId = element.resourceId,
        hint = element.hintText,
        role = element.role,
        payload = payload
    )
}
