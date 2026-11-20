package com.eraassistant.mobile.history

import org.junit.Assert.*
import org.junit.Test

class CommandHistoryCodecTest {
    @Test fun roundTripsStructuredHistory() {
        val record = CommandHistoryCodec.create("open Chrome", "Chrome opened", 1234L)
        val decoded = CommandHistoryCodec.decode(CommandHistoryCodec.encode(listOf(record)))
        assertEquals(1, decoded.size)
        assertEquals(record.command, decoded.single().command)
        assertEquals(record.result, decoded.single().result)
        assertEquals(1234L, decoded.single().timestampEpochMs)
    }

    @Test fun migratesLegacyStringArray() {
        val decoded = CommandHistoryCodec.decode("""["open Chrome → Chrome opened","task → failed to open"]""")
        assertEquals(2, decoded.size)
        assertEquals("open Chrome", decoded[0].command)
        assertEquals(HistoryOutcome.SUCCESS, decoded[0].outcome)
        assertEquals(HistoryOutcome.FAILED, decoded[1].outcome)
    }

    @Test fun redactsPhoneEmailAndApiCredentials() {
        val safe = HistorySanitizer.redact("call +91 98765 43210; mail a@b.com; key sk-abcdefghijklmnop")
        assertFalse(safe.contains("98765"))
        assertFalse(safe.contains("a@b.com"))
        assertFalse(safe.contains("sk-abcdefghijklmnop"))
        assertTrue(safe.contains("[PHONE]"))
        assertTrue(safe.contains("[EMAIL]"))
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test fun classifiesCancelledAndInformationalEvents() {
        assertEquals(HistoryOutcome.CANCELLED, CommandHistoryCodec.classify("Task cancelled by user"))
        assertEquals(HistoryOutcome.INFO, CommandHistoryCodec.classify("Task plan preview created"))
    }
}
