package com.eraassistant.mobile.tasks

/** Persistence boundary for resumable task state. Screen snapshots and credentials are not stored here. */
interface TaskLedgerPersistence {
    fun load(namespace: String): List<TaskStepRecord>?
    /** Return true only when the updated record set has been durably committed. */
    fun save(namespace: String, records: List<TaskStepRecord>): Boolean
    fun clear(namespace: String): Boolean
}

class InMemoryTaskLedgerPersistence : TaskLedgerPersistence {
    private val values = mutableMapOf<String, List<TaskStepRecord>>()
    override fun load(namespace: String): List<TaskStepRecord>? = synchronized(values) { values[namespace]?.toList() }
    override fun save(namespace: String, records: List<TaskStepRecord>): Boolean = synchronized(values) {
        values[namespace] = records.toList()
        true
    }
    override fun clear(namespace: String): Boolean = synchronized(values) {
        values.remove(namespace)
        true
    }
}
