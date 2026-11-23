package com.eraassistant.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class DomainIntentClassifierTest {
    @Test fun detectsHomeAndPersonalProfileAsSeparateDomains() {
        val result = DomainIntentClassifier.classify("বাসার ম্যানেজমেন্ট এবং আমার প্রোফাইলের পছন্দ মনে রাখ")
        assertTrue(AgentDomain.HOME_MANAGEMENT in result.domains)
        assertTrue(AgentDomain.PERSONAL_PROFILE in result.domains)
        assertTrue(result.requiresPersonalContext)
        assertTrue("PERSONAL_MEMORY_REQUIRES_CONSENT" in result.constraints)
        assertTrue("HOUSEHOLD_DATA_REQUIRES_SCOPE" in result.constraints)
    }

    @Test fun detectsCriticalPaymentRisk() {
        val result = DomainIntentClassifier.classify("Pay bill from my bank account")
        assertEquals(GoalRisk.CRITICAL, result.risk)
        assertTrue(result.requiresExplicitApproval)
    }

    @Test fun unknownRequestHasLowConfidenceRatherThanInventingDomain() {
        val result = DomainIntentClassifier.classify("make it work")
        assertEquals(setOf(AgentDomain.UNKNOWN), result.domains)
        assertTrue(result.confidence < 0.5)
    }
}
