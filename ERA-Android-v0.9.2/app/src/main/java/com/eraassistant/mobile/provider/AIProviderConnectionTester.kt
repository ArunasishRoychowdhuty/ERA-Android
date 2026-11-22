package com.eraassistant.mobile.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AIProviderConnectionResult(
    val connected: Boolean,
    val message: String,
    val httpStatus: Int? = null
)

/** User-triggered provider check; never logs or returns an API key or response body. */
object AIProviderConnectionTester {
    suspend fun test(configuration: AIProviderConfiguration, apiKey: String?): AIProviderConnectionResult =
        withContext(Dispatchers.IO) {
            val errors = configuration.validate()
            if (errors.isNotEmpty()) return@withContext AIProviderConnectionResult(false, errors.joinToString())
            if (configuration.provider == AIProviderKind.LOCAL_ONLY) {
                return@withContext AIProviderConnectionResult(true, "Deterministic local planner selected; no model endpoint is required.")
            }
            if (configuration.provider != AIProviderKind.OLLAMA_LOCAL && apiKey.isNullOrBlank()) {
                return@withContext AIProviderConnectionResult(false, "Save a provider API key before testing the connection.")
            }
            var connection: HttpURLConnection? = null
            try {
                val localOllama = configuration.provider == AIProviderKind.OLLAMA_LOCAL
                val target = endpoint(configuration)
                connection = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (localOllama) "GET" else "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = !localOllama
                    setRequestProperty("Accept", "application/json")
                    if (!localOllama) setRequestProperty("Content-Type", "application/json")
                    when (configuration.provider) {
                        AIProviderKind.GEMINI -> setRequestProperty("x-goog-api-key", apiKey)
                        AIProviderKind.OPENAI_COMPATIBLE -> setRequestProperty("Authorization", "Bearer $apiKey")
                        AIProviderKind.LOCAL_ONLY, AIProviderKind.OLLAMA_LOCAL -> Unit
                    }
                }
                if (!localOllama) {
                    val body = requestBody(configuration)
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.let { BoundedResponseReader.readUtf8(it, MAX_RESPONSE_CHARS) }.orEmpty()
                if (status !in 200..299) {
                    AIProviderConnectionResult(false, safeError(status), status)
                } else if (localOllama && !isModelAvailable(response, configuration.modelId)) {
                    AIProviderConnectionResult(false, "Ollama is running, but model '${configuration.modelId}' is not listed as installed.", status)
                } else if (localOllama) {
                    AIProviderConnectionResult(true, "Local Ollama endpoint and selected model are available. Planner requests stay on loopback.", status)
                } else {
                    AIProviderConnectionResult(true, "Provider connection successful.", status)
                }
            } catch (error: Exception) {
                AIProviderConnectionResult(false, "Connection failed: ${error::class.simpleName ?: "network error"}")
            } finally {
                connection?.disconnect()
            }
        }

    fun endpoint(configuration: AIProviderConfiguration): String {
        val base = configuration.endpoint.trim().trimEnd('/')
        return when (configuration.provider) {
            AIProviderKind.LOCAL_ONLY -> base
            AIProviderKind.OLLAMA_LOCAL -> "$base/api/tags"
            AIProviderKind.GEMINI -> "$base/models/${configuration.modelId}:generateContent"
            AIProviderKind.OPENAI_COMPATIBLE -> if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }
    }

    private fun requestBody(configuration: AIProviderConfiguration): String = when (configuration.provider) {
        AIProviderKind.GEMINI -> JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Reply exactly with ERA_CONNECTION_OK.")))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 16))
            .toString()
        AIProviderKind.OPENAI_COMPATIBLE -> JSONObject()
            .put("model", configuration.modelId)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply exactly with ERA_CONNECTION_OK.")))
            .put("max_tokens", 16)
            .toString()
        AIProviderKind.LOCAL_ONLY, AIProviderKind.OLLAMA_LOCAL -> "{}"
    }

    private fun isModelAvailable(response: String, modelId: String): Boolean = runCatching {
        val models = JSONObject(response).optJSONArray("models") ?: return false
        (0 until models.length()).any { index ->
            val item = models.optJSONObject(index) ?: return@any false
            item.optString("name") == modelId || item.optString("model") == modelId
        }
    }.getOrDefault(false)

    private fun safeError(status: Int): String =
        "Provider returned HTTP $status. Response body was intentionally omitted."

    private const val TIMEOUT_MS = 12_000
    private const val MAX_RESPONSE_CHARS = 4_000
}
