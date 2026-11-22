package com.eraassistant.mobile.recovery

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed durable journal store. Corrupt data is surfaced as an error
 * instead of being treated as an empty journal (which could reopen old idempotency keys).
 */
class SharedPreferencesExecutionJournalPersistence(
    context: Context,
    private val prefsName: String = "era_execution_journal"
) : ExecutionJournalPersistence {
    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun loadAll(): List<JournalEntry> = synchronized(PROCESS_LOCK) {
        val raw = prefs.getString(KEY_JOURNAL_ENTRIES, null) ?: return@synchronized emptyList()
        try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        JournalEntry(
                            journalId = obj.getString("journalId"),
                            taskId = obj.getString("taskId"),
                            actionId = obj.getString("actionId"),
                            idempotencyKey = obj.getString("idempotencyKey"),
                            actionFingerprint = obj.getString("actionFingerprint"),
                            actionName = obj.getString("actionName"),
                            state = JournalEntryState.valueOf(obj.getString("state")),
                            isConsequential = obj.optBoolean("isConsequential", false),
                            retryCount = obj.optInt("retryCount", 0),
                            checkpointData = obj.optString("checkpointData").takeIf { it.isNotBlank() },
                            failureReason = obj.optString("failureReason").takeIf { it.isNotBlank() },
                            createdAtEpochMs = obj.getLong("createdAtEpochMs"),
                            updatedAtEpochMs = obj.getLong("updatedAtEpochMs")
                        )
                    )
                }
            }
        } catch (error: Throwable) {
            throw IllegalStateException("Execution journal is corrupt or incompatible; refusing to treat it as empty.", error)
        }
    }

    override fun save(entry: JournalEntry): Boolean = synchronized(PROCESS_LOCK) {
        val all = try {
            loadAll().toMutableList()
        } catch (_: Throwable) {
            return@synchronized false
        }
        val index = all.indexOfFirst { it.journalId == entry.journalId }
        if (index >= 0) {
            all[index] = entry
        } else {
            if (all.size >= MAX_JOURNAL_SIZE) {
                val cutoff = System.currentTimeMillis() - TERMINAL_RETENTION_MS
                all.removeAll { it.state.isTerminal && it.updatedAtEpochMs < cutoff }
            }
            // Do not evict in-flight/recovery records or silently truncate recent idempotency history.
            if (all.size >= MAX_JOURNAL_SIZE) return@synchronized false
            all.add(entry)
        }

        val array = JSONArray()
        all.forEach { item ->
            array.put(JSONObject().apply {
                put("journalId", item.journalId)
                put("taskId", item.taskId)
                put("actionId", item.actionId)
                put("idempotencyKey", item.idempotencyKey)
                put("actionFingerprint", item.actionFingerprint)
                put("actionName", item.actionName)
                put("state", item.state.name)
                put("isConsequential", item.isConsequential)
                put("retryCount", item.retryCount)
                put("checkpointData", item.checkpointData.orEmpty())
                put("failureReason", item.failureReason.orEmpty())
                put("createdAtEpochMs", item.createdAtEpochMs)
                put("updatedAtEpochMs", item.updatedAtEpochMs)
            })
        }
        prefs.edit().putString(KEY_JOURNAL_ENTRIES, array.toString()).commit()
    }

    override fun remove(journalId: String) = synchronized(PROCESS_LOCK) {
        val all = loadAll().filterNot { it.journalId == journalId }
        writeAll(all)
        Unit
    }

    override fun clear() = synchronized(PROCESS_LOCK) {
        prefs.edit().remove(KEY_JOURNAL_ENTRIES).commit()
        Unit
    }

    private fun writeAll(entries: List<JournalEntry>): Boolean {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(JSONObject().apply {
                put("journalId", item.journalId)
                put("taskId", item.taskId)
                put("actionId", item.actionId)
                put("idempotencyKey", item.idempotencyKey)
                put("actionFingerprint", item.actionFingerprint)
                put("actionName", item.actionName)
                put("state", item.state.name)
                put("isConsequential", item.isConsequential)
                put("retryCount", item.retryCount)
                put("checkpointData", item.checkpointData.orEmpty())
                put("failureReason", item.failureReason.orEmpty())
                put("createdAtEpochMs", item.createdAtEpochMs)
                put("updatedAtEpochMs", item.updatedAtEpochMs)
            })
        }
        return prefs.edit().putString(KEY_JOURNAL_ENTRIES, array.toString()).commit()
    }

    companion object {
        private val PROCESS_LOCK = Any()
        private const val KEY_JOURNAL_ENTRIES = "entries_v1"
        private const val MAX_JOURNAL_SIZE = 500
        private const val TERMINAL_RETENTION_MS = 86_400_000L
    }
}
