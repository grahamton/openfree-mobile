package com.openfree.client

import android.content.Context
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.service.AppFunction

class DictionaryAppFunctions {

    /**
     * Adds a new spelling correction mapping to the keyboard's dictionary.
     *
     * @param context The execution context.
     * @param wrong The incorrect spelling or typo to match.
     * @param correct The correct word or phrase to output.
     * @return True if the correction mapping was successfully saved.
     */
    @AppFunction
    suspend fun addDictionaryCorrection(
        context: AppFunctionContext,
        wrong: String,
        correct: String
    ): Boolean {
        val androidCtx = context.context
        val prefs = androidCtx.getSharedPreferences("openfree_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("pref_key_dictionary_mappings", "") ?: ""
        
        val current = if (raw.isBlank()) {
            mutableMapOf()
        } else {
            raw.split(";").mapNotNull {
                val parts = it.split("->")
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap().toMutableMap()
        }
        
        current[wrong.trim().lowercase()] = correct.trim()
        val updatedRaw = current.map { "${it.key}->${it.value}" }.joinToString(";")
        return prefs.edit().putString("pref_key_dictionary_mappings", updatedRaw).commit()
    }

    /**
     * Retrieves all active dictionary spelling corrections as a list of "typo->correction" mappings.
     *
     * @param context The execution context.
     * @return The list of dictionary correction mappings.
     */
    @AppFunction
    suspend fun getDictionaryCorrections(
        context: AppFunctionContext
    ): List<String> {
        val androidCtx = context.context
        val prefs = androidCtx.getSharedPreferences("openfree_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("pref_key_dictionary_mappings", "") ?: ""
        
        if (raw.isBlank()) {
            return emptyList()
        }
        return raw.split(";").filter { it.contains("->") }
    }
}
