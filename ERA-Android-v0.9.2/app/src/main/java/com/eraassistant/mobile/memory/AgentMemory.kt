package com.eraassistant.mobile.memory

import android.content.Context
import android.content.SharedPreferences
import com.eraassistant.mobile.statemachine.ActionExecutionProgress
import com.eraassistant.mobile.statemachine.ExecutionState
import com.eraassistant.mobile.statemachine.TaskStateMachine
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Modular categories for persistent agent memory.
 */
enum class MemoryCategory {
    USER_PREFERENCES,
    TASK_HISTORY,
    VERIFIED_WORKFLOWS,
    MISTAKE_CORRECTIONS,
    CAPABILITY_KNOWLEDGE,
    AGENT_CONFIG
}

/**
 * Individual memory record with privacy protection and audit timestamps.
 */
data class MemoryRecord(
    val id: String,
    val category: MemoryCategory,
    val key: String,
    val value: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val lastAccessedEpochMs: Long = createdAtEpochMs,
    val accessCount: Int = 1,
    val userApproved: Boolean = true
)

/**
 * A verified successful workflow sequence extracted through workflow learning.
 */
data class VerifiedWorkflow(
    val workflowId: String,
    val goalPattern: String,
    val actionNames: List<String>,
    val verifiedCount: Int = 1,
    val lastVerifiedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Persistent Agent Memory Store.
 * Provides modular persistence for user preferences, verified workflows,
 * mistake patterns, and configuration with bounded retention (100 items per category)
 * and clear deletion controls.
 */
class PersistentAgentMemory(
    context: Context? = null,
    private val prefsName: String = "era_agent_memory"
) {
    private val prefs: SharedPreferences? = context?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, MemoryRecord>()

    init {
        loadFromStorage()
    }

    @Synchronized
    fun put(
        category: MemoryCategory,
        key: String,
        value: String,
        userApproved: Boolean = true
    ): Boolean {
        val safeKey = normalizeKey(key) ?: return false
        val cleanValue = PrivacyFilter.sanitizeText(value).take(MAX_VALUE_LENGTH)
        val id = "mem_${category.name.lowercase()}_${hashKey(safeKey)}"
        val existing = memory[id]
        val record = if (existing != null) {
            existing.copy(
                value = cleanValue,
                lastAccessedEpochMs = System.currentTimeMillis(),
                accessCount = existing.accessCount + 1,
                userApproved = userApproved
            )
        } else {
            // Check retention limit per category
            val categoryRecords = memory.values.filter { it.category == category }
            if (categoryRecords.size >= MAX_RECORDS_PER_CATEGORY) {
                // Prune least recently accessed item in this category
                categoryRecords.minByOrNull { it.lastAccessedEpochMs }?.let { memory.remove(it.id) }
            }
            MemoryRecord(
                id = id,
                category = category,
                key = safeKey,
                value = cleanValue,
                userApproved = userApproved
            )
        }
        memory[id] = record
        persistToStorage()
        return true
    }

    @Synchronized
    fun get(category: MemoryCategory, key: String): String? {
        val safeKey = normalizeKey(key) ?: return null
        val id = "mem_${category.name.lowercase()}_${hashKey(safeKey)}"
        val record = memory[id] ?: return null
        val updated = record.copy(
            lastAccessedEpochMs = System.currentTimeMillis(),
            accessCount = record.accessCount + 1
        )
        memory[id] = updated
        return updated.value
    }

    @Synchronized
    fun getCategoryRecords(category: MemoryCategory): List<MemoryRecord> =
        memory.values.filter { it.category == category }.sortedByDescending { it.lastAccessedEpochMs }

    @Synchronized
    fun delete(category: MemoryCategory, key: String): Boolean {
        val safeKey = normalizeKey(key) ?: return false
        val id = "mem_${category.name.lowercase()}_${hashKey(safeKey)}"
        val removed = memory.remove(id) != null
        if (removed) persistToStorage()
        return removed
    }

    @Synchronized
    fun clearCategory(category: MemoryCategory): Int {
        val toRemove = memory.values.filter { it.category == category }.map { it.id }
        toRemove.forEach { memory.remove(it) }
        persistToStorage()
        return toRemove.size
    }

    @Synchronized
    fun clearAll() {
        memory.clear()
        prefs?.edit()?.clear()?.commit()
    }

    private fun normalizeKey(key: String): String? {
        if (key.isBlank() || key.length > MAX_KEY_LENGTH || PrivacyFilter.isSensitiveKey(key)) return null
        val clean = PrivacyFilter.sanitizeText(key).trim()
        if (clean.isBlank() || clean.length > MAX_KEY_LENGTH) return null
        return clean
    }

    private fun hashKey(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }

    private fun loadFromStorage() {
        val raw = prefs?.getString(KEY_STORE, null) ?: return
        var sanitizedExistingData = false
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val category = MemoryCategory.valueOf(obj.getString("category"))
                val originalKey = obj.getString("key")
                val safeKey = normalizeKey(originalKey)
                if (safeKey == null) {
                    sanitizedExistingData = true
                    continue
                }
                val originalValue = obj.getString("value")
                val safeValue = PrivacyFilter.sanitizeText(originalValue).take(MAX_VALUE_LENGTH)
                if (safeKey != originalKey || safeValue != originalValue) sanitizedExistingData = true
                val recordId = "mem_${category.name.lowercase()}_${hashKey(safeKey)}"
                val record = MemoryRecord(
                    id = recordId,
                    category = category,
                    key = safeKey,
                    value = safeValue,
                    createdAtEpochMs = obj.getLong("createdAtEpochMs"),
                    lastAccessedEpochMs = obj.getLong("lastAccessedEpochMs"),
                    accessCount = obj.getInt("accessCount").coerceAtLeast(0),
                    userApproved = obj.optBoolean("userApproved", true)
                )
                memory[record.id] = record
            }
        }
        if (sanitizedExistingData) persistToStorage()
    }

    private fun persistToStorage() {
        if (prefs == null) return
        val array = JSONArray()
        memory.values.forEach { r ->
            array.put(JSONObject().apply {
                put("id", r.id)
                put("category", r.category.name)
                put("key", r.key)
                put("value", r.value)
                put("createdAtEpochMs", r.createdAtEpochMs)
                put("lastAccessedEpochMs", r.lastAccessedEpochMs)
                put("accessCount", r.accessCount)
                put("userApproved", r.userApproved)
            })
        }
        prefs.edit().putString(KEY_STORE, array.toString()).commit()
    }

    companion object {
        private const val KEY_STORE = "records_v1"
        const val MAX_RECORDS_PER_CATEGORY = 100
        private const val MAX_KEY_LENGTH = 120
        private const val MAX_VALUE_LENGTH = 4_000
    }
}

