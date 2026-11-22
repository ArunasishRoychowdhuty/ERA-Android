package com.eraassistant.mobile.tasks

import android.content.Context
import com.eraassistant.mobile.memory.PrivacyFilter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores task-step metadata and a bounded state-transition history locally. A malformed
 * existing record is an error, not an empty task: silent reset could hide unfinished work.
 */
class SharedPreferencesTaskLedgerPersistence(context: Context) : TaskLedgerPersistence {
    private val prefs = context.getSharedPreferences("era_task_ledger", Context.MODE_PRIVATE)

    override fun load(namespace: String): List<TaskStepRecord>? {
        val raw = prefs.getString(key(namespace), null) ?: return null
        return try {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    val dependencies = item.optJSONArray("dependsOn") ?: JSONArray()
                    val step = AgentTaskStep(
                        id = item.getString("id"),
                        instruction = PrivacyFilter.sanitizeText(item.optString("instruction")).take(2_000),
                        expectedResult = PrivacyFilter.sanitizeText(item.optString("expectedResult")).take(2_000),
                        dependsOn = buildSet { for (i in 0 until dependencies.length()) add(dependencies.getString(i)) },
                        retryLimit = item.optInt("retryLimit", 1).coerceIn(0, 3),
                        requiresFreshApproval = item.optBoolean("requiresFreshApproval", true),
                        requiredCapabilities = buildSet {
                            val capabilities = item.optJSONArray("requiredCapabilities") ?: JSONArray()
                            for (i in 0 until capabilities.length()) {
                                add(com.eraassistant.mobile.planner.TaskCapability.valueOf(capabilities.getString(i)))
                            }
                        },
                        risk = runCatching {
                            com.eraassistant.mobile.planner.TaskRisk.valueOf(item.optString("risk", "LOW"))
                        }.getOrDefault(com.eraassistant.mobile.planner.TaskRisk.LOW),
                        reversible = item.optBoolean("reversible", true),
                        requiresUserApproval = item.optBoolean("requiresUserApproval", false)
                    )
                    val storedState = item.optString("state", "PENDING")
                    val state = when (storedState) {
                        "EXECUTION_REPORTED" -> TaskStepState.USER_REPORTED
                        "VERIFIED" -> TaskStepState.USER_CONFIRMED
                        else -> TaskStepState.valueOf(storedState)
                    }
                    val migratedNote = when (storedState) {
                        "EXECUTION_REPORTED" ->
                            "Legacy action report migrated. ERA has no independent dispatch receipt."
                        "VERIFIED" ->
                            "Legacy outcome was user-supplied; ERA did not independently verify the target app."
                        else -> PrivacyFilter.sanitizeText(item.optString("note"))
                    }
                    val historyJson = item.optJSONArray("history") ?: JSONArray()
                    val history = buildList {
                        val start = (historyJson.length() - MAX_HISTORY).coerceAtLeast(0)
                        for (i in start until historyJson.length()) {
                            val event = historyJson.getJSONObject(i)
                            add(
                                TaskStepTransition(
                                    fromState = TaskStepState.valueOf(event.getString("from")),
                                    toState = TaskStepState.valueOf(event.getString("to")),
                                    timestampEpochMs = event.optLong("at", 0L),
                                    note = PrivacyFilter.sanitizeText(event.optString("note")).take(500)
                                )
                            )
                        }
                    }
                    add(
                        TaskStepRecord(
                            step = step,
                            state = state,
                            note = migratedNote.take(500),
                            retryCount = item.optInt("retryCount", 0).coerceAtLeast(0),
                            history = history
                        )
                    )
                }
            }
        } catch (error: Throwable) {
            throw IllegalStateException("Stored task ledger is corrupt or incompatible; refusing to reset task progress.", error)
        }
    }

    override fun save(namespace: String, records: List<TaskStepRecord>): Boolean {
        val json = JSONArray()
        records.forEach { record ->
            val step = record.step
            val history = JSONArray()
            record.history.takeLast(MAX_HISTORY).forEach { event ->
                history.put(JSONObject().apply {
                    put("from", event.fromState.name)
                    put("to", event.toState.name)
                    put("at", event.timestampEpochMs)
                    put("note", PrivacyFilter.sanitizeText(event.note).take(500))
                })
            }
            json.put(JSONObject().apply {
                put("id", step.id)
                put("instruction", PrivacyFilter.sanitizeText(step.instruction).take(2_000))
                put("expectedResult", PrivacyFilter.sanitizeText(step.expectedResult).take(2_000))
                put("dependsOn", JSONArray(step.dependsOn.toList()))
                put("retryLimit", step.retryLimit)
                put("requiresFreshApproval", step.requiresFreshApproval)
                put("requiredCapabilities", JSONArray(step.requiredCapabilities.map { it.name }.sorted()))
                put("risk", step.risk.name)
                put("reversible", step.reversible)
                put("requiresUserApproval", step.requiresUserApproval)
                put("state", record.state.name)
                put("note", PrivacyFilter.sanitizeText(record.note).take(500))
                put("retryCount", record.retryCount)
                put("history", history)
            })
        }
        return prefs.edit().putString(key(namespace), json.toString()).commit()
    }

    override fun clear(namespace: String): Boolean =
        prefs.edit().remove(key(namespace)).commit()

    private fun key(namespace: String): String = "run_${namespace.take(80)}"

    companion object {
        private const val MAX_HISTORY = 100
    }
}
