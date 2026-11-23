package com.eraassistant.mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowTimestampCodecTest {
    @Test fun currentSchemaRoundTripsEpochMilliseconds() {
        val timestamp = 1_795_000_123_456L
        assertEquals(timestamp, WorkflowTimestampCodec.decodeEpochMs(
            WorkflowTimestampCodec.encodeEpochMs(timestamp),
            WorkflowTimestampCodec.CURRENT_SCHEMA_VERSION
        ))
    }

    @Test fun legacySecondsAreMigratedToMillisecondsExactlyOnce() {
        val legacySeconds = 1_795_000_123L
        assertEquals(legacySeconds * 1_000L, WorkflowTimestampCodec.decodeEpochMs(legacySeconds, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveTimestampIsRejected() {
        WorkflowTimestampCodec.decodeEpochMs(0L, 1)
    }
}
