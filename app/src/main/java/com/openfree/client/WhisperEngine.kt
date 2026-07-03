package com.openfree.client

import android.content.Context
import android.util.Log

/**
 * WhisperEngine
 *
 * Kotlin-side wrapper around the native `openfree_native` JNI library.
 * Manages the lifetime of a whisper_context (via a Long pointer) and
 * exposes a clean API for loading a model and transcribing audio.
 *
 * Thread safety: [loadModel], [transcribe], and [unloadModel] are
 * @Synchronized so the native context can never be freed mid-inference
 * (e.g. keyboard dismissed while a streaming partial pass is running).
 */
class WhisperEngine(private val context: Context) {

    private var contextPtr: Long = 0L

    val isModelLoaded: Boolean
        get() = contextPtr != 0L

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Load a GGML Whisper model from the given file path.
     *
     * @return true if the model loaded successfully, false otherwise.
     */
    @Synchronized
    fun loadModel(modelPath: String): Boolean {
        unloadModel()
        Log.i(TAG, "loadModel: $modelPath")
        contextPtr = nativeLoadModel(modelPath)
        if (contextPtr == 0L) {
            Log.e(TAG, "loadModel: failed — context is null")
            return false
        }
        Log.i(TAG, "loadModel: success (contextPtr=$contextPtr)")
        return true
    }

    /**
     * Transcribe raw float audio samples (16kHz mono PCM, normalised [-1, 1]).
     *
     * @return The transcribed text, or an empty string on failure.
     */
    @Synchronized
    fun transcribe(audioSamples: FloatArray): String {
        if (!isModelLoaded) {
            Log.w(TAG, "transcribe: no model loaded — returning empty string")
            return ""
        }
        if (audioSamples.isEmpty()) {
            Log.w(TAG, "transcribe: empty audio array")
            return ""
        }
        return nativeTranscribe(contextPtr, audioSamples)
    }

    /**
     * Release the native whisper context and free associated memory.
     * Safe to call even if no model is currently loaded.
     */
    @Synchronized
    fun unloadModel() {
        if (contextPtr != 0L) {
            nativeUnloadModel(contextPtr)
            contextPtr = 0L
            Log.i(TAG, "unloadModel: context released")
        }
    }

    /** Human-readable string of compiled Whisper/GGML capabilities. */
    fun getSystemInfo(): String = nativeGetSystemInfo()

    // ── JNI declarations ───────────────────────────────────────────────────────

    private external fun nativeLoadModel(modelPath: String): Long
    private external fun nativeTranscribe(contextPtr: Long, samples: FloatArray): String
    private external fun nativeUnloadModel(contextPtr: Long)
    private external fun nativeGetSystemInfo(): String

    companion object {
        private const val TAG = "WhisperEngine"

        init {
            // whisper may be a shared library or linked statically; suppress the
            // UnsatisfiedLinkError if it is folded into openfree_native.
            try {
                System.loadLibrary("whisper")
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "whisper not loaded as a separate library (may be static)")
            }
            System.loadLibrary("openfree_native")
        }
    }
}
