package com.eraassistant.mobile.search

/** Pure query builder for the user-triggered external Shopping search handoff. */
object ProductSearchQuery {
    fun normalize(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY_CHARS)

    fun shoppingUrl(raw: String): String? {
        val query = normalize(raw)
        if (query.isBlank()) return null
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        return "https://www.google.com/search?tbm=shop&q=$encoded"
    }

    private const val MAX_QUERY_CHARS = 180
}
