package com.eraassistant.mobile.provider

import com.eraassistant.mobile.ai.AIPlannerProvider
import com.eraassistant.mobile.ai.PlannerRequest
import com.eraassistant.mobile.ai.PlannerResponse
import com.eraassistant.mobile.ai.PlannerStep
import com.eraassistant.mobile.ai.Risk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Remote planning adapter. It receives only the task goal/capabilities, never screen frames. */
class RemoteAIPlannerProvider(
    private val configuration: AIProviderConfiguration,
    private val apiKey: String
) : AIPlannerProvider {
    override suspend fun plan(request: PlannerRequest): PlannerResponse = withContext(Dispatchers.IO) {
        require(configuration.provider == AIProviderKind.GEMINI ||
            configuration.provider == AIProviderKind.OPENAI_COMPATIBLE
        ) { "Select a supported remote provider first." }
        require(configuration.validate().isEmpty()) { configuration.validate().joinToString() }
        require(apiKey.isNotBlank()) { "Provider API key is not configured." }
        require(request.goal.isNotBlank() && request.goal.length <= 2_000) { "Goal is empty or too long." }

        val prompt = buildPrompt(request)
        val body = when (configuration.provider) {
            AIProviderKind.GEMINI -> geminiBody(prompt)
            AIProviderKind.OPENAI_COMPATIBLE -> openAiBody(prompt)
            AIProviderKind.LOCAL_ONLY, AIProviderKind.OLLAMA_LOCAL -> error("This provider is not handled by the remote planner.")
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
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.let { BoundedResponseReader.readUtf8(it, MAX_RESPONSE_CHARS) }.orEmpty()
            if (status !in 200..299) throw ProviderHttpException(status, "Remote planner returned HTTP $status.")
            parsePlan(extractText(response))
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildPrompt(request: PlannerRequest): String = buildString {
        appendLine("Create a safe, concise task plan for an Android assistant.")
        appendLine("Return only one JSON object with keys steps and successCriteria.")
        appendLine("Each step: id (1-based numeric string), description, tool, dependsOn (array of ids), risk (LOW, REVIEW or CONSEQUENTIAL).")
        appendLine("Use 1 to 12 steps. Dependencies must refer only to earlier step IDs.")
        appendLine("Never claim an action was executed. Never propose bypassing permissions or security.")
        appendLine("Sending, publishing, calling, paying, purchasing, deleting, sharing or submitting must be CONSEQUENTIAL and explicitly user-performed.")
        appendLine("User goal: ${request.goal.take(2_000)}")
        appendLine("Available capabilities: ${request.capabilities.take(40).joinToString(", ")}")
        request.uiContext?.take(4_000)?.let { appendLine("User-approved textual context: $it") }
    }

    private fun geminiBody(prompt: String): JSONObject = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 2048))

    private fun openAiBody(prompt: String): JSONObject = JSONObject()
        .put("model", configuration.modelId)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        .put("response_format", JSONObject().put("type", "json_object"))
        .put("max_tokens", 2048)

    private fun extractText(response: String): String {
        val root = JSONObject(response)
        val text = when (configuration.provider) {
            AIProviderKind.GEMINI -> root.getJSONArray("candidates")
                .getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text")
            AIProviderKind.OPENAI_COMPATIBLE -> root.getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
            AIProviderKind.LOCAL_ONLY, AIProviderKind.OLLAMA_LOCAL -> error("Local provider has no remote response.")
        }.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "Provider response did not contain a JSON object." }
        return text.substring(start, end + 1)
    }

    private fun parsePlan(raw: String): PlannerResponse {
        val root = JSONObject(raw)
        val items = root.getJSONArray("steps")
        require(items.length() in 1..MAX_STEPS) { "Remote plan must contain 1 to $MAX_STEPS steps." }
        val steps = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val id = item.optString("id", (index + 1).toString()).trim()
            require(id.matches(Regex("[1-9][0-9]{0,2}"))) { "Remote step ID is invalid." }
            val description = item.optString("description").trim().take(MAX_DESCRIPTION)
            val tool = item.optString("tool").trim().take(80)
            require(description.isNotBlank() && tool.isNotBlank()) { "Remote plan step is missing description or tool." }
            val modelRisk = runCatching { Risk.valueOf(item.optString("risk").uppercase()) }.getOrDefault(Risk.REVIEW)
            val highImpact = HIGH_IMPACT.containsMatchIn("$tool $description")
            val risk = if (highImpact) Risk.CONSEQUENTIAL else modelRisk
            val dependencies = item.optJSONArray("dependsOn")?.let { array ->
                (0 until array.length()).map { array.optString(it).take(3) }
            }.orEmpty()
            PlannerStep(id, description, tool, dependencies, risk)
        }
        val known = steps.map { it.id }.toSet()
        require(steps.map { it.id }.distinct().size == steps.size) { "Remote plan step IDs are not unique." }
        steps.forEach { step ->
            require(step.dependsOn.all { it in known && it != step.id }) { "Remote plan contains an invalid dependency." }
            require(step.dependsOn.all { dep -> dep.toIntOrNull() != null && dep.toInt() < step.id.toInt() }) {
                "Remote plan dependencies must point to earlier steps."
            }
        }
        val criteriaJson = root.optJSONArray("successCriteria") ?: JSONArray()
        val criteria = (0 until criteriaJson.length()).map { criteriaJson.optString(it).trim().take(200) }
            .filter(String::isNotBlank).take(8)
        return PlannerResponse(steps, criteria)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val MAX_RESPONSE_CHARS = 16_000
        const val MAX_DESCRIPTION = 500
        const val MAX_STEPS = 12
        val HIGH_IMPACT = Regex("(?i)\\b(send|publish|post|call|pay|payment|purchase|buy|delete|remove|share|transfer|submit|install|authenticate)\\b|পাঠাও|প্রকাশ|কল কর|পেমেন্ট|মুছ|জমা দাও|শেয়ার")
    }
}
