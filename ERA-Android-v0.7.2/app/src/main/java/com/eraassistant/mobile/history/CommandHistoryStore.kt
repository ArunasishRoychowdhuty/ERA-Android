package com.eraassistant.mobile.history

import android.content.Context
import org.json.JSONArray

class CommandHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("era_history", Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = preferences.getString(KEY_HISTORY, "[]") ?: "[]"
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index -> json.optString(index).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }

    fun add(entry: String): List<String> {
        val updated = (listOf(entry) + load()).take(MAX_ITEMS)
        val json = JSONArray().apply { updated.forEach(::put) }
        preferences.edit().putString(KEY_HISTORY, json.toString()).apply()
        return updated
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    private companion object {
        const val KEY_HISTORY = "items"
        const val MAX_ITEMS = 50
    }
}
