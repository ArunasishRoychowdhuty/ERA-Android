package com.eraassistant.mobile.memory

import org.json.JSONArray
import org.json.JSONObject

data class UserProfile(
    val displayName: String? = null,
    val preferredName: String? = null,
    val language: String? = null,
    val timezone: String? = null,
    val communicationStyle: String? = null,
    val interests: List<String> = emptyList(),
    val accessibilityPreference: String? = null,
    val updatedAtEpochMs: Long
)

/**
 * Explicit-consent profile bundle. Only non-sensitive preference fields are accepted;
 * direct identifiers and credential-like values are rejected by the shared domain-memory policy.
 */
class UserProfileService(
    private val memory: DomainMemoryService,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun save(
        profile: UserProfile,
        explicitUserConsent: Boolean,
        retentionDays: Int = 365
    ): DomainMemoryResult {
        if (!explicitUserConsent) {
            return DomainMemoryResult(
                DomainMemoryDecision.CONSENT_REQUIRED,
                "Explicit user consent is required before saving a profile."
            )
        }
        val cleaned = clean(profile) ?: return DomainMemoryResult(
            DomainMemoryDecision.INVALID_INPUT,
            "Profile fields or interests exceed their allowed format or length."
        )
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("displayName", cleaned.displayName ?: JSONObject.NULL)
            put("preferredName", cleaned.preferredName ?: JSONObject.NULL)
            put("language", cleaned.language ?: JSONObject.NULL)
            put("timezone", cleaned.timezone ?: JSONObject.NULL)
            put("communicationStyle", cleaned.communicationStyle ?: JSONObject.NULL)
            put("interests", JSONArray(cleaned.interests))
            put("accessibilityPreference", cleaned.accessibilityPreference ?: JSONObject.NULL)
            put("updatedAtBase36", java.lang.Long.toString(clock(), 36))
        }.toString()
        return memory.put(
            DomainMemoryWrite(
                area = DomainMemoryArea.USER_PROFILE,
                key = PROFILE_BUNDLE_KEY,
                value = json,
                sensitivity = DomainMemorySensitivity.PERSONAL,
                explicitUserConsent = true,
                retentionDays = retentionDays
            )
        )
    }

    fun get(): UserProfile? {
        val raw = memory.get(DomainMemoryArea.USER_PROFILE, PROFILE_BUNDLE_KEY)?.value ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (obj.optInt("schemaVersion") != 1) return null
        val interestsArray = obj.optJSONArray("interests") ?: JSONArray()
        val interests = (0 until interestsArray.length())
            .mapNotNull { interestsArray.optString(it).takeIf(String::isNotBlank) }
            .take(MAX_INTERESTS)
        val updatedAt = obj.optString("updatedAtBase36").toLongOrNull(36) ?: return null
        if (updatedAt <= 0L) return null
        return UserProfile(
            displayName = obj.optNullableString("displayName"),
            preferredName = obj.optNullableString("preferredName"),
            language = obj.optNullableString("language"),
            timezone = obj.optNullableString("timezone"),
            communicationStyle = obj.optNullableString("communicationStyle"),
            interests = interests,
            accessibilityPreference = obj.optNullableString("accessibilityPreference"),
            updatedAtEpochMs = updatedAt
        )
    }

    fun delete(): Boolean = memory.delete(DomainMemoryArea.USER_PROFILE, PROFILE_BUNDLE_KEY)

    private fun clean(profile: UserProfile): UserProfile? {
        return try {
            fun optional(value: String?, max: Int): String? {
                val clean = value?.trim()?.takeIf(String::isNotBlank) ?: return null
                require(clean.length <= max && clean.none { it.code < 0x20 }) {
                    "Profile field is invalid."
                }
                return clean
            }

            val display = optional(profile.displayName, MAX_SHORT_FIELD)
            val preferred = optional(profile.preferredName, MAX_SHORT_FIELD)
            val language = optional(profile.language, MAX_LANGUAGE)
            val timezone = optional(profile.timezone, MAX_TIMEZONE)
            val style = optional(profile.communicationStyle, MAX_LONG_FIELD)
            val accessibility = optional(profile.accessibilityPreference, MAX_LONG_FIELD)

            require(profile.interests.size <= MAX_INTERESTS) { "Too many interests." }
            val interests = profile.interests.map { it.trim() }
            require(interests.all {
                it.isNotBlank() && it.length <= MAX_INTEREST_LENGTH && it.none { ch -> ch.code < 0x20 }
            }) { "An interest is invalid." }
            require(listOf(display, preferred, language, timezone, style, accessibility).any { it != null } ||
                interests.isNotEmpty()) { "Profile is empty." }

            UserProfile(
                displayName = display,
                preferredName = preferred,
                language = language,
                timezone = timezone,
                communicationStyle = style,
                interests = interests.distinctBy(String::lowercase),
                accessibilityPreference = accessibility,
                updatedAtEpochMs = clock()
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    companion object {
        private const val PROFILE_BUNDLE_KEY = "profile_bundle"
        private const val MAX_SHORT_FIELD = 60
        private const val MAX_LANGUAGE = 16
        private const val MAX_TIMEZONE = 48
        private const val MAX_LONG_FIELD = 120
        private const val MAX_INTERESTS = 8
        private const val MAX_INTEREST_LENGTH = 48
    }
}
