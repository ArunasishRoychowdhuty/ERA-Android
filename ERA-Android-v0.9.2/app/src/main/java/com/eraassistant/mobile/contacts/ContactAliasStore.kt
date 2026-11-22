package com.eraassistant.mobile.contacts

import android.content.Context
import org.json.JSONArray

/** Stores user-authored local aliases keyed to a contact ID. No contact data is uploaded. */
class ContactAliasStore(context: Context) {
    private val prefs = context.getSharedPreferences("era_contact_aliases", Context.MODE_PRIVATE)

    fun aliasesFor(contactId: String): Set<String> {
        if (contactId.isBlank()) return emptySet()
        val raw = prefs.getString(contactId, null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotBlank).toSet()
        }.getOrDefault(emptySet())
    }

    fun addAlias(contactId: String, alias: String): Boolean {
        val cleanId = contactId.trim()
        val cleanAlias = alias.trim().take(MAX_ALIAS_LENGTH)
        if (cleanId.isBlank() || cleanAlias.isBlank()) return false
        val updated = (aliasesFor(cleanId) + cleanAlias).distinctBy { it.lowercase() }
        prefs.edit().putString(cleanId, JSONArray(updated).toString()).apply()
        return true
    }

    companion object { private const val MAX_ALIAS_LENGTH = 60 }
}
