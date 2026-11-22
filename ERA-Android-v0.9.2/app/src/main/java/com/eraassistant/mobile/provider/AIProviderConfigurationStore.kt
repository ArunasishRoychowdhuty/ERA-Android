package com.eraassistant.mobile.provider

import android.content.Context

class AIProviderConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("era_provider_settings", Context.MODE_PRIVATE)
    private val secrets = SecureApiKeyStore(context)

    fun load(): AIProviderConfiguration = AIProviderConfiguration(
        provider = runCatching {
            AIProviderKind.valueOf(preferences.getString(KEY_PROVIDER, AIProviderKind.LOCAL_ONLY.name).orEmpty())
        }.getOrDefault(AIProviderKind.LOCAL_ONLY),
        modelId = preferences.getString(KEY_MODEL, "").orEmpty(),
        endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty(),
        remoteFallbackEnabled = preferences.getBoolean(KEY_REMOTE_FALLBACK, false)
    )

    fun save(configuration: AIProviderConfiguration, apiKey: String? = null): List<String> {
        val errors = configuration.validate()
        if (errors.isNotEmpty()) return errors
        if (configuration.provider == AIProviderKind.LOCAL_ONLY ||
            configuration.provider == AIProviderKind.OLLAMA_LOCAL
        ) {
            secrets.clear()
        } else if (!apiKey.isNullOrBlank()) {
            secrets.save(apiKey)
        }
        preferences.edit()
            .putString(KEY_PROVIDER, configuration.provider.name)
            .putString(KEY_MODEL, configuration.modelId.trim())
            .putString(KEY_ENDPOINT, configuration.endpoint.trim().trimEnd('/'))
            .putBoolean(KEY_REMOTE_FALLBACK, configuration.remoteFallbackEnabled)
            .commit()
        return emptyList()
    }

    fun apiKey(): String? = secrets.read()
    fun hasApiKey(): Boolean = secrets.hasKey()
    fun clearApiKey() = secrets.clear()
    fun remoteFallbackReady(): Boolean = load().isRemoteConfigured(hasApiKey())

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL = "model_id"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_REMOTE_FALLBACK = "remote_fallback_enabled"
    }
}
