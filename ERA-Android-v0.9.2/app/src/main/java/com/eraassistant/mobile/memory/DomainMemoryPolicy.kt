package com.eraassistant.mobile.memory

import org.json.JSONObject

enum class DomainMemoryArea { USER_PROFILE, HOME_MANAGEMENT, HOUSEHOLD_ROUTINES, PEOPLE_CONTEXT }
enum class DomainMemorySensitivity { GENERAL, PERSONAL, SENSITIVE, SECRET }

data class DomainMemoryWrite(
    val area: DomainMemoryArea,
    val key: String,
    val value: String,
    val sensitivity: DomainMemorySensitivity,
    val explicitUserConsent: Boolean,
    val retentionDays: Int = 180
)

enum class DomainMemoryDecision { STORED, CONSENT_REQUIRED, FIELD_NOT_ALLOWED, SENSITIVE_DATA_BLOCKED, INVALID_INPUT }
data class DomainMemoryResult(val decision: DomainMemoryDecision, val reason: String)
data class DomainMemoryEntry(
    val area: DomainMemoryArea, val key: String, val value: String,
    val createdAtEpochMs: Long, val expiresAtEpochMs: Long
)

/**
 * Consent-gated domain memory. Credentials, identity numbers, contact coordinates and
 * precise locations are excluded. Uses the existing app-private SharedPreferences store;
 * it is bounded and scrubbed but is not an encrypted vault.
 */
class DomainMemoryService(
    private val memory: PersistentAgentMemory,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun put(request: DomainMemoryWrite): DomainMemoryResult {
        val key = request.key.trim().lowercase()
        if (key.isBlank() || key.length > 80 || request.value.isBlank() ||
            request.value.length > MAX_DOMAIN_VALUE_LENGTH || request.retentionDays !in 1..365) {
            return DomainMemoryResult(DomainMemoryDecision.INVALID_INPUT, "Key, value or retention period is invalid.")
        }
        if (!isAllowedField(request.area, key)) {
            return DomainMemoryResult(DomainMemoryDecision.FIELD_NOT_ALLOWED, "This field is outside the safe domain-memory allowlist.")
        }
        if (request.sensitivity.ordinal >= DomainMemorySensitivity.SENSITIVE.ordinal ||
            PrivacyFilter.isSensitiveKey(key) || containsSecretLikeValue(request.value)) {
            return DomainMemoryResult(DomainMemoryDecision.SENSITIVE_DATA_BLOCKED, "Sensitive data and secrets are not accepted into domain memory.")
        }
        if (!request.explicitUserConsent) {
            return DomainMemoryResult(DomainMemoryDecision.CONSENT_REQUIRED, "Explicit consent is required before storing profile or household information.")
        }
        val safe = PrivacyFilter.sanitizeText(request.value).trim()
        if (safe.isBlank() || safe.contains("[REDACTED")) {
            return DomainMemoryResult(DomainMemoryDecision.SENSITIVE_DATA_BLOCKED, "Privacy filtering removed or redacted the supplied value.")
        }
        val now = clock()
        val expiry = now + request.retentionDays * DAY_MS
        val payload = JSONObject().apply {
            put("schemaVersion", 1); put("area", request.area.name); put("key", key)
            put("value", safe); put("createdAtEpochMs", now); put("expiresAtEpochMs", expiry)
        }.toString()
        val stored = memory.put(category(request.area), key, payload, userApproved = true)
        return if (stored) DomainMemoryResult(
            DomainMemoryDecision.STORED, "Stored with explicit consent and a bounded retention period."
        ) else DomainMemoryResult(
            DomainMemoryDecision.SENSITIVE_DATA_BLOCKED, "The underlying memory store rejected the record."
        )
    }

    fun get(area: DomainMemoryArea, key: String): DomainMemoryEntry? {
        val normalized = key.trim().lowercase()
        val raw = memory.get(category(area), normalized) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val expires = json.optLong("expiresAtEpochMs", 0L)
        if (expires <= clock()) {
            memory.delete(category(area), normalized)
            return null
        }
        if (json.optString("area") != area.name || json.optString("key") != normalized) return null
        return DomainMemoryEntry(area, normalized, json.optString("value"),
            json.optLong("createdAtEpochMs"), expires)
    }

    fun delete(area: DomainMemoryArea, key: String): Boolean = memory.delete(category(area), key.trim().lowercase())
    fun clear(area: DomainMemoryArea): Int = memory.clearCategory(category(area))

    private fun category(area: DomainMemoryArea): MemoryCategory = when (area) {
        DomainMemoryArea.USER_PROFILE -> MemoryCategory.USER_PROFILE
        DomainMemoryArea.HOME_MANAGEMENT -> MemoryCategory.HOME_MANAGEMENT
        DomainMemoryArea.HOUSEHOLD_ROUTINES -> MemoryCategory.HOUSEHOLD_ROUTINES
        DomainMemoryArea.PEOPLE_CONTEXT -> MemoryCategory.PEOPLE_CONTEXT
    }

    private fun isAllowedField(area: DomainMemoryArea, key: String): Boolean {
        if (key.containsAny("email", "phone", "mobile", "address", "location", "gps", "password", "token", "otp", "pin", "bank", "account", "aadhaar", "passport", "medical", "diagnosis")) return false
        val allowed = when (area) {
            DomainMemoryArea.USER_PROFILE -> setOf("display_name", "preferred_name", "language", "timezone", "communication_style", "interests", "accessibility_preference", "profile_bundle")
            DomainMemoryArea.HOME_MANAGEMENT -> setOf("household_task", "room_preference", "shopping_preference", "appliance_preference", "inventory_item", "maintenance_note")
            DomainMemoryArea.HOUSEHOLD_ROUTINES -> setOf("morning_routine", "evening_routine", "cleaning_schedule", "shopping_schedule", "reminder_preference")
            DomainMemoryArea.PEOPLE_CONTEXT -> setOf("relationship_label", "preferred_name", "communication_preference")
        }
        return key in allowed
    }

    private fun containsSecretLikeValue(value: String): Boolean {
        val patterns = listOf(
            Regex("(?i)\\b(?:password|passcode|otp|one[- ]time code|pin)\\s*[:=]?\\s*\\S+"),
            Regex("(?i)\\b(?:bearer|api[_ -]?key|access[_ -]?token)\\s*[:=]?\\s*\\S+"),
            Regex("\\b\\d{12,}\\b")
        )
        return patterns.any { it.containsMatchIn(value) } ||
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").containsMatchIn(value)
    }

    private fun String.containsAny(vararg terms: String): Boolean = terms.any(::contains)
    companion object {
        private const val DAY_MS = 86_400_000L
        private const val MAX_DOMAIN_VALUE_LENGTH = 3_500
    }
}
