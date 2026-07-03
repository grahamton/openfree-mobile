package com.openfree.client

/**
 * VoiceActivityDetector
 *
 * Shared, pure-logic energy VAD used by both dictation surfaces
 * (same pattern as [DictionaryStore]; mirrored 1:1 by the Python
 * mock-harness tests).
 *
 * Audio is analysed in 30 ms frames (480 samples @ 16 kHz). A frame is
 * "speech" when its RMS energy reaches [speechThreshold]. Two duties:
 *
 *  1. Auto-stop: feed live chunks through [processChunk]; it returns true
 *     exactly once, when [silenceTimeoutMs] of continuous silence has
 *     elapsed after speech was heard. Leading silence never fires — the
 *     user gets as long as they need to start talking.
 *  2. Trimming: [trimSilence] removes leading/trailing silence (keeping
 *     [TRIM_PADDING_MS] of context) before the final Whisper pass, which
 *     cuts inference time and avoids hallucinations on silent tails.
 */
class VoiceActivityDetector(
    /** Continuous silence after speech that triggers auto-stop; <= 0 disables. */
    private val silenceTimeoutMs: Long,
    private val speechThreshold: Float = DEFAULT_SPEECH_THRESHOLD
) {

    private val remainder = ArrayDeque<Float>()
    private var hasHeardSpeech = false
    private var silenceMs = 0L
    private var fired = false

    /**
     * Consume a live audio chunk (any length, 16 kHz mono [-1, 1] floats).
     *
     * @return true exactly once per session, at the moment the configured
     *         silence timeout elapses after speech. Always false when
     *         disabled ([silenceTimeoutMs] <= 0).
     */
    fun processChunk(chunk: FloatArray): Boolean {
        if (silenceTimeoutMs <= 0 || fired) return false

        for (sample in chunk) remainder.addLast(sample)

        val frame = FloatArray(FRAME_SIZE)
        while (remainder.size >= FRAME_SIZE) {
            for (i in 0 until FRAME_SIZE) frame[i] = remainder.removeFirst()
            if (frameRms(frame, FRAME_SIZE) >= speechThreshold) {
                hasHeardSpeech = true
                silenceMs = 0L
            } else if (hasHeardSpeech) {
                silenceMs += FRAME_MS
                if (silenceMs >= silenceTimeoutMs) {
                    fired = true
                    return true
                }
            }
        }
        return false
    }

    /** Clear all state so the detector can be reused for a new session. */
    fun reset() {
        remainder.clear()
        hasHeardSpeech = false
        silenceMs = 0L
        fired = false
    }

    companion object {
        const val FRAME_SIZE = 480          // 30 ms at 16 kHz
        const val FRAME_MS = 30L
        const val DEFAULT_SPEECH_THRESHOLD = 0.015f
        const val TRIM_PADDING_MS = 200

        private const val SAMPLE_RATE = 16_000
        private const val PADDING_SAMPLES = SAMPLE_RATE * TRIM_PADDING_MS / 1000

        /**
         * Strip leading and trailing silence from a full recording, keeping
         * [TRIM_PADDING_MS] of context on each side. Returns an empty array
         * when no frame reaches [threshold] (i.e. no speech at all), so
         * callers can skip inference entirely.
         */
        fun trimSilence(
            samples: FloatArray,
            threshold: Float = DEFAULT_SPEECH_THRESHOLD
        ): FloatArray {
            if (samples.isEmpty()) return samples

            var firstSpeechFrame = -1
            var lastSpeechFrame = -1
            var frame = 0
            var offset = 0
            while (offset < samples.size) {
                val len = minOf(FRAME_SIZE, samples.size - offset)
                if (frameRms(samples, len, offset) >= threshold) {
                    if (firstSpeechFrame < 0) firstSpeechFrame = frame
                    lastSpeechFrame = frame
                }
                frame++
                offset += FRAME_SIZE
            }

            if (firstSpeechFrame < 0) return FloatArray(0)

            val start = maxOf(0, firstSpeechFrame * FRAME_SIZE - PADDING_SAMPLES)
            val end = minOf(samples.size, (lastSpeechFrame + 1) * FRAME_SIZE + PADDING_SAMPLES)
            if (start == 0 && end == samples.size) return samples
            return samples.copyOfRange(start, end)
        }

        private fun frameRms(samples: FloatArray, length: Int, offset: Int = 0): Float {
            if (length <= 0) return 0f
            var sum = 0.0
            for (i in offset until offset + length) {
                val s = samples[i].toDouble()
                sum += s * s
            }
            return Math.sqrt(sum / length).toFloat()
        }
    }
}
