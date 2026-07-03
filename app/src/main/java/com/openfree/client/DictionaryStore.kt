package com.openfree.client

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * DictionaryStore
 *
 * Single source of truth for the corrections dictionary, shared by
 * [OpenFreeIME], [FloatingOpenFreeService], [SettingsActivity], and
 * [DictionaryAppFunctions].
 *
 * Mappings are persisted as a JSON object under
 * [OpenFreeIME.KEY_DICTIONARY_MAPPINGS], so entries may safely contain any
 * characters (including ";" and "->", which corrupted the legacy delimited
 * format). Legacy `wrong->correct;wrong->correct` values are migrated to JSON
 * transparently on first read.
 */
object DictionaryStore {

    /** All active corrections, keyed by the lowercase "wrong" form. */
    fun getMappings(prefs: SharedPreferences): Map<String, String> {
        val raw = prefs.getString(OpenFreeIME.KEY_DICTIONARY_MAPPINGS, "") ?: ""
        if (raw.isBlank()) return emptyMap()

        parseJson(raw)?.let { return it }

        val legacy = parseLegacy(raw)
        if (legacy.isNotEmpty()) {
            saveMappings(prefs, legacy) // migrate to JSON so the fragile format never round-trips again
        }
        return legacy
    }

    /**
     * Add (or replace) a correction. The wrong form is normalised to
     * trimmed lowercase; the correct form is stored trimmed verbatim.
     *
     * @return true if the mapping was persisted.
     */
    fun addMapping(prefs: SharedPreferences, wrong: String, correct: String): Boolean {
        val key = wrong.trim().lowercase()
        val value = correct.trim()
        if (key.isEmpty() || value.isEmpty()) return false
        val updated = getMappings(prefs).toMutableMap()
        updated[key] = value
        return saveMappings(prefs, updated)
    }

    /** Remove every stored correction. */
    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(OpenFreeIME.KEY_DICTIONARY_MAPPINGS).apply()
    }

    /** Apply all corrections to [text] using case-insensitive whole-word matching. */
    fun applyCorrections(prefs: SharedPreferences, text: String): String {
        var result = text
        for ((wrong, correct) in getMappings(prefs)) {
            val regex = "(?i)\\b${Pattern.quote(wrong)}\\b".toRegex()
            result = result.replace(regex, correct)
        }
        return result
    }

    private fun saveMappings(prefs: SharedPreferences, mappings: Map<String, String>): Boolean {
        val json = JSONObject()
        for ((wrong, correct) in mappings) {
            json.put(wrong, correct)
        }
        return prefs.edit()
            .putString(OpenFreeIME.KEY_DICTIONARY_MAPPINGS, json.toString())
            .commit()
    }

    private fun parseJson(raw: String): Map<String, String>? {
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            for (key in json.keys()) {
                result[key] = json.getString(key)
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLegacy(raw: String): Map<String, String> {
        return raw.split(";").mapNotNull {
            val parts = it.split("->")
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }
}
