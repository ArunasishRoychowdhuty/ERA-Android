package com.eraassistant.mobile.contacts

/** Contact fields are supplied only by a user-selected or separately consented source. */
data class ContactCandidate(
    val id: String,
    val displayName: String,
    val phoneNumbers: List<String>,
    val aliases: Set<String> = emptySet()
)

sealed interface ContactMatchResult {
    data object NoMatch : ContactMatchResult
    data class Unique(val contact: ContactCandidate, val matchedBy: MatchKind) : ContactMatchResult
    data class Multiple(val contacts: List<ContactCandidate>, val matchedBy: MatchKind) : ContactMatchResult
}

enum class MatchKind { EXACT_NAME, ALIAS, PARTIAL_NAME, PHONE_DIGITS }

/** Deterministic matching. It never accesses Contacts or silently picks an ambiguous result. */
object ContactMatchEngine {
    fun resolve(query: String, contacts: List<ContactCandidate>): ContactMatchResult {
        val clean = normalizeText(query)
        if (clean.isBlank()) return ContactMatchResult.NoMatch
        val digitQuery = normalizeDigits(query).filter(Char::isDigit)
        if (digitQuery.length >= MIN_PHONE_QUERY_DIGITS) {
            val phoneMatches = contacts.filter { candidate ->
                candidate.phoneNumbers.any { raw ->
                    val digits = normalizeDigits(raw).filter(Char::isDigit)
                    digits == digitQuery || digits.endsWith(digitQuery)
                }
            }
            if (phoneMatches.isNotEmpty()) return ranked(phoneMatches, MatchKind.PHONE_DIGITS)
        }
        val exact = contacts.filter { normalizeText(it.displayName) == clean }
        if (exact.isNotEmpty()) return ranked(exact, MatchKind.EXACT_NAME)
        val aliases = contacts.filter { c -> c.aliases.any { normalizeText(it) == clean } }
        val partial = contacts.filter { c ->
            normalizeText(c.displayName).contains(clean) ||
                c.aliases.any { normalizeText(it).contains(clean) }
        }
        // An alias is not a safe exact match if the same short phrase also
        // partially matches another contact's name. Keep every candidate.
        val combined = (aliases + partial).distinctBy { it.id }
        if (combined.isEmpty()) return ContactMatchResult.NoMatch
        val kind = if (aliases.isNotEmpty() && combined.size == aliases.distinctBy { it.id }.size) {
            MatchKind.ALIAS
        } else {
            MatchKind.PARTIAL_NAME
        }
        return ranked(combined, kind)
    }

    private fun ranked(matches: List<ContactCandidate>, kind: MatchKind): ContactMatchResult {
        val uniqueById = matches.distinctBy { it.id }
        return if (uniqueById.size == 1) ContactMatchResult.Unique(uniqueById.single(), kind)
        else ContactMatchResult.Multiple(uniqueById, kind)
    }

    fun normalizeDigits(value: String): String = value.map { char ->
        val index = BENGALI_DIGITS.indexOf(char)
        if (index >= 0) ('0' + index) else {
            val extendedIndex = EXTENDED_ARABIC_DIGITS.indexOf(char)
            if (extendedIndex >= 0) ('0' + extendedIndex) else char
        }
    }.joinToString("")

    fun normalizeText(value: String): String = value.trim().lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private const val MIN_PHONE_QUERY_DIGITS = 4
    private const val BENGALI_DIGITS = "০১২৩৪৫৬৭৮৯"
    private const val EXTENDED_ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"
}
