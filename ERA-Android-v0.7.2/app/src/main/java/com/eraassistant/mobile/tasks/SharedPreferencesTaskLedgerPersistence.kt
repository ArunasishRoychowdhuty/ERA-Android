package com.eraassistant.mobile.tasks

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores only task-step metadata locally so a validated task can resume after process recreation. */
class SharedPreferencesTaskLedgerPersistence(context: Context) : TaskLedgerPersistence {
    private val prefs = context.getSharedPreferences("era_task_ledger", Context.MODE_PRIVATE)

    override fun load(namespace: String): List<TaskStepRecord>? {
        val raw = prefs.getString(key(namespace), null) ?: return null
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    val dependencies = item.optJSONArray("dependsOn") ?: JSONArray()
                    val step = AgentTaskStep(
                        id = item.getString("id"),
                        instruction = item.optString("instruction"),
                        expectedResult = item.optString("expectedResult"),
                        dependsOn = buildSet { for (i in 0 until dependencies.length()) add(dependencies.getString(i)) },
                        retryLimit = item.optInt("retryLimit", 1).coerceIn(0, 3),
                        requiresFreshApproval = item.optBoolean("requiresFreshApproval", true)
                    )
                    val state = runCatching { TaskStepState.valueOf(item.optString("state", "PENDING")) }
                        .getOrDefault(TaskStepState.PENDING)
                    add(TaskStepRecord(step, state, item.optString("note"), item.optInt("retryCount", 0).coerceAtLeast(0)))
                }
            }
        }.getOrNull()
    }

    override fun save(namespace: String, records: List<TaskStepRecord>) {
        val json = JSONArray()
        records.forEach { record ->
            val step = record.step
            json.put(JSONObject().apply {
                put("id", step.id)
                put("instruction", step.instruction)
                put("expectedResult", step.expectedResult)
                put("dependsOn", JSONArray(step.dependsOn.toList()))
                put("retryLimit", step.retryLimit)
                put("requiresFreshApproval", step.requiresFreshApproval)
                put("state", record.state.name)
                put("note", record.note.take(500))
                put("retryCount", record.retryCount)
            })
        }
        prefs.edit().putString(key(namespace), json.toString()).apply()
    }

    override fun clear(namespace: String) {
        prefs.edit().remove(key(namespace)).apply()
    }

    private fun key(namespace: String): String = "run_${namespace.take(80)}"
}
