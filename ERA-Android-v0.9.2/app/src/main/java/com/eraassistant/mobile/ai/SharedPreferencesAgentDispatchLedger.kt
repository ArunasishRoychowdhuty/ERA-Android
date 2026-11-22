package com.eraassistant.mobile.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Durable replay guard for capability dispatches.
 *
 * Only hashes, states and timestamps are persisted; action text, labels, URLs and screen
 * content are never written here. commit() is intentional: dispatch must not begin until
 * the IN_PROGRESS claim has reached persistent storage. Call from a worker thread.
 */
class SharedPreferencesAgentDispatchLedger(context: Context) : AgentDispatchLedger {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private data class Entry(
        val keyHash: String,
        val fingerprint: String,
        val state: AgentDispatchRecordState,
        val updatedAtEpochMs: Long
    )

    override fun claim(idempotencyKey: String, actionFingerprint: String): AgentDispatchClaim =
        synchronized(PROCESS_LOCK) {
            val entries = readEntries() ?: return@synchronized AgentDispatchClaim.StorageFailure
            val keyHash = digest(idempotencyKey)
            val existing = entries.firstOrNull { it.keyHash == keyHash }
            if (existing != null) {
                return@synchronized if (existing.fingerprint == actionFingerprint) {
                    AgentDispatchClaim.Existing(existing.state)
                } else {
                    AgentDispatchClaim.KeyConflict
                }
            }
            if (entries.size >= MAX_RECORDS) return@synchronized AgentDispatchClaim.StorageFailure
            entries += Entry(keyHash, actionFingerprint, AgentDispatchRecordState.IN_PROGRESS, System.currentTimeMillis())
            if (writeEntries(entries)) AgentDispatchClaim.Acquired else AgentDispatchClaim.StorageFailure
        }

    override fun finish(
        idempotencyKey: String,
        actionFingerprint: String,
        state: AgentDispatchRecordState
    ): Boolean = synchronized(PROCESS_LOCK) {
        if (state == AgentDispatchRecordState.IN_PROGRESS) return@synchronized false
        val entries = readEntries() ?: return@synchronized false
        val keyHash = digest(idempotencyKey)
        val index = entries.indexOfFirst { it.keyHash == keyHash && it.fingerprint == actionFingerprint }
        if (index < 0 || entries[index].state != AgentDispatchRecordState.IN_PROGRESS) return@synchronized false
        entries[index] = entries[index].copy(state = state, updatedAtEpochMs = System.currentTimeMillis())
        writeEntries(entries)
    }

    private fun readEntries(): MutableList<Entry>? {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        Entry(
                            keyHash = item.getString("keyHash"),
                            fingerprint = item.getString("fingerprint"),
                            state = AgentDispatchRecordState.valueOf(item.getString("state")),
                            updatedAtEpochMs = item.optLong("updatedAtEpochMs", 0L)
                        )
                    )
                }
            }.toMutableList()
        }.getOrNull()
    }

    private fun writeEntries(entries: List<Entry>): Boolean {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("keyHash", entry.keyHash)
                put("fingerprint", entry.fingerprint)
                put("state", entry.state.name)
                put("updatedAtEpochMs", entry.updatedAtEpochMs)
            })
        }
        return prefs.edit().putString(KEY_ENTRIES, array.toString()).commit()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        /** Serializes claims across every ledger instance in this app process. */
        private val PROCESS_LOCK = Any()
        private const val PREFS_NAME = "era_agent_dispatch_journal"
        private const val KEY_ENTRIES = "dispatch_entries_v1"
        private const val MAX_RECORDS = 5_000
    }
}
