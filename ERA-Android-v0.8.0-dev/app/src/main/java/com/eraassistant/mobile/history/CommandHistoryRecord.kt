package com.eraassistant.mobile.history

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class HistoryOutcome { SUCCESS, INFO, FAILED, CANCELLED }

data class CommandHistoryRecord(
    val id: String,
    val timestampEpochMs: Long,
    val command: String,
    val result: String,
    val outcome: HistoryOutcome
 ) {
    fun displayText(): String {
        val time = if (timestampEpochMs > 0L) {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(timestampEpochMs))
        } else "--:--"
        return "$time [${outcome.name}] ${command} → ${result}"
    }
}

object CommandHistoryCodec {
    private const val VERSION = 2

    fun create(command: String, result: String, nowEpochMs: Long = System.currentTimeMillis()): CommandHistoryRecord {
        val safeCommand = HistorySanitizer.redact(command).take(180)
        val safeResult = HistorySanitizer.redact(result).take(240)
        return CommandHistoryRecord(
            id = UUID.randomUUID().toString(),
            timestampEpochMs = nowEpochMs,
            command = safeCommand,
            result = safeResult,
            outcome = classify(result)
        )
    }

    fun classify(result: String): HistoryOutcome {
        val text = result.lowercase()
        return when {
            listOf("failed", "failure", "ব্যর্থ", "পাওয়া যায়নি", "পাওয়া যায়নি", "খোলা যায়নি", "খোলা যায়নি", "permission denied").any(text::contains) -> HistoryOutcome.FAILED
            listOf("cancel", "বাতিল", "বন্ধ করা হয়েছে").any(text::contains) -> HistoryOutcome.CANCELLED
            listOf("preview", "waiting", "নির্বাচন", "review", "খোলা হয়েছে", "খোলা হয়েছে").any(text::contains) -> HistoryOutcome.INFO
            else -> HistoryOutcome.SUCCESS
        }
    }

    fun encode(records: List<CommandHistoryRecord>): String = JSONObject()
        .put("schema", "era.history.v2")
        .put("version", VERSION)
        .put("items", JSONArray().apply {
            records.forEach { item ->
                put(JSONObject()
                    .put("id", item.id)
                    .put("at", item.timestampEpochMs)
                    .put("command", item.command)
                    .put("result", item.result)
                    .put("outcome", item.outcome.name))
            }
        }).toString()

    /** Supports the prior v1 JSON array of display strings. */
    fun decode(raw: String): List<CommandHistoryRecord> = runCatching {
        val root = JSONObject(raw)
        val items = root.optJSONArray("items") ?: return@runCatching emptyList()
        (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val command = item.optString("command").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val result = item.optString("result")
            val outcome = runCatching { HistoryOutcome.valueOf(item.optString("outcome")) }
                .getOrDefault(classify(result))
            CommandHistoryRecord(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                timestampEpochMs = item.optLong("at", 0L),
                command = command.take(180),
                result = result.take(240),
                outcome = outcome
            )
        }
    }.getOrElse {
        runCatching {
            val legacy = JSONArray(raw)
            (0 until legacy.length()).mapNotNull { index ->
                val line = legacy.optString(index).takeIf(String::isNotBlank) ?: return@mapNotNull null
                val split = line.split(" → ", limit = 2)
                val command = split.first().trim().take(180)
                val result = split.getOrNull(1).orEmpty().trim().take(240)
                CommandHistoryRecord(
                    id = UUID.randomUUID().toString(),
                    timestampEpochMs = 0L,
                    command = HistorySanitizer.redact(command),
                    result = HistorySanitizer.redact(result),
                    outcome = classify(result)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun toDisplayList(records: List<CommandHistoryRecord>): List<String> = records.map { it.displayText() }
}

object HistorySanitizer {
    private val phoneLike = Regex("(?<!\\d)\\+?\\d[\\d ()-]{6,}\\d(?!\\d)")
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val apiToken = Regex("(?i)(AIza[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{12,}|Bearer\\s+\\S+)")
    fun redact(value: String): String = value
        .replace(apiToken, "[REDACTED]")
        .replace(email, "[EMAIL]")
        .replace(phoneLike, "[PHONE]")
}
