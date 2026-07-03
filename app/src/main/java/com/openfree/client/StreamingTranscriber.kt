package com.openfree.client

import android.content.SharedPreferences
import android.util.Log

/**
 * StreamingTranscriber
 *
 * Shared dictation-session orchestrator used by both [OpenFreeIME] and
 * [FloatingOpenFreeService] (same pattern as [DictionaryStore]). Owns the
 * M9 live-feedback pipeline for one recording session:
 *
 *  - Live chunks from [AudioRecorder.onChunk] are buffered and fed through
 *    a [VoiceActivityDetector]; when the configured silence timeout elapses
 *    the surface is told to auto-stop via [Listener.onSilenceTimeout].
 *  - While recording, a single worker thread periodically transcribes the
 *    most recent audio window and reports partial text via
 *    [Listener.onPartial], so the user sees words appear while talking.
 *  - [finishSession] stops the worker, trims leading/trailing silence, and
 *    runs the final full-context Whisper pass over the whole recording.
 *
 * All [WhisperEngine] access for the session happens on the worker thread
 * or after it has been joined, so partial and final passes never run
 * concurrently (the engine is not thread-safe).
 *
 * Threading: [feedChunk] is called on the audio thread; [finishSession] and
 * [cancelSession] must be called from a background thread — they join the
 * partial worker, which may be mid-inference.
 */
class StreamingTranscriber(private val whisperEngine: WhisperEngine) {

    /** Per-session tuning, read from the shared preferences of both surfaces. */
    data class Config(
        /** Show partial transcriptions while recording. */
        val partialsEnabled: Boolean,
        /** Auto-stop after this much silence following speech; <= 0 disables. */
        val silenceTimeoutMs: Long,
        /** Interpret spoken editing commands via [VoiceCommandProcessor]. */
        val voiceCommandsEnabled: Boolean,
        /** Minimum delay between partial passes. */
        val partialIntervalMs: Long = DEFAULT_PARTIAL_INTERVAL_MS
    ) {
        companion object {
            fun fromPrefs(prefs: SharedPreferences): Config = Config(
                partialsEnabled = prefs.getBoolean(OpenFreeIME.KEY_LIVE_PREVIEW, true),
                silenceTimeoutMs =
                    prefs.getInt(OpenFreeIME.KEY_VAD_AUTO_STOP_SECONDS, 2) * 1000L,
                voiceCommandsEnabled = prefs.getBoolean(OpenFreeIME.KEY_VOICE_COMMANDS, true)
            )
        }
    }

    interface Listener {
        /** Partial transcription of the audio so far. Called on a worker thread. */
        fun onPartial(text: String)

        /** The VAD silence timeout elapsed. Called on the audio thread. */
        fun onSilenceTimeout()
    }

    private val lock = Any()
    private val chunks = mutableListOf<FloatArray>()
    private var totalSamples = 0
    private var lastPartialSamples = 0

    @Volatile
    private var sessionActive = false
    private var vad: VoiceActivityDetector? = null
    private var worker: Thread? = null
    private var listener: Listener? = null

    // ── Session lifecycle ──────────────────────────────────────────────────────

    /** Begin a new dictation session. Any previous session is discarded. */
    fun startSession(config: Config, listener: Listener) {
        stopWorker()
        synchronized(lock) {
            chunks.clear()
            totalSamples = 0
            lastPartialSamples = 0
        }
        this.listener = listener
        vad = VoiceActivityDetector(config.silenceTimeoutMs)
        sessionActive = true

        if (config.partialsEnabled) {
            worker = Thread({ partialLoop(config.partialIntervalMs) },
                "StreamingTranscriber-Partials").also { it.start() }
        }
    }

    /**
     * Feed one live audio chunk (16 kHz mono floats). Called on the audio
     * thread; buffers for partial passes and drives the VAD.
     */
    fun feedChunk(chunk: FloatArray) {
        if (!sessionActive) return
        synchronized(lock) {
            chunks.add(chunk)
            totalSamples += chunk.size
        }
        if (vad?.processChunk(chunk) == true) {
            Log.i(TAG, "VAD silence timeout — requesting auto-stop")
            listener?.onSilenceTimeout()
        }
    }

    /**
     * End the session and run the final full-context pass over the complete
     * recording (silence-trimmed). Call from a background thread.
     *
     * @return the final transcription, or "" when no speech was detected.
     */
    fun finishSession(fullSamples: FloatArray): String {
        stopWorker()
        clearSession()

        val trimmed = VoiceActivityDetector.trimSilence(fullSamples)
        if (trimmed.isEmpty()) {
            Log.i(TAG, "finishSession: no speech detected after trimming — skipping inference")
            return ""
        }
        Log.i(TAG, "finishSession: trimmed ${fullSamples.size} -> ${trimmed.size} samples")
        return whisperEngine.transcribe(padToMinimum(trimmed))
    }

    /** Abandon the session without a final pass. Call from a background thread. */
    fun cancelSession() {
        stopWorker()
        clearSession()
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun partialLoop(intervalMs: Long) {
        while (sessionActive) {
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                return
            }
            if (!sessionActive) return

            val snapshot = synchronized(lock) {
                if (totalSamples < MIN_PARTIAL_SAMPLES || totalSamples == lastPartialSamples) {
                    null
                } else {
                    lastPartialSamples = totalSamples
                    snapshotTailLocked(PARTIAL_WINDOW_SAMPLES)
                }
            } ?: continue

            val text = whisperEngine.transcribe(snapshot)
            if (sessionActive && text.isNotBlank()) {
                listener?.onPartial(text.trim())
            }
        }
    }

    /** Copy the most recent [maxSamples] of buffered audio. Caller holds [lock]. */
    private fun snapshotTailLocked(maxSamples: Int): FloatArray {
        val take = minOf(totalSamples, maxSamples)
        val out = FloatArray(take)
        var remaining = take
        var i = chunks.size - 1
        while (remaining > 0 && i >= 0) {
            val chunk = chunks[i]
            val n = minOf(chunk.size, remaining)
            System.arraycopy(chunk, chunk.size - n, out, remaining - n, n)
            remaining -= n
            i--
        }
        return out
    }

    private fun stopWorker() {
        sessionActive = false
        worker?.let {
            it.interrupt()
            try {
                it.join() // serialise: never overlap partial + final inference
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
    }

    private fun clearSession() {
        synchronized(lock) {
            chunks.clear()
            totalSamples = 0
            lastPartialSamples = 0
        }
        vad = null
        listener = null
    }

    /** whisper_full rejects audio shorter than ~1 s; pad with trailing silence. */
    private fun padToMinimum(samples: FloatArray): FloatArray =
        if (samples.size >= MIN_TRANSCRIBE_SAMPLES) samples
        else samples.copyOf(MIN_TRANSCRIBE_SAMPLES)

    companion object {
        private const val TAG = "StreamingTranscriber"

        const val DEFAULT_PARTIAL_INTERVAL_MS = 1_500L
        const val MIN_PARTIAL_SAMPLES = 19_200        // 1.2 s @ 16 kHz
        const val PARTIAL_WINDOW_SAMPLES = 240_000    // 15 s @ 16 kHz
        const val MIN_TRANSCRIBE_SAMPLES = 17_600     // 1.1 s @ 16 kHz
    }
}
