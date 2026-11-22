package com.eraassistant.mobile.search

import android.graphics.Bitmap
import com.eraassistant.mobile.provider.AIProviderConfiguration
import com.eraassistant.mobile.provider.AIProviderConnectionTester
import com.eraassistant.mobile.provider.AIProviderKind
import com.eraassistant.mobile.provider.BoundedResponseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class VisualProductCandidate(
    val productName: String,
    val brand: String,
    val model: String,
    val category: String,
    val confidence: Double,
    val visualNotes: String
) {
    val shoppingQuery: String
        get() = listOf(brand, productName, model)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .joinToString(" ")
}

/** Parses a constrained JSON answer; it does not claim the model identification is certain. */
object VisualProductResponseParser {
    fun parse(raw: String): VisualProductCandidate {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "The visual model returned no product JSON." }
        val json = JSONObject(raw.substring(start, end + 1))
        val name = json.optString("productName").trim().take(MAX_FIELD_CHARS)
        require(name.isNotBlank()) { "The image model could not identify a product name." }
        return VisualProductCandidate(
            productName = name,
            brand = json.optString("brand").trim().take(MAX_FIELD_CHARS),
            model = json.optString("model").trim().take(MAX_FIELD_CHARS),
            category = json.optString("category").trim().take(MAX_FIELD_CHARS),
            confidence = json.optDouble("confidence", 0.5).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.5,
            visualNotes = json.optString("visualNotes").trim().take(MAX_FIELD_CHARS)
        )
    }

    private const val MAX_FIELD_CHARS = 120
}

/**
 * Optional user-triggered vision request. A captured frame is sent only after the UI
 * separately obtains explicit cloud-upload consent. This class never stores the image.
 */
object VisualProductRecognitionProvider {
    suspend fun identify(
        bitmap: Bitmap,
        configuration: AIProviderConfiguration,
        apiKey: String
    ): VisualProductCandidate = withContext(Dispatchers.IO) {
        require(configuration.provider == AIProviderKind.GEMINI ||
            configuration.provider == AIProviderKind.OPENAI_COMPATIBLE
        ) {
            "Visual recognition requires an explicit remote provider. The local Ollama planner does not receive image frames."
        }
        require(configuration.validate().isEmpty()) { configuration.validate().joinToString() }
        require(apiKey.isNotBlank()) { "A saved provider API key is required." }
        val imageBytes = encodeFrame(bitmap)
        val base64 = Base64.getEncoder().encodeToString(imageBytes)
        val body = when (configuration.provider) {
            AIProviderKind.GEMINI -> geminiBody(base64)
            AIProviderKind.OPENAI_COMPATIBLE -> openAiBody(base64, configuration.modelId)
            AIProviderKind.LOCAL_ONLY, AIProviderKind.OLLAMA_LOCAL -> error("Local-only mode does not send image data.")
        }
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(AIProviderConnectionTester.endpoint(configuration)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                when (configuration.provider) {
                    AIProviderKind.GEMINI -> setRequestProperty("x-goog-api-key", apiKey)
                    AIProviderKind.OPENAI_COMPATIBLE -> setRequestProperty("Authorization", "Bearer $apiKey")
                    AIProviderKind.LOCAL_ONLY, AIProviderKind.OLLAMA_LOCAL -> Unit
                }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                throw IOException("Visual recognition provider returned HTTP $status.")
            }
            val response = BoundedResponseReader.readUtf8(connection.inputStream, MAX_RESPONSE_CHARS)
            VisualProductResponseParser.parse(extractText(response, configuration.provider))
        } finally {
            connection?.disconnect()
        }
    }

    private fun encodeFrame(source: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        if (!source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
            throw IOException("Could not encode the captured image.")
        }
        var bytes = output.toByteArray()
        if (bytes.size > MAX_IMAGE_BYTES) {
            output.reset()
            if (!source.compress(Bitmap.CompressFormat.JPEG, LOW_JPEG_QUALITY, output)) {
                throw IOException("Could not reduce the captured image size.")
            }
            bytes = output.toByteArray()
        }
        require(bytes.size <= MAX_IMAGE_BYTES) { "Captured image is too large for a bounded visual lookup." }
        return bytes
    }

    private fun geminiBody(base64: String): JSONObject = JSONObject()
        .put("contents", JSONArray().put(
            JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PRODUCT_PROMPT))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", base64)))
        )))
        .put("generationConfig", JSONObject()
            .put("responseMimeType", "application/json")
            .put("maxOutputTokens", 350))

    private fun openAiBody(base64: String, modelId: String): JSONObject {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", PRODUCT_PROMPT))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64,$base64")
                .put("detail", "high")))
        return JSONObject()
            .put("model", modelId)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", content)))
            .put("max_tokens", 350)
    }

    private fun extractText(response: String, provider: AIProviderKind): String {
        val root = JSONObject(response)
        return when (provider) {
            AIProviderKind.GEMINI -> {
                val parts = root.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
                    .joinToString("\n").trim()
            }
            AIProviderKind.OPENAI_COMPATIBLE -> root.getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content").trim()
            AIProviderKind.LOCAL_ONLY, AIProviderKind.OLLAMA_LOCAL -> error("Local-only mode has no remote response.")
        }
    }

    private const val PRODUCT_PROMPT = """
Identify the main clearly visible commercial product in the supplied image. Do not identify people.
Return only JSON with keys: productName, brand, model, category, confidence, visualNotes.
Use empty strings for unknown brand/model/category. confidence must be a number from 0 to 1.
Do not invent a brand or model. If the exact product cannot be distinguished, use a broad productName and lower confidence.
visualNotes should briefly state visible distinguishing features. Do not include personal data or text unrelated to the product.
"""
    private const val TIMEOUT_MS = 25_000
    private const val MAX_RESPONSE_CHARS = 4_000
    private const val MAX_IMAGE_BYTES = 1_500_000
    private const val JPEG_QUALITY = 78
    private const val LOW_JPEG_QUALITY = 58
}