/**
 * Workflow Learning Engine:
 * Identifies reusable, verified action sequences from completed tasks.
 * Stores verified workflow templates in memory without private values.
 */
class WorkflowLearningEngine(
    private val memory: PersistentAgentMemory
) {
    /**
     * Learns from a completed task state machine.
     * Only extracts workflows when the task reached SUCCEEDED.
     */
    fun learnFromTask(stateMachine: TaskStateMachine): VerifiedWorkflow? {
        if (stateMachine.getState() != ExecutionState.SUCCEEDED) return null

        val actions = stateMachine.allActionProgress()
            .filter { it.state == ExecutionState.SUCCEEDED || it.state == ExecutionState.VERIFIED }
            .map { it.name }

        if (actions.isEmpty()) return null

        val goal = PrivacyFilter.sanitizeText(stateMachine.goal.trim()).take(160)
        if (goal.isBlank()) return null
        val keyHash = MessageDigest.getInstance("SHA-256")
            .digest(goal.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        val key = "wf_$keyHash"
        val existingJson = memory.get(MemoryCategory.VERIFIED_WORKFLOWS, key)

        val workflow = if (existingJson != null) {
            val obj = JSONObject(existingJson)
            VerifiedWorkflow(
                workflowId = obj.getString("workflowId"),
                goalPattern = obj.getString("goalPattern"),
                actionNames = actions,
                verifiedCount = obj.getInt("verifiedCount") + 1,
                lastVerifiedAtEpochMs = System.currentTimeMillis()
            )
        } else {
            VerifiedWorkflow(
                // Stable non-numeric identifier: generic numeric-card redaction must not alter JSON IDs.
                workflowId = "wf_$keyHash",
                goalPattern = goal,
                actionNames = actions,
                verifiedCount = 1,
                lastVerifiedAtEpochMs = System.currentTimeMillis()
            )
        }

        val json = JSONObject().apply {
            put("workflowId", workflow.workflowId)
            put("goalPattern", workflow.goalPattern)
            put("actionNames", JSONArray(workflow.actionNames))
            put("verifiedCount", workflow.verifiedCount)
            // Store seconds: the general numeric-card filter must not mistake a 13-digit epoch millisecond value for a payment card.
            put("lastVerifiedAtEpochMs", workflow.lastVerifiedAtEpochMs / 1_000L)
        }.toString()

        memory.put(MemoryCategory.VERIFIED_WORKFLOWS, key, json)
        return workflow
    }

    /**
     * Finds a learned verified workflow matching a goal.
     */
    fun findVerifiedWorkflow(goal: String): VerifiedWorkflow? {
        val records = memory.getCategoryRecords(MemoryCategory.VERIFIED_WORKFLOWS)
        val lowerGoal = PrivacyFilter.sanitizeText(goal.trim()).take(160).lowercase()
        if (lowerGoal.isBlank()) return null
        for (record in records) {
            val obj = runCatching { JSONObject(record.value) }.getOrNull() ?: continue
            val pattern = obj.optString("goalPattern").lowercase()
            if (lowerGoal.contains(pattern) || pattern.contains(lowerGoal)) {
                val actionArray = obj.getJSONArray("actionNames")
                val actions = (0 until actionArray.length()).map { actionArray.getString(it) }
                return VerifiedWorkflow(
                    workflowId = obj.getString("workflowId"),
                    goalPattern = obj.getString("goalPattern"),
                    actionNames = actions,
                    verifiedCount = obj.getInt("verifiedCount"),
                    lastVerifiedAtEpochMs = obj.getLong("lastVerifiedAtEpochMs") * 1_000L * 1_000L
                )
            }
        }
        return null
    }
}
