package com.eraassistant.mobile.tasks

/** Persistence boundary for resumable task state. Screen snapshots and credentials are not stored here. */
interface TaskLedgerPersistence {
    fun load(namespace: String): List<TaskStepRecord>?
    fun save(namespace: String, records: List<TaskStepRecord>)
    fun clear(namespace: String)
}

class InMemoryTaskLedgerPersistence : TaskLedgerPersistence {
    private val values = mutableMapOf<String, List<TaskStepRecord>>()
    override fun load(namespace: String): List<TaskStepRecord>? = values[namespace]?.toList()
    override fun save(namespace: String, records: List<TaskStepRecord>) { values[namespace] = records.toList() }
    override fun clear(namespace: String) { values.remove(namespace) }
}
