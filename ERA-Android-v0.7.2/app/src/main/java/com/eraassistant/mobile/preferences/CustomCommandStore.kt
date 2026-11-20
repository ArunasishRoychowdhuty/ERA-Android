package com.eraassistant.mobile.preferences

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CustomCommandAlias(val phrase: String, val mappedCommand: String)

/** Local-only user-defined phrase aliases. Aliases can map only to commands
 * already accepted by the deterministic CommandRouter allow-list. */
class CustomCommandStore(context: Context) {
    private val preferences = context.getSharedPreferences("era_custom_commands", Context.MODE_PRIVATE)

    fun load(): List<CustomCommandAlias> {
        val raw = preferences.getString(KEY_ALIASES, "[]") ?: "[]"
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val phrase = item.optString("phrase").trim()
                val mapped = item.optString("mapped").trim()
                if (phrase.isBlank() || mapped.isBlank()) null else CustomCommandAlias(phrase, mapped)
            }
        }.getOrDefault(emptyList())
    }

    fun save(alias: CustomCommandAlias): List<CustomCommandAlias> {
        val clean = alias.copy(phrase = alias.phrase.trim(), mappedCommand = alias.mappedCommand.trim())
        val updated = (load().filterNot { it.phrase.equals(clean.phrase, ignoreCase = true) } + clean).takeLast(30)
        persist(updated)
        return updated
    }

    fun delete(phrase: String): List<CustomCommandAlias> {
        val updated = load().filterNot { it.phrase.equals(phrase, ignoreCase = true) }
        persist(updated)
        return updated
    }

    private fun persist(aliases: List<CustomCommandAlias>) {
        val json = JSONArray().apply {
            aliases.forEach { alias ->
                put(JSONObject().put("phrase", alias.phrase).put("mapped", alias.mappedCommand))
            }
        }
        preferences.edit().putString(KEY_ALIASES, json.toString()).apply()
    }

    private companion object {
        const val KEY_ALIASES = "aliases"
    }
}
