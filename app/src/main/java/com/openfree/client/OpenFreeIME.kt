package com.openfree.client

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PorterDuff
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * OpenFreeIME
 *
 * The core [InputMethodService] implementation for the OpenFree offline
 * voice-dictation keyboard.
 *
 * Flow:
 *  1. User taps mic button → [startRecording]
 *  2. [AudioRecorder] captures 16 kHz mono PCM in a background thread.
 *  3. User taps mic button again → [stopAndTranscribe]
 *  4. [WhisperEngine.transcribe] runs inference on the background thread.
 *  5. Result is committed via [android.view.inputmethod.InputConnection.commitText]
 *     on the main thread.
 */
class OpenFreeIME : InputMethodService() {

    // ── Dependencies ───────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var prefs: SharedPreferences

    // ── View references ────────────────────────────────────────────────────────

    private var btnMic: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var txtStatus: TextView? = null
    private var txtPreview: TextView? = null

    // ── State ──────────────────────────────────────────────────────────────────

    private var isRecording = false
    private var isTranscribing = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        audioRecorder = AudioRecorder()
        whisperEngine = WhisperEngine(applicationContext)
        reloadModel()
    }

    override fun onCreateInputView(): View {
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_OpenFree)
        val view = android.view.LayoutInflater.from(themedContext).inflate(R.layout.keyboard_view, null)

        btnMic      = view.findViewById(R.id.btn_mic)
        btnSettings = view.findViewById(R.id.btn_settings)
        txtStatus   = view.findViewById(R.id.txt_status)
        txtPreview  = view.findViewById(R.id.txt_preview)

        btnMic?.setOnClickListener { toggleRecording() }
        btnSettings?.setOnClickListener { openSettings() }

        setIdleState()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Re-check model on each keyboard activation (user might have changed path in settings)
        reloadModel()
        txtPreview?.text = getString(R.string.preview_hint)
        if (!isRecording) setIdleState()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isRecording) {
            stopAndTranscribe()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            audioRecorder.stopRecording()
        }
        whisperEngine.unloadModel()
    }

    // ── Model loading ──────────────────────────────────────────────────────────

    private fun reloadModel() {
        val modelPath = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        if (modelPath.isBlank()) {
            Log.w(TAG, "No model path configured. Open Settings to configure a model.")
            return
        }
        if (whisperEngine.isModelLoaded) return // already loaded

        Thread {
            val ok = whisperEngine.loadModel(modelPath)
            Log.i(TAG, "reloadModel: loaded=$ok path=$modelPath")
        }.start()
    }

    // ── Recording flow ─────────────────────────────────────────────────────────

    private fun toggleRecording() {
        if (isTranscribing) {
            Log.w(TAG, "toggleRecording: currently transcribing — ignoring tap")
            return
        }
        if (isRecording) stopAndTranscribe() else startRecording()
    }

    private fun startRecording() {
        isRecording = true
        txtPreview?.text = "Listening..."
        setRecordingState()

        audioRecorder.startRecording { samples ->
            // Callback runs on the AudioRecorder background thread
            onSamplesReady(samples)
        }
    }

    private fun stopAndTranscribe() {
        if (!isRecording) return
        isRecording = false
        isTranscribing = true
        txtPreview?.text = "Transcribing..."
        setProcessingState()

        // stopRecording() signals the thread to stop and triggers the callback
        Thread { audioRecorder.stopRecording() }.start()
    }

    private fun onSamplesReady(samples: FloatArray) {
        if (samples.isEmpty()) {
            mainHandler.post {
                isTranscribing = false
                txtPreview?.text = "No audio captured"
                setIdleState()
            }
            return
        }

        val text = whisperEngine.transcribe(samples)

        mainHandler.post {
            isTranscribing = false
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                currentInputConnection?.commitText(trimmed, /* newCursorPosition= */ 1)
                txtPreview?.text = trimmed
            } else {
                txtPreview?.text = "No speech detected"
            }
            setIdleState()
        }
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private fun setIdleState() {
        txtStatus?.text = getString(R.string.status_idle)
        applyMicStyle(recording = false)
    }

    private fun setRecordingState() {
        txtStatus?.text = getString(R.string.status_listening)
        applyMicStyle(recording = true)
    }

    private fun setProcessingState() {
        txtStatus?.text = getString(R.string.status_processing)
        applyMicStyle(recording = false)
    }

    private fun applyMicStyle(recording: Boolean) {
        btnMic ?: return
        if (recording) {
            btnMic!!.setBackgroundResource(R.drawable.mic_button_bg_active)
            btnMic!!.setImageResource(R.drawable.ic_stop)
            btnMic!!.setColorFilter(
                ContextCompat.getColor(this, R.color.on_secondary),
                PorterDuff.Mode.SRC_IN
            )
        } else {
            btnMic!!.setBackgroundResource(R.drawable.mic_button_bg)
            btnMic!!.setImageResource(R.drawable.ic_mic)
            btnMic!!.setColorFilter(
                ContextCompat.getColor(this, R.color.on_primary),
                PorterDuff.Mode.SRC_IN
            )
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ── JNI legacy stub ────────────────────────────────────────────────────────

    /** Returns Whisper system info string; used by legacy test stub in whisper-jni.cpp. */
    external fun stringFromJNI(): String

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        private const val TAG        = "OpenFreeIME"
        const val PREFS_NAME         = "openfree_prefs"
        const val KEY_MODEL_PATH     = "pref_key_model_path"
        const val KEY_REMOTE_FALLBACK_URL = "pref_key_remote_fallback_url"
    }
}
