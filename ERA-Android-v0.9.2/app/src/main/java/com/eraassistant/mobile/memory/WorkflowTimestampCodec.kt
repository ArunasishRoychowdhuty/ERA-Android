package com.eraassistant.mobile.memory

/** Versioned codec for workflow verification timestamps. */
object WorkflowTimestampCodec {
    const val CURRENT_SCHEMA_VERSION = 2

    fun encodeEpochMs(epochMs: Long): Long {
        require(epochMs > 0L) { "Workflow timestamp must be a positive epoch millisecond value." }
        return epochMs
    }

    /** Legacy v1 records stored seconds in a field named EpochMs; migrate them by x1000. */
    fun decodeEpochMs(storedValue: Long, schemaVersion: Int): Long {
        require(storedValue > 0L) { "Stored workflow timestamp must be positive." }
        return if (schemaVersion >= CURRENT_SCHEMA_VERSION) {
            storedValue
        } else {
            Math.multiplyExact(storedValue, 1_000L)
        }
    }
}
