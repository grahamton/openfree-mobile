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
        val prefs = androidCtx.getSharedPreferences(OpenFreeIME.PREFS_NAME, Context.MODE_PRIVATE)
        return DictionaryStore.addMapping(prefs, wrong, correct)
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
        val prefs = androidCtx.getSharedPreferences(OpenFreeIME.PREFS_NAME, Context.MODE_PRIVATE)
        return DictionaryStore.getMappings(prefs).map { "${it.key}->${it.value}" }
    }
}
