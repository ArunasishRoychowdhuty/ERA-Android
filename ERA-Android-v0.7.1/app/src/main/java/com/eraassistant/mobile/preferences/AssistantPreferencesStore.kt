package com.eraassistant.mobile.preferences

import android.content.Context

class AssistantPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences("era_preferences", Context.MODE_PRIVATE)

    fun speechOutputEnabled(): Boolean = preferences.getBoolean(KEY_SPEECH_OUTPUT, true)
    fun setSpeechOutputEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SPEECH_OUTPUT, enabled).apply()
    }

    fun speechLanguage(): String = preferences.getString(KEY_SPEECH_LANGUAGE, "bn-IN") ?: "bn-IN"
    fun setSpeechLanguage(language: String) {
        val safeLanguage = if (language == "en-US") "en-US" else "bn-IN"
        preferences.edit().putString(KEY_SPEECH_LANGUAGE, safeLanguage).apply()
    }

    fun autoObservationEnabled(): Boolean = preferences.getBoolean(KEY_AUTO_OBSERVE, false)
    fun setAutoObservationEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_OBSERVE, enabled).apply()
    }

    fun accessibilityConsentAccepted(): Boolean = preferences.getBoolean(KEY_ACCESSIBILITY_CONSENT, false)
    fun setAccessibilityConsentAccepted(accepted: Boolean) {
        preferences.edit().putBoolean(KEY_ACCESSIBILITY_CONSENT, accepted).apply()
    }

    private companion object {
        const val KEY_SPEECH_OUTPUT = "speech_output_enabled"
        const val KEY_SPEECH_LANGUAGE = "speech_language"
        const val KEY_AUTO_OBSERVE = "auto_observe"
        const val KEY_ACCESSIBILITY_CONSENT = "accessibility_consent_accepted"
    }
}
