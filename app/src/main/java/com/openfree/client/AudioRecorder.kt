package com.openfree.client

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioRecorder
 *
 * Records 16-bit PCM mono audio at 16 kHz via [AudioRecord] and converts
 * the captured samples to a normalized float array in the range [-1.0, 1.0].
 *
 * Usage:
 * ```
 * audioRecorder.startRecording { samples ->
 *     // called on background thread after stopRecording()
 *     val text = whisperEngine.transcribe(samples)
 * }
 * // ... later ...
 * audioRecorder.stopRecording()
 * ```
 *
 * ⚠ Requires the RECORD_AUDIO permission to be granted at runtime on API 23+.
 */
class AudioRecorder {

    // ── Public state ───────────────────────────────────────────────────────────

    /** True while the recorder is actively capturing audio. */
    val isActive: Boolean get() = _recording.get()

    // ── Private state ──────────────────────────────────────────────────────────

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val _recording = AtomicBoolean(false)

    @Volatile
    var peakAmplitude: Float = 0f
        private set

    /**
     * Optional callback invoked on the recording thread each time a new
     * chunk of audio is read. The parameter is the peak amplitude of
     * the chunk normalised to [0.0, 1.0].
     */
    var onAmplitudeUpdate: ((Float) -> Unit)? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Start capturing audio from the device microphone.
     *
     * Recording continues until [stopRecording] is called.  When recording
     * stops, [onAudioSamplesReady] is invoked **on the recording background
     * thread** with a [FloatArray] of the full captured session normalised to
     * [-1.0, 1.0].  Pass the array directly to [WhisperEngine.transcribe].
     *
     * @param onAudioSamplesReady Callback invoked once after [stopRecording].
     */
    fun startRecording(onAudioSamplesReady: (FloatArray) -> Unit) {
        if (_recording.get()) {
            Log.w(TAG, "startRecording called while already recording — ignoring")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize returned error: $minBuf")
            return
        }
        val bufSize = maxOf(minBuf * 4, 8192)

        @Suppress("MissingPermission") // Permission checked before IME is enabled
        // Switched from MediaRecorder.AudioSource.MIC to MediaRecorder.AudioSource.VOICE_RECOGNITION for Okay Google
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise (state=${record.state})")
            record.release()
            return
        }

        audioRecord = record
        _recording.set(true)
        peakAmplitude = 0f
        record.startRecording()
        Log.i(TAG, "startRecording: capturing at ${SAMPLE_RATE} Hz mono 16-bit PCM")

        val chunkSize = bufSize / 2 // Shorts are 2 bytes

        recordingThread = Thread({
            val samples = mutableListOf<Short>()
            val chunk = ShortArray(chunkSize)

            while (_recording.get()) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    var maxVal = 0
                    for (i in 0 until read) {
                        val sample = chunk[i]
                        samples.add(sample)
                        val absVal = Math.abs(sample.toInt())
                        if (absVal > maxVal) {
                            maxVal = absVal
                        }
                    }
                    peakAmplitude = maxVal.toFloat() / Short.MAX_VALUE.toFloat()
                    onAmplitudeUpdate?.invoke(peakAmplitude)
                }
            }

            // Convert 16-bit signed PCM → normalised float [-1, 1]
            val floats = FloatArray(samples.size) { i ->
                samples[i].toFloat() / Short.MAX_VALUE.toFloat()
            }

            Log.i(TAG, "recording stopped: ${floats.size} samples collected")
            onAudioSamplesReady(floats)

        }, "AudioRecorder-Thread").also { it.start() }
    }

    /**
     * Stop recording and trigger the [onAudioSamplesReady] callback.
     *
     * Blocks until the recording thread has exited (max 4 s) and the
     * [AudioRecord] has been released.
     */
    fun stopRecording() {
        if (!_recording.getAndSet(false)) {
            Log.w(TAG, "stopRecording called while not recording — ignoring")
            return
        }

        recordingThread?.join(4_000L)
        recordingThread = null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        Log.i(TAG, "stopRecording: AudioRecord released")
    }

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        private const val TAG          = "AudioRecorder"
        private const val SAMPLE_RATE  = 16_000                         // Whisper requires 16 kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT
    }
}
