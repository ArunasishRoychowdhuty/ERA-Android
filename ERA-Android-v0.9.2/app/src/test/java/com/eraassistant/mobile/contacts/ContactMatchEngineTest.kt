package com.eraassistant.mobile.contacts

import org.junit.Assert.*
import org.junit.Test

class ContactMatchEngineTest {
    private val contacts = listOf(
        ContactCandidate("1", "Payel Biswas", listOf("+91 98765 43210"), setOf("Payel", "friend")),
        ContactCandidate("2", "Payel Das", listOf("+91 98765 40000")),
        ContactCandidate("3", "Arun Roy", listOf("9000011111"), setOf("home"))
    )

    @Test fun exactNameWinsBeforePartialMatches() {
        val result = ContactMatchEngine.resolve("Payel Biswas", contacts)
        assertTrue(result is ContactMatchResult.Unique)
        assertEquals("1", (result as ContactMatchResult.Unique).contact.id)
        assertEquals(MatchKind.EXACT_NAME, result.matchedBy)
    }
    @Test fun partialNameReturnsAmbiguousRatherThanFirstMatch() {
        val result = ContactMatchEngine.resolve("Payel", contacts)
        assertTrue(result is ContactMatchResult.Multiple)
        assertEquals(2, (result as ContactMatchResult.Multiple).contacts.size)
    }
    @Test fun aliasResolvesUniqueContact() {
        val result = ContactMatchEngine.resolve("friend", contacts)
        assertTrue(result is ContactMatchResult.Unique)
        assertEquals("1", (result as ContactMatchResult.Unique).contact.id)
        assertEquals(MatchKind.ALIAS, result.matchedBy)
    }
    @Test fun BengaliDigitPartialPhoneMatchesUniqueContact() {
        val result = ContactMatchEngine.resolve("৩২১০", contacts)
        assertTrue(result is ContactMatchResult.Unique)
        assertEquals("1", (result as ContactMatchResult.Unique).contact.id)
        assertEquals(MatchKind.PHONE_DIGITS, result.matchedBy)
    }
    @Test fun unknownAndBlankQueriesDoNotMatch() {
        assertEquals(ContactMatchResult.NoMatch, ContactMatchEngine.resolve("", contacts))
        assertEquals(ContactMatchResult.NoMatch, ContactMatchEngine.resolve("Nobody", contacts))
    }
}
