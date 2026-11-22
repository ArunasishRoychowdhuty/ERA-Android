package com.eraassistant.mobile.statemachine

import android.content.Context
import com.eraassistant.mobile.capability.ExecutionError
import com.eraassistant.mobile.capability.ExecutionErrorCategory
import com.eraassistant.mobile.memory.PrivacyFilter
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed task lifecycle journal.
 *
 * Epoch timestamps are stored in seconds to avoid generic secret/card filters treating a
 * 13-digit millisecond value as a payment-card value. Histories are bounded and free-form
 * text is sanitized before persistence. Malformed stored state fails closed.
 */
class SharedPreferencesTaskStatePersistence(context: Context) : TaskStatePersistence {
    private val prefs = context.getSharedPreferences("era_task_state_machine", Context.MODE_PRIVATE)

    override fun load(taskId: String): TaskStateSnapshot? {
        val raw = prefs.getString(key(taskId), null) ?: return null
        return try {
            val root = JSONObject(raw)
            val actionsJson = root.optJSONArray("actions") ?: JSONArray()
            val actions = buildList {
                val start = (actionsJson.length() - MAX_ACTIONS).coerceAtLeast(0)
                for (index in start until actionsJson.length()) {
                    val item = actionsJson.getJSONObject(index)
                    val actionHistory = readHistory(item.optJSONArray("history"), MAX_ACTION_HISTORY)
                    val errorCategory = item.optString("errorCategory").takeIf(String::isNotBlank)
                    val error = errorCategory?.let { category ->
                        ExecutionError(
                            category = ExecutionErrorCategory.valueOf(category),
                            message = PrivacyFilter.sanitizeText(item.optString("errorMessage")).take(MAX_TEXT)
                        )
                    }
                    add(
                        ActionExecutionProgress(
                            actionId = item.getString("actionId").take(MAX_ID),
                            name = PrivacyFilter.sanitizeText(item.optString("name")).take(MAX_TEXT),
                            state = ExecutionState.valueOf(item.getString("state")),
                            retryCount = item.optInt("retryCount", 0).coerceIn(0, MAX_RETRIES),
                            maxRetries = item.optInt("maxRetries", 2).coerceIn(0, MAX_RETRIES),
                            lastError = error,
                            history = actionHistory,
                            verificationEvidence = item.optString("verificationEvidence")
                                .takeIf(String::isNotBlank)
                                ?.let(PrivacyFilter::sanitizeText)
                                ?.take(MAX_TEXT)
                        )
                    )
                }
            }
            TaskStateSnapshot(
                taskId = root.getString("taskId"),
                goal = PrivacyFilter.sanitizeText(root.optString("goal")).take(MAX_TEXT),
                state = ExecutionState.valueOf(root.getString("state")),
                actions = actions,
                history = readHistory(root.optJSONArray("history"), MAX_TASK_HISTORY),
                createdAtEpochMs = root.optLong("createdAtEpochSeconds", 0L) * 1_000L,
                updatedAtEpochMs = root.optLong("updatedAtEpochSeconds", 0L) * 1_000L
            ).also {
                require(it.taskId == taskId) { "Task state key does not match stored task ID." }
                require(it.createdAtEpochMs > 0L && it.updatedAtEpochMs > 0L) {
                    "Stored task lifecycle timestamps are invalid."
                }
            }
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Stored task lifecycle state is corrupt or incompatible; refusing to reset its history.",
                error
            )
        }
    }

    override fun save(snapshot: TaskStateSnapshot): Boolean {
        if (snapshot.actions.size > MAX_ACTIONS) return false
        return runCatching {
            val actions = JSONArray()
            snapshot.actions.sortedBy { it.actionId }.forEach { action ->
                actions.put(JSONObject().apply {
                    put("actionId", action.actionId.take(MAX_ID))
                    put("name", PrivacyFilter.sanitizeText(action.name).take(MAX_TEXT))
                    put("state", action.state.name)
                    put("retryCount", action.retryCount.coerceIn(0, MAX_RETRIES))
                    put("maxRetries", action.maxRetries.coerceIn(0, MAX_RETRIES))
                    action.lastError?.let {
                        put("errorCategory", it.category.name)
                        put("errorMessage", PrivacyFilter.sanitizeText(it.message).take(MAX_TEXT))
                    }
                    put("verificationEvidence", action.verificationEvidence?.let {
                        PrivacyFilter.sanitizeText(it).take(MAX_TEXT)
                    }.orEmpty())
                    put("history", writeHistory(action.history, MAX_ACTION_HISTORY))
                })
            }
            val root = JSONObject().apply {
                put("taskId", snapshot.taskId)
                put("goal", PrivacyFilter.sanitizeText(snapshot.goal).take(MAX_TEXT))
                put("state", snapshot.state.name)
                put("createdAtEpochSeconds", snapshot.createdAtEpochMs / 1_000L)
                put("updatedAtEpochSeconds", snapshot.updatedAtEpochMs / 1_000L)
                put("history", writeHistory(snapshot.history, MAX_TASK_HISTORY))
                put("actions", actions)
            }
            prefs.edit().putString(key(snapshot.taskId), root.toString()).commit()
        }.getOrDefault(false)
    }

    override fun clear(taskId: String): Boolean =
        prefs.edit().remove(key(taskId)).commit()

    private fun key(taskId: String) = "task_${taskId.take(MAX_ID)}"

    private fun writeHistory(records: List<StateTransitionRecord>, limit: Int): JSONArray =
        JSONArray().apply {
            records.takeLast(limit).forEach { record ->
                put(JSONObject().apply {
                    put("from", record.fromState.name)
                    put("to", record.toState.name)
                    put("atEpochSeconds", record.timestampEpochMs / 1_000L)
                    put("reason", PrivacyFilter.sanitizeText(record.reason).take(MAX_TEXT))
                    put("triggeredBy", PrivacyFilter.sanitizeText(record.triggeredBy).take(80))
                })
            }
        }

    private fun readHistory(json: JSONArray?, limit: Int): List<StateTransitionRecord> {
        if (json == null) return emptyList()
        val start = (json.length() - limit).coerceAtLeast(0)
        return buildList {
            for (index in start until json.length()) {
                val item = json.getJSONObject(index)
                add(
                    StateTransitionRecord(
                        fromState = ExecutionState.valueOf(item.getString("from")),
                        toState = ExecutionState.valueOf(item.getString("to")),
                        timestampEpochMs = item.optLong("atEpochSeconds", 0L) * 1_000L,
                        reason = PrivacyFilter.sanitizeText(item.optString("reason")).take(MAX_TEXT),
                        triggeredBy = PrivacyFilter.sanitizeText(item.optString("triggeredBy")).take(80)
                    )
                )
            }
        }
    }

    private companion object {
        const val MAX_ID = 160
        const val MAX_TEXT = 2_000
        const val MAX_ACTIONS = 100
        const val MAX_TASK_HISTORY = 100
        const val MAX_ACTION_HISTORY = 50
        const val MAX_RETRIES = 10
    }
}
