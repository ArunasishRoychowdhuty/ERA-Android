package com.eraassistant.mobile.history

import android.content.Context

class CommandHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("era_history", Context.MODE_PRIVATE)

    fun loadRecords(): List<CommandHistoryRecord> {
        val raw = preferences.getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return CommandHistoryCodec.decode(raw).take(MAX_ITEMS)
    }

    fun load(): List<String> = CommandHistoryCodec.toDisplayList(loadRecords())

    /** Backward-compatible entry point for existing callers. */
    fun add(entry: String): List<String> {
        val parts = entry.split(" → ", limit = 2)
        return add(parts.firstOrNull().orEmpty(), parts.getOrNull(1).orEmpty())
    }

    fun add(command: String, result: String): List<String> {
        val updated = (listOf(CommandHistoryCodec.create(command, result)) + loadRecords()).take(MAX_ITEMS)
        preferences.edit().putString(KEY_HISTORY, CommandHistoryCodec.encode(updated)).apply()
        return CommandHistoryCodec.toDisplayList(updated)
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    private companion object {
        const val KEY_HISTORY = "items"
        const val MAX_ITEMS = 50
    }
}
