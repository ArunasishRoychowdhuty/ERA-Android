package com.eraassistant.mobile.search

import android.graphics.Bitmap
import com.eraassistant.mobile.provider.AIProviderConfiguration
import com.eraassistant.mobile.provider.AIProviderConnectionTester
import com.eraassistant.mobile.provider.AIProviderKind
import com.eraassistant.mobile.provider.BoundedResponseReader
import com.eraassistant.mobile.screen.NormalizedScreenRegion
import com.eraassistant.mobile.screen.SensitiveInputPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class VisualControlCandidate(
    val label: String,
    val role: String,
    val region: NormalizedScreenRegion,
    val confidence: Double,
    val actionHint: String
)
data class VisualScreenAnalysis(val summary: String, val controls: List<VisualControlCandidate>)

/** Strict parser for bounded normalized targets; credential-like controls are filtered locally. */
object VisualScreenResponseParser {
    fun parse(raw: String): VisualScreenAnalysis {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "The vision model returned no screen-analysis JSON." }
        val json = JSONObject(raw.substring(start, end + 1))
        val summary = json.optString("summary").trim().take(400)
        val array = json.optJSONArray("controls") ?: JSONArray()
        val controls = buildList {
            for (index in 0 until minOf(array.length(), MAX_CONTROLS)) {
                val item = array.optJSONObject(index) ?: continue
                val label = item.optString("label").trim().take(MAX_LABEL_CHARS)
                val role = item.optString("role").trim().lowercase().take(40)
                val confidence = item.optDouble("confidence", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
                val bounds = item.optJSONObject("region") ?: continue
                val region = NormalizedScreenRegion(
                    bounds.optDouble("left", Double.NaN),
                    bounds.optDouble("top", Double.NaN),
                    bounds.optDouble("right", Double.NaN),
                    bounds.optDouble("bottom", Double.NaN)
                )
                if (label.isBlank() || role !in ALLOWED_ROLES || !region.isValid()) continue
                if (SensitiveInputPolicy.isSensitiveField(label = label, role = role)) continue
                add(VisualControlCandidate(label, role, region, confidence, item.optString("actionHint").trim().take(100)))
            }
        }
        return VisualScreenAnalysis(summary, controls)
    }
    private val ALLOWED_ROLES = setOf("button", "link", "tab", "checkbox", "radio", "switch", "menuitem", "listitem", "card", "icon", "navigation", "control", "unknown")
    private const val MAX_CONTROLS = 16
    private const val MAX_LABEL_CHARS = 100
}

/** User-triggered vision request. The UI must obtain a separate one-time cloud-upload consent. */
object VisualScreenUnderstandingProvider {
    suspend fun analyze(bitmap: Bitmap, configuration: AIProviderConfiguration, apiKey: String): VisualScreenAnalysis =
        withContext(Dispatchers.IO) {
            require(configuration.provider == AIProviderKind.GEMINI || configuration.provider == AIProviderKind.OPENAI_COMPATIBLE) {
                "Screen understanding requires an explicitly selected remote multimodal provider."
            }
            require(configuration.validate().isEmpty()) { configuration.validate().joinToString() }
            require(apiKey.isNotBlank()) { "A saved provider API key is required." }
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)) throw IOException("Could not encode screen frame.")
            var bytes = output.toByteArray()
            if (bytes.size > MAX_IMAGE_BYTES) {
                output.reset()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 52, output)) throw IOException("Could not reduce screen frame size.")
                bytes = output.toByteArray()
            }
            require(bytes.size <= MAX_IMAGE_BYTES) { "Screen frame exceeds the bounded upload size." }
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val body = if (configuration.provider == AIProviderKind.GEMINI) geminiBody(base64) else openAiBody(base64, configuration.modelId)
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(AIProviderConnectionTester.endpoint(configuration)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (configuration.provider == AIProviderKind.GEMINI) setRequestProperty("x-goog-api-key", apiKey)
                    else setRequestProperty("Authorization", "Bearer " + apiKey)
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    connection.errorStream?.close()
                    throw IOException("Screen-understanding provider returned HTTP " + status + ".")
                }
                val response = BoundedResponseReader.readUtf8(connection.inputStream, MAX_RESPONSE_CHARS)
                VisualScreenResponseParser.parse(extractText(response, configuration.provider))
            } finally {
                connection?.disconnect()
            }
        }

    private fun geminiBody(base64: String): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("text", SCREEN_PROMPT))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", base64)))
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 1200))
    }

    private fun openAiBody(base64: String, modelId: String): JSONObject {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", SCREEN_PROMPT))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64," + base64).put("detail", "high")))
        return JSONObject().put("model", modelId).put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", content)
        )).put("max_tokens", 1200)
    }

    private fun extractText(response: String, provider: AIProviderKind): String {
        val root = JSONObject(response)
        return when (provider) {
            AIProviderKind.GEMINI -> {
                val parts = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }.joinToString("\n").trim()
            }
            AIProviderKind.OPENAI_COMPATIBLE -> root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            else -> error("Local-only mode does not receive screen images.")
        }
    }

    private const val SCREEN_PROMPT = """
Analyze visible UI only. Identify at most 16 clearly visible interactive non-sensitive controls.
Never identify people, transcribe text typed into fields, infer hidden UI, or expose personal content.
Return ONLY JSON: {"summary":"short description","controls":[{"label":"visible label","role":"button|link|tab|checkbox|radio|switch|menuitem|listitem|card|icon|navigation|control|unknown","region":{"left":0.1,"top":0.2,"right":0.3,"bottom":0.3},"confidence":0.95,"actionHint":"visible purpose"}]}.
Region values must be normalized to the complete supplied image (0..1), never screen pixels.
Do not include password, OTP, PIN, payment, card, bank, API-key or token fields.
Do not propose Send, Publish, Pay, Delete, Purchase or Submit controls; those remain manual-only.
Use only visible labels. Never invent labels or coordinates. Omit uncertain controls.
"""
    private const val TIMEOUT_MS = 25_000
    private const val MAX_RESPONSE_CHARS = 12_000
    private const val MAX_IMAGE_BYTES = 1_500_000
}
