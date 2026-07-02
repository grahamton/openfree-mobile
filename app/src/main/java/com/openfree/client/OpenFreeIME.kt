package com.openfree.client

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PorterDuff
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.regex.Pattern


/**
 * OpenFreeIME
 *
 * The core [InputMethodService] implementation for the OpenFree offline
 * voice-dictation and QWERTY keyboard.
 *
 * Flow:
 *  1. User long-presses spacebar → [startRecording]
 *  2. [AudioRecorder] captures 16 kHz mono PCM in a background thread.
 *  3. User taps spacebar again → [stopAndTranscribe]
 *  4. [WhisperEngine.transcribe] runs inference on the background thread.
 *  5. Result is committed via [android.view.inputmethod.InputConnection.commitText]
 *     on the main thread.
 *  6. User taps spacebar normally → Commits space character.
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
    private var pulseRing: View? = null
    private var editDictWrong: EditText? = null
    private var editDictCorrect: EditText? = null

    // ── State ──────────────────────────────────────────────────────────────────

    private var isRecording = false
    private var isTranscribing = false
    private var isShifted = false
    private var keyboardView: View? = null
    private var pulseAnimator: AnimatorSet? = null
    private var transcribingAnimator: ObjectAnimator? = null
    private var loadedModelPath: String? = null
    @Volatile
    private var discardNextTranscription = false
    private var touchStartTime = 0L
    private var spacebarRunnable: Runnable? = null

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
        keyboardView = view

        btnMic      = view.findViewById(R.id.btn_mic)
        btnSettings = view.findViewById(R.id.btn_settings)
        txtStatus   = view.findViewById(R.id.txt_status)
        txtPreview  = view.findViewById(R.id.txt_preview)
        pulseRing   = view.findViewById(R.id.mic_pulse_ring)

        // ── Voice-Integrated Spacebar Controls (Push-to-Talk with Long Press) ──
        btnMic?.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchStartTime = System.currentTimeMillis()
                    discardNextTranscription = false
                    spacebarRunnable = Runnable {
                        if (!isRecording && !isTranscribing) {
                            startRecording()
                        }
                    }
                    mainHandler.postDelayed(spacebarRunnable!!, 400L) // 400ms long press threshold
                    v.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    spacebarRunnable?.let {
                        mainHandler.removeCallbacks(it)
                    }
                    spacebarRunnable = null

                    if (isRecording) {
                        stopAndTranscribe()
                    } else {
                        // Quick tap -> just commit space, never started recording
                        val ic = currentInputConnection
                        ic?.commitText(" ", 1)
                    }
                    true
                }
                else -> false
            }
        }

        btnSettings?.setOnClickListener { openSettings() }

        // Setup layouts & tabs
        val layoutQwerty = view.findViewById<View>(R.id.layout_qwerty)
        val layoutDict = view.findViewById<View>(R.id.layout_dictionary)

        val btnTabDict = view.findViewById<ImageButton>(R.id.btn_tab_dict)
        val btnTabSwitch = view.findViewById<ImageButton>(R.id.btn_tab_switch)

        val btnClearPreview = view.findViewById<ImageButton>(R.id.btn_clear_preview)

        btnClearPreview?.setOnClickListener {
            txtPreview?.text = ""
        }

        // Inline Dictionary panel toggle
        btnTabDict?.setOnClickListener {
            if (layoutDict.visibility == View.VISIBLE) {
                layoutDict.visibility = View.GONE
                btnTabDict.setColorFilter(
                    ContextCompat.getColor(this, R.color.text_primary),
                    PorterDuff.Mode.SRC_IN
                )
            } else {
                layoutDict.visibility = View.VISIBLE
                btnTabDict.setColorFilter(
                    ContextCompat.getColor(this, R.color.primary),
                    PorterDuff.Mode.SRC_IN
                )

                // Pre-populate with the last word of preview
                val prevText = txtPreview?.text?.toString() ?: ""
                if (prevText.isNotEmpty() && prevText != getString(R.string.preview_hint)) {
                    val words = prevText.trim().split("\\s+".toRegex())
                    val lastWord = words.lastOrNull() ?: ""
                    view.findViewById<EditText>(R.id.edit_dict_wrong)?.setText(lastWord)
                }
            }
        }

        btnTabSwitch?.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            val token = window.window?.attributes?.token
            if (token != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    switchToNextInputMethod(false)
                } else {
                    @Suppress("DEPRECATION")
                    imm.switchToNextInputMethod(token, false)
                }
            }
        }

        // Setup QWERTY keys recursively (Row 1-3 keys)
        setupQwertyKeys(layoutQwerty as ViewGroup)

        // Setup repeating backspace on long-press
        val btnBackspace = view.findViewById<Button>(R.id.key_backspace)
        var backspaceRunnable: Runnable? = null
        btnBackspace?.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    handleKeyClick("⌫")
                    v.performClick()
                    backspaceRunnable = object : Runnable {
                        override fun run() {
                            handleKeyClick("⌫")
                            mainHandler.postDelayed(this, 100)
                        }
                    }
                    mainHandler.postDelayed(backspaceRunnable!!, 400)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (backspaceRunnable != null) {
                        mainHandler.removeCallbacks(backspaceRunnable!!)
                        backspaceRunnable = null
                    }
                    true
                }
                else -> false
            }
        }

        // Setup Dictionary Add Panel actions
        editDictWrong = view.findViewById(R.id.edit_dict_wrong)
        editDictCorrect = view.findViewById(R.id.edit_dict_correct)
        editDictWrong?.showSoftInputOnFocus = false
        editDictCorrect?.showSoftInputOnFocus = false

        val btnDictSave = view.findViewById<Button>(R.id.btn_dict_save)
        val btnDictCancel = view.findViewById<Button>(R.id.btn_dict_cancel)

        btnDictSave?.setOnClickListener {
            val wrong = editDictWrong?.text?.toString() ?: ""
            val correct = editDictCorrect?.text?.toString() ?: ""
            if (wrong.isNotBlank() && correct.isNotBlank()) {
                addDictionaryItem(wrong, correct)
                editDictWrong?.setText("")
                editDictCorrect?.setText("")
                layoutDict.visibility = View.GONE
                btnTabDict?.setColorFilter(
                    ContextCompat.getColor(this, R.color.text_primary),
                    PorterDuff.Mode.SRC_IN
                )
            }
        }

        btnDictCancel?.setOnClickListener {
            editDictWrong?.setText("")
            editDictCorrect?.setText("")
            layoutDict.visibility = View.GONE
            btnTabDict?.setColorFilter(
                ContextCompat.getColor(this, R.color.text_primary),
                PorterDuff.Mode.SRC_IN
            )
        }

        setIdleState()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
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
        stopPulseAnimation()
        whisperEngine.unloadModel()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        return false
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        return false
    }

    // ── Model loading ──────────────────────────────────────────────────────────

    private fun reloadModel() {
        val modelPath = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        if (modelPath.isBlank()) {
            Log.w(TAG, "No model path configured. Open Settings to configure a model.")
            return
        }
        if (whisperEngine.isModelLoaded && modelPath == loadedModelPath) return // already loaded

        Thread {
            val ok = whisperEngine.loadModel(modelPath)
            if (ok) {
                loadedModelPath = modelPath
            }
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
        performHaptic()

        audioRecorder.startRecording { samples ->
            onSamplesReady(samples)
        }
    }

    private fun stopAndTranscribe() {
        if (!isRecording) return
        isRecording = false
        isTranscribing = true
        txtPreview?.text = "Transcribing..."
        setProcessingState()
        performHaptic()

        Thread { audioRecorder.stopRecording() }.start()
    }

    private fun onSamplesReady(samples: FloatArray) {
        if (discardNextTranscription) {
            discardNextTranscription = false
            mainHandler.post {
                isTranscribing = false
                txtPreview?.text = ""
                setIdleState()
            }
            return
        }

        if (samples.isEmpty()) {
            mainHandler.post {
                isTranscribing = false
                txtPreview?.text = "No audio captured"
                setIdleState()
            }
            return
        }

        val text = whisperEngine.transcribe(samples)
        val correctedText = applyDictionary(text)

        mainHandler.post {
            isTranscribing = false
            val trimmed = correctedText.trim()
            if (trimmed.isNotEmpty()) {
                currentInputConnection?.commitText(trimmed, /* newCursorPosition= */ 1)
                txtPreview?.text = trimmed
                performHaptic()
            } else {
                txtPreview?.text = "No speech detected"
            }
            setIdleState()
        }
    }

    // ── QWERTY key handling ────────────────────────────────────────────────────

    private fun setupQwertyKeys(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) {
                setupQwertyKeys(child)
            } else if (child is Button) {
                // Ensure we skipenter or shift special action keys if needed, or bind them
                if (child.id != R.id.key_backspace) {
                    child.setOnClickListener {
                        handleKeyClick(child.text.toString())
                    }
                }
            }
        }
    }

    private fun getFocusedInternalEditText(): EditText? {
        if (editDictWrong?.isFocused == true) return editDictWrong
        if (editDictCorrect?.isFocused == true) return editDictCorrect
        return null
    }

    private fun handleKeyClick(key: String) {
        val focusedEdit = getFocusedInternalEditText()
        if (focusedEdit != null) {
            handleInternalKeyClick(focusedEdit, key)
            return
        }

        val ic = currentInputConnection ?: return
        when (key) {
            "⌫" -> {
                ic.deleteSurroundingText(1, 0)
            }
            "Space" -> {
                ic.commitText(" ", 1)
            }
            "⏎" -> {
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            }
            "⇧" -> {
                isShifted = !isShifted
                toggleQwertyShift()
            }
            else -> {
                val textToCommit = if (isShifted) key.uppercase() else key.lowercase()
                ic.commitText(textToCommit, 1)
                if (isShifted) {
                    isShifted = false
                    toggleQwertyShift()
                }
            }
        }
    }

    private fun handleInternalKeyClick(focusedEdit: EditText, key: String) {
        val start = Math.max(focusedEdit.selectionStart, 0)
        val end = Math.max(focusedEdit.selectionEnd, 0)

        when (key) {
            "⌫" -> {
                if (start > 0 || end > start) {
                    if (start == end) {
                        focusedEdit.text.delete(start - 1, start)
                    } else {
                        focusedEdit.text.delete(start, end)
                    }
                }
            }
            "Space" -> {
                focusedEdit.text.replace(start, end, " ")
            }
            "⏎" -> {
                if (focusedEdit == editDictWrong) {
                    editDictCorrect?.requestFocus()
                } else {
                    keyboardView?.findViewById<Button>(R.id.btn_dict_save)?.performClick()
                }
            }
            "⇧" -> {
                isShifted = !isShifted
                toggleQwertyShift()
            }
            else -> {
                val textToCommit = if (isShifted) key.uppercase() else key.lowercase()
                focusedEdit.text.replace(start, end, textToCommit)
                if (isShifted) {
                    isShifted = false
                    toggleQwertyShift()
                }
            }
        }
    }

    private fun toggleQwertyShift() {
        val layoutQwerty = keyboardView?.findViewById<ViewGroup>(R.id.layout_qwerty) ?: return
        updateKeyCase(layoutQwerty)
    }

    private fun updateKeyCase(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) {
                updateKeyCase(child)
            } else if (child is Button) {
                val txt = child.text.toString()
                if (txt.length == 1 && txt[0].isLetter()) {
                    child.text = if (isShifted) txt.uppercase() else txt.lowercase()
                }
            }
        }
    }

    // ── Dictionary Corrections ─────────────────────────────────────────────────

    private fun getDictionaryMappings(): Map<String, String> {
        val raw = prefs.getString(KEY_DICTIONARY_MAPPINGS, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull {
            val parts = it.split("->")
            if (parts.size == 2) {
                parts[0] to parts[1]
            } else null
        }.toMap()
    }

    private fun addDictionaryItem(wrong: String, correct: String) {
        val current = getDictionaryMappings().toMutableMap()
        current[wrong.trim().lowercase()] = correct.trim()
        val raw = current.map { "${it.key}->${it.value}" }.joinToString(";")
        prefs.edit().putString(KEY_DICTIONARY_MAPPINGS, raw).apply()
    }

    private fun applyDictionary(text: String): String {
        var result = text
        val mappings = getDictionaryMappings()
        for ((wrong, correct) in mappings) {
            val regex = "(?i)\\b${Pattern.quote(wrong)}\\b".toRegex()
            result = result.replace(regex, correct)
        }
        return result
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    enum class MicState { IDLE, RECORDING, TRANSCRIBING }

    private fun setIdleState() {
        txtStatus?.text = "Tap spacebar or hold to dictate"
        applyMicStyle(MicState.IDLE)
        stopPulseAnimation()
    }

    private fun setRecordingState() {
        txtStatus?.text = "Listening... Speak clearly"
        applyMicStyle(MicState.RECORDING)
        startPulseAnimation()
    }

    private fun setProcessingState() {
        txtStatus?.text = "Processing speech..."
        applyMicStyle(MicState.TRANSCRIBING)
        stopPulseAnimation()
    }

    private fun applyMicStyle(state: MicState) {
        btnMic ?: return
        when (state) {
            MicState.IDLE -> {
                btnMic!!.backgroundTintList = null
                btnMic!!.setBackgroundResource(R.drawable.mic_button_bg)
                btnMic!!.setImageResource(R.drawable.ic_mic)
                btnMic!!.setColorFilter(
                    ContextCompat.getColor(this, R.color.on_primary),
                    PorterDuff.Mode.SRC_IN
                )
                stopTranscribingAnimation()
            }
            MicState.RECORDING -> {
                btnMic!!.backgroundTintList = null
                btnMic!!.setBackgroundResource(R.drawable.mic_button_bg_active)
                btnMic!!.setImageResource(R.drawable.ic_stop)
                btnMic!!.setColorFilter(
                    ContextCompat.getColor(this, R.color.on_secondary),
                    PorterDuff.Mode.SRC_IN
                )
                stopTranscribingAnimation()
            }
            MicState.TRANSCRIBING -> {
                btnMic!!.setBackgroundResource(R.drawable.mic_button_bg)
                btnMic!!.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.gold)
                )
                btnMic!!.setImageResource(R.drawable.ic_mic)
                btnMic!!.setColorFilter(
                    ContextCompat.getColor(this, R.color.on_primary),
                    PorterDuff.Mode.SRC_IN
                )
                startTranscribingAnimation()
            }
        }
    }

    private fun startPulseAnimation() {
        val ring = pulseRing ?: return
        ring.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.secondary_container)
        )
        ring.visibility = View.VISIBLE
        ring.alpha = 0.6f
        ring.scaleX = 1.0f
        ring.scaleY = 1.0f

        val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 1.0f, 1.6f)
        val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 1.0f, 1.6f)
        val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.6f, 0.0f)

        scaleX.repeatCount = ValueAnimator.INFINITE
        scaleX.repeatMode = ValueAnimator.RESTART
        scaleY.repeatCount = ValueAnimator.INFINITE
        scaleY.repeatMode = ValueAnimator.RESTART
        alpha.repeatCount = ValueAnimator.INFINITE
        alpha.repeatMode = ValueAnimator.RESTART

        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 1200
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseRing?.alpha = 0.0f
        pulseRing?.visibility = View.GONE
    }

    private fun performHaptic() {
        btnMic?.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun startTranscribingAnimation() {
        stopTranscribingAnimation()
        btnMic?.let { mic ->
            transcribingAnimator = ObjectAnimator.ofFloat(mic, "alpha", 1.0f, 0.4f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun stopTranscribingAnimation() {
        transcribingAnimator?.cancel()
        transcribingAnimator = null
        btnMic?.alpha = 1.0f
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
        const val KEY_DICTIONARY_MAPPINGS = "pref_key_dictionary_mappings"
    }
}
