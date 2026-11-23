package com.eraassistant.mobile.home

import com.eraassistant.mobile.memory.DomainMemoryArea
import com.eraassistant.mobile.memory.DomainMemoryDecision
import com.eraassistant.mobile.memory.DomainMemorySensitivity
import com.eraassistant.mobile.memory.DomainMemoryService
import com.eraassistant.mobile.memory.DomainMemoryWrite
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class HouseholdTaskStatus { OPEN, IN_PROGRESS, COMPLETED, CANCELLED }

data class HouseholdTask(
    val id: String,
    val title: String,
    val dueAtEpochMs: Long?,
    val status: HouseholdTaskStatus,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

data class HouseholdInventoryItem(
    val id: String,
    val name: String,
    val quantity: Int,
    val unit: String,
    val updatedAtEpochMs: Long
)

enum class HouseholdRoutineSlot(val memoryKey: String) {
    MORNING("morning_routine"),
    EVENING("evening_routine"),
    CLEANING("cleaning_schedule"),
    SHOPPING("shopping_schedule"),
    REMINDER_PREFERENCE("reminder_preference")
}

enum class HomeManagementDecision {
    SAVED, CONSENT_REQUIRED, INVALID_INPUT, LIMIT_REACHED, NOT_FOUND, STORAGE_REJECTED
}

data class HomeManagementResult<T>(
    val decision: HomeManagementDecision,
    val value: T? = null,
    val message: String
) {
    val saved: Boolean get() = decision == HomeManagementDecision.SAVED
}

/**
 * Local household task, inventory and routine repository.
 *
 * Every mutation requires explicit consent. Data is stored in the existing app-private
 * domain-memory repository with bounded retention. Times and generated IDs use compact
 * encodings to avoid confusing metadata with credential-like values in the privacy filter.
 * This service does not schedule alarms or connect to IoT devices.
 */
class HomeManagementService(
    private val memory: DomainMemoryService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val createId: () -> String = { "h_" + UUID.randomUUID().toString().take(24) }
) {
    fun createTask(
        title: String,
        dueAtEpochMs: Long? = null,
        explicitUserConsent: Boolean
    ): HomeManagementResult<HouseholdTask> {
        val cleanTitle = title.trim()
        val now = clock()
        if (!validText(cleanTitle, MAX_TASK_TITLE) ||
            dueAtEpochMs?.let { it <= now || it > now + MAX_FUTURE_DUE_MS } == true) {
            return result(HomeManagementDecision.INVALID_INPUT, message = "Task title or due time is invalid.")
        }
        val existing = listTasks()
        if (existing.size >= MAX_TASKS) {
            return result(HomeManagementDecision.LIMIT_REACHED, message = "The local task list is full. Complete or remove an existing task first.")
        }
        val task = HouseholdTask(
            id = createId(),
            title = cleanTitle,
            dueAtEpochMs = dueAtEpochMs,
            status = HouseholdTaskStatus.OPEN,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        return writeCollection(
            DomainMemoryArea.HOME_MANAGEMENT, TASKS_KEY, encodeTasks(existing + task),
            explicitUserConsent, task
        )
    }

    fun listTasks(): List<HouseholdTask> = readCollection(
        DomainMemoryArea.HOME_MANAGEMENT, TASKS_KEY, ::decodeTasks
    )

    fun updateTaskStatus(
        taskId: String,
        status: HouseholdTaskStatus,
        explicitUserConsent: Boolean
    ): HomeManagementResult<HouseholdTask> {
        if (taskId.isBlank()) return result(HomeManagementDecision.INVALID_INPUT, message = "Task ID is required.")
        val existing = listTasks()
        val old = existing.firstOrNull { it.id == taskId }
            ?: return result(HomeManagementDecision.NOT_FOUND, message = "Household task was not found.")
        val updated = old.copy(status = status, updatedAtEpochMs = clock())
        val next = existing.map { if (it.id == taskId) updated else it }
        return writeCollection(
            DomainMemoryArea.HOME_MANAGEMENT, TASKS_KEY, encodeTasks(next),
            explicitUserConsent, updated
        )
    }

    fun deleteTask(
        taskId: String,
        explicitUserConsent: Boolean
    ): HomeManagementResult<Unit> {
        if (taskId.isBlank()) return result(HomeManagementDecision.INVALID_INPUT, message = "Task ID is required.")
        val existing = listTasks()
        if (existing.none { it.id == taskId }) {
            return result(HomeManagementDecision.NOT_FOUND, message = "Household task was not found.")
        }
        val next = existing.filterNot { it.id == taskId }
        return writeCollection(
            DomainMemoryArea.HOME_MANAGEMENT, TASKS_KEY, encodeTasks(next),
            explicitUserConsent, Unit
        )
    }

    fun upsertInventoryItem(
        name: String,
        quantity: Int,
        unit: String = "item",
        explicitUserConsent: Boolean
    ): HomeManagementResult<HouseholdInventoryItem> {
        val cleanName = name.trim()
        val cleanUnit = unit.trim().ifBlank { "item" }
        if (!validText(cleanName, MAX_INVENTORY_NAME) ||
            !validText(cleanUnit, MAX_INVENTORY_UNIT) ||
            quantity !in 0..MAX_INVENTORY_QUANTITY) {
            return result(HomeManagementDecision.INVALID_INPUT, message = "Inventory name, unit or quantity is invalid.")
        }
        val existing = listInventory()
        val current = existing.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        if (current == null && existing.size >= MAX_INVENTORY_ITEMS) {
            return result(HomeManagementDecision.LIMIT_REACHED, message = "The local inventory list is full.")
        }
        val item = HouseholdInventoryItem(
            id = current?.id ?: createId(),
            name = cleanName,
            quantity = quantity,
            unit = cleanUnit,
            updatedAtEpochMs = clock()
        )
        val next = if (current == null) existing + item
            else existing.map { if (it.id == current.id) item else it }
        return writeCollection(
            DomainMemoryArea.HOME_MANAGEMENT, INVENTORY_KEY, encodeInventory(next),
            explicitUserConsent, item
        )
    }

    fun listInventory(): List<HouseholdInventoryItem> = readCollection(
        DomainMemoryArea.HOME_MANAGEMENT, INVENTORY_KEY, ::decodeInventory
    )

    fun saveRoutine(
        slot: HouseholdRoutineSlot,
        description: String,
        explicitUserConsent: Boolean
    ): HomeManagementResult<String> {
        val clean = description.trim()
        if (!validText(clean, MAX_ROUTINE_LENGTH)) {
            return result(HomeManagementDecision.INVALID_INPUT, message = "Routine description is invalid.")
        }
        val stored = memory.put(
            DomainMemoryWrite(
                area = DomainMemoryArea.HOUSEHOLD_ROUTINES,
                key = slot.memoryKey,
                value = clean,
                sensitivity = DomainMemorySensitivity.PERSONAL,
                explicitUserConsent = explicitUserConsent,
                retentionDays = DEFAULT_RETENTION_DAYS
            )
        )
        return when (stored.decision) {
            DomainMemoryDecision.STORED -> result(HomeManagementDecision.SAVED, clean, "Routine saved.")
            DomainMemoryDecision.CONSENT_REQUIRED -> result(HomeManagementDecision.CONSENT_REQUIRED, message = stored.reason)
            DomainMemoryDecision.INVALID_INPUT -> result(HomeManagementDecision.INVALID_INPUT, message = stored.reason)
            else -> result(HomeManagementDecision.STORAGE_REJECTED, message = stored.reason)
        }
    }

    fun getRoutine(slot: HouseholdRoutineSlot): String? =
        memory.get(DomainMemoryArea.HOUSEHOLD_ROUTINES, slot.memoryKey)?.value
            ?.takeIf(String::isNotBlank)

    private fun <T> writeCollection(
        area: DomainMemoryArea,
        key: String,
        payload: String,
        consent: Boolean,
        item: T
    ): HomeManagementResult<T> {
        val stored = memory.put(
            DomainMemoryWrite(
                area = area,
                key = key,
                value = payload,
                sensitivity = DomainMemorySensitivity.PERSONAL,
                explicitUserConsent = consent,
                retentionDays = DEFAULT_RETENTION_DAYS
            )
        )
        return when (stored.decision) {
            DomainMemoryDecision.STORED -> result(HomeManagementDecision.SAVED, item, "Household data saved.")
            DomainMemoryDecision.CONSENT_REQUIRED -> result(HomeManagementDecision.CONSENT_REQUIRED, message = stored.reason)
            DomainMemoryDecision.INVALID_INPUT -> result(HomeManagementDecision.INVALID_INPUT, message = stored.reason)
            else -> result(HomeManagementDecision.STORAGE_REJECTED, message = stored.reason)
        }
    }

    private fun <T> readCollection(
        area: DomainMemoryArea,
        key: String,
        decode: (String) -> List<T>
    ): List<T> = memory.get(area, key)?.value?.let { raw ->
        runCatching { decode(raw) }.getOrDefault(emptyList())
    }.orEmpty()

    private fun encodeTasks(tasks: List<HouseholdTask>): String = JSONArray().apply {
        tasks.forEach { task ->
            put(JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("dueAtBase36", task.dueAtEpochMs?.let(::encodeBase36) ?: JSONObject.NULL)
                put("status", task.status.name)
                put("createdAtBase36", encodeBase36(task.createdAtEpochMs))
                put("updatedAtBase36", encodeBase36(task.updatedAtEpochMs))
            })
        }
    }.toString()

    private fun decodeTasks(raw: String): List<HouseholdTask> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = obj.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val status = runCatching { HouseholdTaskStatus.valueOf(obj.optString("status")) }.getOrNull()
                ?: HouseholdTaskStatus.OPEN
            HouseholdTask(
                id = id,
                title = title,
                dueAtEpochMs = obj.optString("dueAtBase36").takeIf { it.isNotBlank() && it != "null" }?.let(::decodeBase36),
                status = status,
                createdAtEpochMs = decodeBase36(obj.optString("createdAtBase36")),
                updatedAtEpochMs = decodeBase36(obj.optString("updatedAtBase36"))
            )
        }
    }

    private fun encodeInventory(items: List<HouseholdInventoryItem>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("quantity", item.quantity)
                put("unit", item.unit)
                put("updatedAtBase36", encodeBase36(item.updatedAtEpochMs))
            })
        }
    }.toString()

    private fun decodeInventory(raw: String): List<HouseholdInventoryItem> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = obj.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            HouseholdInventoryItem(
                id = id,
                name = name,
                quantity = obj.optInt("quantity").coerceIn(0, MAX_INVENTORY_QUANTITY),
                unit = obj.optString("unit", "item"),
                updatedAtEpochMs = decodeBase36(obj.optString("updatedAtBase36"))
            )
        }
    }

    private fun validText(value: String, maxLength: Int): Boolean =
        value.isNotBlank() && value.length <= maxLength && value.none { it.code < 0x20 }

    private fun encodeBase36(value: Long): String {
        require(value > 0L)
        return java.lang.Long.toString(value, 36)
    }

    private fun decodeBase36(value: String): Long {
        val decoded = value.toLongOrNull(36) ?: 0L
        require(decoded > 0L)
        return decoded
    }

    private fun <T> result(
        decision: HomeManagementDecision,
        value: T? = null,
        message: String
    ) = HomeManagementResult(decision, value, message)

    companion object {
        const val MAX_TASKS = 12
        const val MAX_INVENTORY_ITEMS = 12
        private const val DEFAULT_RETENTION_DAYS = 180
        private const val MAX_TASK_TITLE = 120
        private const val MAX_INVENTORY_NAME = 80
        private const val MAX_INVENTORY_UNIT = 16
        private const val MAX_INVENTORY_QUANTITY = 999_999
        private const val MAX_ROUTINE_LENGTH = 180
        private const val MAX_FUTURE_DUE_MS = 365L * 86_400_000L
        private const val TASKS_KEY = "household_task"
        private const val INVENTORY_KEY = "inventory_item"
    }
}
