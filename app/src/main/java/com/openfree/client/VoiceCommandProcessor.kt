package com.openfree.client

/**
 * VoiceCommandProcessor
 *
 * Single source of truth for spoken editing commands, shared by
 * [OpenFreeIME] and [FloatingOpenFreeService] (same pattern as
 * [DictionaryStore]). Pure logic — no Android dependencies — so the
 * behaviour is mirrored 1:1 by the Python mock-harness tests.
 *
 * Turns dictated text (after the dictionary corrections pass) into a
 * sequence of [TextOp]s, recognising command phrases as actions instead of
 * literal words:
 *
 *  - Punctuation words ("period", "comma", "question mark", …) become the
 *    punctuation character, attached to the preceding text without a space.
 *  - "new line" / "new paragraph" insert line breaks.
 *  - "delete that" / "scratch that" discard whatever was dictated so far in
 *    the current utterance, or — when the utterance starts with the command —
 *    emit [TextOp.DeleteLastCommit] so the surface removes the previously
 *    committed dictation.
 *
 * Matching is case-insensitive and ignores punctuation Whisper glues onto
 * command words ("New line." still matches "new line").
 *
 * Extending the command set: add one entry to [punctuationCommands]
 * (spoken phrase → literal text, appended without a leading space) or
 * [actionCommands] (spoken phrase → [TextOp]). Multi-word phrases are
 * supported and matched longest-first.
 */
object VoiceCommandProcessor {

    /** An edit operation for the dictation surface to execute in order. */
    sealed class TextOp {
        /** Commit literal text at the cursor. */
        data class InsertText(val text: String) : TextOp()

        /** Remove the text committed by the previous dictation utterance. */
        object DeleteLastCommit : TextOp()
    }

    // ── Command registry ───────────────────────────────────────────────────────

    /** Spoken phrase → literal appended directly after preceding text. */
    private val punctuationCommands: Map<List<String>, String> = mapOf(
        "period" to ".",
        "full stop" to ".",
        "comma" to ",",
        "question mark" to "?",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "colon" to ":",
        "semicolon" to ";",
        "new line" to "\n",
        "new paragraph" to "\n\n"
    ).mapKeys { (phrase, _) -> phrase.split(" ") }

    /** Spoken phrase → editing action. */
    private val actionCommands: Map<List<String>, TextOp> = mapOf(
        "delete that" to TextOp.DeleteLastCommit,
        "scratch that" to TextOp.DeleteLastCommit
    ).mapKeys { (phrase, _) -> phrase.split(" ") }

    private val maxPhraseLength: Int =
        (punctuationCommands.keys + actionCommands.keys).maxOf { it.size }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Parse one dictated utterance into an ordered list of [TextOp]s.
     * Returns an empty list when the utterance contains nothing to commit.
     */
    fun process(text: String): List<TextOp> {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val ops = mutableListOf<TextOp>()
        val builder = StringBuilder()

        var i = 0
        while (i < tokens.size) {
            val matchLen = matchAt(tokens, i) { phraseLen, normalized ->
                val punctuation = punctuationCommands[normalized]
                if (punctuation != null) {
                    appendLiteral(builder, punctuation)
                    return@matchAt true
                }
                val action = actionCommands[normalized]
                if (action != null) {
                    if (builder.isNotEmpty()) {
                        // Discard what was dictated so far in this utterance.
                        builder.clear()
                    } else {
                        ops.add(action)
                    }
                    return@matchAt true
                }
                false
            }
            if (matchLen > 0) {
                i += matchLen
            } else {
                appendWord(builder, tokens[i])
                i++
            }
        }

        if (builder.isNotBlank()) {
            ops.add(TextOp.InsertText(builder.toString()))
        }
        return ops
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    /**
     * Try command phrases starting at [start], longest first. [onCandidate]
     * receives the phrase length and normalized tokens and returns true when
     * it consumed a command. Returns the number of tokens consumed (0 = none).
     */
    private inline fun matchAt(
        tokens: List<String>,
        start: Int,
        onCandidate: (Int, List<String>) -> Boolean
    ): Int {
        for (len in minOf(maxPhraseLength, tokens.size - start) downTo 1) {
            val normalized = (0 until len).map { normalizeToken(tokens[start + it]) }
            if (normalized.any { it.isEmpty() }) continue
            if (onCandidate(len, normalized)) return len
        }
        return 0
    }

    /** Lowercase and strip surrounding punctuation (keeps inner apostrophes). */
    private fun normalizeToken(token: String): String =
        token.lowercase().trim { !it.isLetterOrDigit() }

    /** Append a punctuation/newline literal with no leading space. */
    private fun appendLiteral(builder: StringBuilder, literal: String) {
        builder.append(literal)
    }

    /** Append a literal word, space-separated unless at start or after a newline. */
    private fun appendWord(builder: StringBuilder, word: String) {
        val punctuationOnly = normalizeToken(word).isEmpty()
        if (builder.isNotEmpty() && !builder.endsWith("\n") && !punctuationOnly) {
            builder.append(' ')
        }
        builder.append(word)
    }
}
