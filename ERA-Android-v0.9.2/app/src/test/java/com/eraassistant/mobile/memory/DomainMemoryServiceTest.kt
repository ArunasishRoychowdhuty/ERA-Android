package com.eraassistant.mobile.memory

import org.junit.Assert.*
import org.junit.Test

class DomainMemoryServiceTest {
    @Test fun profileRequiresConsentAndPersistsOnlyAllowlistedFields() {
        val memory = PersistentAgentMemory(context = null)
        val service = DomainMemoryService(memory) { 1_000L }
        val noConsent = service.put(DomainMemoryWrite(
            DomainMemoryArea.USER_PROFILE, "preferred_name", "Arun", DomainMemorySensitivity.PERSONAL, false
        ))
        assertEquals(DomainMemoryDecision.CONSENT_REQUIRED, noConsent.decision)

        val saved = service.put(DomainMemoryWrite(
            DomainMemoryArea.USER_PROFILE, "preferred_name", "Arun", DomainMemorySensitivity.PERSONAL, true, 30
        ))
        assertEquals(DomainMemoryDecision.STORED, saved.decision)
        assertEquals("Arun", service.get(DomainMemoryArea.USER_PROFILE, "preferred_name")?.value)
    }

    @Test fun secretsAndPrecisePersonalFieldsAreRejected() {
        val service = DomainMemoryService(PersistentAgentMemory(context = null))
        val secret = service.put(DomainMemoryWrite(
            DomainMemoryArea.USER_PROFILE, "preferred_name", "password: hello123",
            DomainMemorySensitivity.PERSONAL, true
        ))
        assertEquals(DomainMemoryDecision.SENSITIVE_DATA_BLOCKED, secret.decision)

        val address = service.put(DomainMemoryWrite(
            DomainMemoryArea.USER_PROFILE, "address", "exact home address",
            DomainMemorySensitivity.PERSONAL, true
        ))
        assertEquals(DomainMemoryDecision.FIELD_NOT_ALLOWED, address.decision)
    }

    @Test fun expiredEntriesAreDeletedOnRead() {
        var now = 100L
        val memory = PersistentAgentMemory(context = null)
        val service = DomainMemoryService(memory) { now }
        assertEquals(DomainMemoryDecision.STORED, service.put(DomainMemoryWrite(
            DomainMemoryArea.HOME_MANAGEMENT, "household_task", "water plants",
            DomainMemorySensitivity.PERSONAL, true, retentionDays = 1
        )).decision)
        now += 2L * 86_400_000L
        assertNull(service.get(DomainMemoryArea.HOME_MANAGEMENT, "household_task"))
    }
}
