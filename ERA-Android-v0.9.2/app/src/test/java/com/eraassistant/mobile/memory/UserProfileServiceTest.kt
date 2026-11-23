package com.eraassistant.mobile.memory

import org.junit.Assert.*
import org.junit.Test

class UserProfileServiceTest {
    @Test fun profileRequiresExplicitConsent() {
        val service = UserProfileService(
            DomainMemoryService(PersistentAgentMemory(context = null))
        ) { 1_700_000_000_000L }
        val result = service.save(
            UserProfile(displayName = "Arun", updatedAtEpochMs = 1L),
            explicitUserConsent = false
        )
        assertEquals(DomainMemoryDecision.CONSENT_REQUIRED, result.decision)
        assertNull(service.get())
    }

    @Test fun profileBundleRoundTripsAndCanBeUpdatedAndDeleted() {
        var now = 1_700_000_000_000L
        val service = UserProfileService(
            DomainMemoryService(PersistentAgentMemory(context = null)) { now },
            clock = { now }
        )
        val first = service.save(
            UserProfile(
                displayName = "Arun",
                preferredName = "Aru",
                language = "bn-IN",
                timezone = "Asia/Kolkata",
                communicationStyle = "Direct and detailed",
                interests = listOf("Android", "AI", "Automation"),
                accessibilityPreference = "Large text",
                updatedAtEpochMs = 1L
            ),
            explicitUserConsent = true
        )
        assertEquals(DomainMemoryDecision.STORED, first.decision)
        val saved = service.get()!!
        assertEquals("Arun", saved.displayName)
        assertEquals("Aru", saved.preferredName)
        assertEquals("bn-IN", saved.language)
        assertEquals("Asia/Kolkata", saved.timezone)
        assertEquals(listOf("Android", "AI", "Automation"), saved.interests)
        assertEquals(now, saved.updatedAtEpochMs)

        now += 10_000L
        val second = service.save(
            UserProfile(
                preferredName = "Arun",
                language = "en-IN",
                interests = listOf("Kotlin"),
                updatedAtEpochMs = 1L
            ),
            explicitUserConsent = true
        )
        assertEquals(DomainMemoryDecision.STORED, second.decision)
        assertEquals("Arun", service.get()?.preferredName)
        assertEquals("en-IN", service.get()?.language)
        assertEquals(listOf("Kotlin"), service.get()?.interests)
        assertNull(service.get()?.displayName)
        assertTrue(service.delete())
        assertNull(service.get())
    }

    @Test fun oversizedFieldsAndEmptyBundleAreRejected() {
        val service = UserProfileService(
            DomainMemoryService(PersistentAgentMemory(context = null))
        ) { 1_700_000_000_000L }
        val oversized = service.save(
            UserProfile(displayName = "x".repeat(61), updatedAtEpochMs = 1L),
            explicitUserConsent = true
        )
        assertEquals(DomainMemoryDecision.INVALID_INPUT, oversized.decision)

        val empty = service.save(
            UserProfile(updatedAtEpochMs = 1L),
            explicitUserConsent = true
        )
        assertEquals(DomainMemoryDecision.INVALID_INPUT, empty.decision)
        assertNull(service.get())
    }

    @Test fun tooManyInterestsAndCredentialLikeValuesAreRejected() {
        val service = UserProfileService(
            DomainMemoryService(PersistentAgentMemory(context = null))
        ) { 1_700_000_000_000L }
        val interests = service.save(
            UserProfile(interests = (1..9).map { "Interest $it" }, updatedAtEpochMs = 1L),
            explicitUserConsent = true
        )
        assertEquals(DomainMemoryDecision.INVALID_INPUT, interests.decision)

        val secret = service.save(
            UserProfile(preferredName = "password: secret-value", updatedAtEpochMs = 1L),
            explicitUserConsent = true
        )
        assertEquals(DomainMemoryDecision.SENSITIVE_DATA_BLOCKED, secret.decision)
        assertNull(service.get())
    }

    @Test fun retentionLimitIsDelegatedToDomainMemoryPolicy() {
        val service = UserProfileService(
            DomainMemoryService(PersistentAgentMemory(context = null))
        ) { 1_700_000_000_000L }
        val result = service.save(
            UserProfile(displayName = "Arun", updatedAtEpochMs = 1L),
            explicitUserConsent = true,
            retentionDays = 400
        )
        assertEquals(DomainMemoryDecision.INVALID_INPUT, result.decision)
    }
}
