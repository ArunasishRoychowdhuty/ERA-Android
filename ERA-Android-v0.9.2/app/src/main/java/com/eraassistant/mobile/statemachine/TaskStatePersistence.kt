package com.eraassistant.mobile.statemachine


/** Durable, bounded persistence for task-level and action-level lifecycle history. */
interface TaskStatePersistence {
    fun load(taskId: String): TaskStateSnapshot?
    /** Returns true only when the entire snapshot was committed. */
    fun save(snapshot: TaskStateSnapshot): Boolean
    fun clear(taskId: String): Boolean
}

class InMemoryTaskStatePersistence : TaskStatePersistence {
    private val values = mutableMapOf<String, TaskStateSnapshot>()

    override fun load(taskId: String): TaskStateSnapshot? =
        synchronized(values) { values[taskId] }

    override fun save(snapshot: TaskStateSnapshot): Boolean = synchronized(values) {
        values[snapshot.taskId] = snapshot
        true
    }

    override fun clear(taskId: String): Boolean = synchronized(values) {
        values.remove(taskId)
        true
    }
}
