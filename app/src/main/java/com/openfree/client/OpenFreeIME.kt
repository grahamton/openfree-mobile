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
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private var dotsAnimator: AnimatorSet? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        audioRecorder = AudioRecorder()
        whisperEngine = WhisperEngine(applicationContext)
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
        applyTheme()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        reloadModel()
        txtPreview?.text = getString(R.string.preview_hint)
        applyTheme()
        if (!isRecording) setIdleState()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isRecording) {
            stopAndTranscribe()
        }
        whisperEngine.unloadModel()
        loadedModelPath = null
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

    private val visualizerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val amplitude = audioRecorder.peakAmplitude
                val container = keyboardView?.findViewById<LinearLayout>(R.id.ime_waveform_container)
                val density = resources.displayMetrics.density
                if (container != null && container.visibility == View.VISIBLE) {
                    val count = container.childCount
                    for (i in 0 until count) {
                        val bar = container.getChildAt(i)
                        val timeFactor = System.currentTimeMillis() / 150.0
                        val wave = Math.sin(timeFactor + i * 0.5) * 0.5 + 0.5
                        val scaleFactor = 0.2 + 0.8 * amplitude * wave
                        val maxBarHeight = 28 * density
                        val newHeight = (maxBarHeight * scaleFactor).toInt().coerceAtLeast((4 * density).toInt())
                        
                        val lp = bar.layoutParams
                        lp.height = newHeight
                        bar.layoutParams = lp
                    }
                }
                mainHandler.postDelayed(this, 50)
            }
        }
    }

    private fun startRecording() {
        isRecording = true
        txtPreview?.text = "Listening..."
        setRecordingState()
        performHaptic()

        val theme = prefs.getString("pref_key_theme", "classic") ?: "classic"
        val container = keyboardView?.findViewById<View>(R.id.ime_waveform_container)
        container?.visibility = View.VISIBLE
        if (theme == "oled") {
            val count = (container as? ViewGroup)?.childCount ?: 0
            for (i in 0 until count) {
                val bar = container?.findViewById<LinearLayout>(R.id.ime_waveform_container)?.getChildAt(i)
                bar?.setBackgroundColor(android.graphics.Color.parseColor("#7FFFCB"))
            }
        } else {
            val count = (container as? ViewGroup)?.childCount ?: 0
            for (i in 0 until count) {
                val bar = container?.findViewById<LinearLayout>(R.id.ime_waveform_container)?.getChildAt(i)
                bar?.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            }
        }

        if (theme == "classic" || theme == "frosted") {
            setLetterKeysAlpha(0.55f)
        }

        audioRecorder.startRecording { samples ->
            onSamplesReady(samples)
        }
        mainHandler.post(visualizerRunnable)
    }

    private fun stopAndTranscribe() {
        if (!isRecording) return
        isRecording = false
        isTranscribing = true
        txtPreview?.text = "Transcribing..."
        setProcessingState()
        performHaptic()

        val theme = prefs.getString("pref_key_theme", "classic") ?: "classic"
        keyboardView?.findViewById<View>(R.id.ime_waveform_container)?.visibility = View.GONE
        
        if (theme == "classic" || theme == "frosted") {
            setLetterKeysAlpha(1.0f)
        }

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
        val btn = btnMic ?: return
        val theme = prefs.getString("pref_key_theme", "classic") ?: "classic"
        val density = resources.displayMetrics.density
        
        btn.backgroundTintList = null
        btn.alpha = 1.0f
        
        if (theme == "oled") {
            btn.elevation = 0f
            when (state) {
                MicState.IDLE -> {
                    val normalBg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 4 * density
                        setColor(android.graphics.Color.parseColor("#121212"))
                        setStroke((1 * density).toInt(), android.graphics.Color.parseColor("#2A2A2A"))
                    }
                    btn.background = normalBg
                    btn.setImageResource(R.drawable.ic_mic)
                    btn.setColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
                    stopTranscribingAnimation()
                }
                MicState.RECORDING -> {
                    val activeBg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 4 * density
                        setColor(android.graphics.Color.parseColor("#7FFFCB"))
                    }
                    btn.background = activeBg
                    btn.setImageResource(R.drawable.ic_stop)
                    btn.setColorFilter(android.graphics.Color.BLACK, PorterDuff.Mode.SRC_IN)
                    stopTranscribingAnimation()
                }
                MicState.TRANSCRIBING -> {
                    val activeBg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 4 * density
                        setColor(android.graphics.Color.parseColor("#7FFFCB"))
                    }
                    btn.background = activeBg
                    btn.setImageResource(R.drawable.ic_mic)
                    btn.setColorFilter(android.graphics.Color.BLACK, PorterDuff.Mode.SRC_IN)
                    startTranscribingAnimation()
                }
            }
            return
        }

        if (theme == "frosted") {
            btn.elevation = if (state == MicState.RECORDING) 18f * density else 0f
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val shadowColor = if (state == MicState.RECORDING) "#D0BCFF" else "#FFFFFF"
                btn.outlineSpotShadowColor = android.graphics.Color.parseColor(shadowColor)
                btn.outlineAmbientShadowColor = android.graphics.Color.parseColor(shadowColor)
            }
            when (state) {
                MicState.IDLE -> {
                    btn.setBackgroundResource(R.drawable.mic_button_bg_frosted)
                    btn.setImageResource(R.drawable.ic_mic)
                    btn.setColorFilter(android.graphics.Color.parseColor("#D0BCFF"), PorterDuff.Mode.SRC_IN)
                    stopTranscribingAnimation()
                }
                MicState.RECORDING -> {
                    val activeBg = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 22 * density
                        setColor(android.graphics.Color.parseColor("#D0BCFF"))
                    }
                    btn.background = activeBg
                    btn.setImageResource(R.drawable.ic_stop)
                    btn.setColorFilter(android.graphics.Color.parseColor("#21005D"), PorterDuff.Mode.SRC_IN)
                    stopTranscribingAnimation()
                }
                MicState.TRANSCRIBING -> {
                    btn.setBackgroundResource(R.drawable.mic_button_bg_frosted)
                    btn.alpha = 0.7f
                    btn.setImageResource(R.drawable.ic_mic)
                    btn.setColorFilter(android.graphics.Color.parseColor("#D0BCFF"), PorterDuff.Mode.SRC_IN)
                    startTranscribingAnimation()
                }
            }
            return
        }

        when (state) {
            MicState.IDLE -> {
                btn.setBackgroundResource(R.drawable.mic_button_bg)
                btn.setImageResource(R.drawable.ic_mic)
                btn.setColorFilter(
                    ContextCompat.getColor(this, R.color.on_primary),
                    PorterDuff.Mode.SRC_IN
                )
                stopTranscribingAnimation()
            }
            MicState.RECORDING -> {
                btn.setBackgroundResource(R.drawable.mic_button_bg_active)
                btn.setImageResource(R.drawable.ic_stop)
                btn.setColorFilter(
                    ContextCompat.getColor(this, R.color.on_secondary),
                    PorterDuff.Mode.SRC_IN
                )
                stopTranscribingAnimation()
            }
            MicState.TRANSCRIBING -> {
                btn.setBackgroundResource(R.drawable.mic_button_bg)
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.gold)
                )
                btn.setImageResource(R.drawable.ic_mic)
                btn.setColorFilter(
                    ContextCompat.getColor(this, R.color.on_primary),
                    PorterDuff.Mode.SRC_IN
                )
                startTranscribingAnimation()
            }
        }
    }

    private fun startPulseAnimation() {
        val ring = pulseRing ?: return
        val theme = prefs.getString("pref_key_theme", "classic") ?: "classic"
        if (theme == "oled" || theme == "frosted") {
            ring.visibility = View.GONE
            return
        }

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
            duration = 1600
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
        val theme = prefs.getString("pref_key_theme", "classic") ?: "classic"
        if (theme == "frosted") {
            val layoutDots = keyboardView?.findViewById<View>(R.id.layout_dots)
            val dot1 = keyboardView?.findViewById<View>(R.id.dot1)
            val dot2 = keyboardView?.findViewById<View>(R.id.dot2)
            val dot3 = keyboardView?.findViewById<View>(R.id.dot3)
            if (layoutDots != null && dot1 != null && dot2 != null && dot3 != null) {
                layoutDots.visibility = View.VISIBLE
                val anim1 = ObjectAnimator.ofFloat(dot1, "translationY", 0f, -10f, 0f).apply {
                    duration = 1200
                    repeatCount = ValueAnimator.INFINITE
                }
                val anim2 = ObjectAnimator.ofFloat(dot2, "translationY", 0f, -10f, 0f).apply {
                    duration = 1200
                    startDelay = 400
                    repeatCount = ValueAnimator.INFINITE
                }
                val anim3 = ObjectAnimator.ofFloat(dot3, "translationY", 0f, -10f, 0f).apply {
                    duration = 1200
                    startDelay = 800
                    repeatCount = ValueAnimator.INFINITE
                }
                dotsAnimator = AnimatorSet().apply {
                    playTogether(anim1, anim2, anim3)
                    start()
                }
            }
        } else {
            // Classic/OLED: show horizontal indeterminate progress bar
            val progressBar = keyboardView?.findViewById<ProgressBar>(R.id.ime_progress_bar)
            if (progressBar != null) {
                if (theme == "oled") {
                    progressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#7FFFCB")
                    )
                } else {
                    progressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.primary)
                    )
                }
                progressBar.visibility = View.VISIBLE
            }
            btnMic?.let { mic ->
                transcribingAnimator = ObjectAnimator.ofFloat(mic, "alpha", 1.0f, 0.4f).apply {
                    duration = 600
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        }
    }

    private fun stopTranscribingAnimation() {
        transcribingAnimator?.cancel()
        transcribingAnimator = null
        btnMic?.alpha = 1.0f

        dotsAnimator?.cancel()
        dotsAnimator = null
        keyboardView?.findViewById<View>(R.id.layout_dots)?.visibility = View.GONE

        keyboardView?.findViewById<ProgressBar>(R.id.ime_progress_bar)?.visibility = View.GONE
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun applyTheme() {
        val theme = prefs.getString("pref_key_theme", "classic") ?: "classic"
        val root = keyboardView?.findViewById<View>(R.id.keyboard_root) ?: return
        val density = resources.displayMetrics.density

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            window?.window?.setBackgroundBlurRadius(0)
        }

        resetKeysStyle()

        when (theme) {
            "classic" -> {
                root.setBackgroundColor(ContextCompat.getColor(this, R.color.surface))
                applyClassicKeysStyle()
            }
            "frosted" -> {
                val isPowerSave = (getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode ?: false
                if (android.os.Build.VERSION.SDK_INT >= 31 && !isPowerSave) {
                    val radius = prefs.getInt("pref_key_blur_radius", 20)
                    window?.window?.setBackgroundBlurRadius(radius)
                    root.setBackgroundColor(android.graphics.Color.parseColor("#8C18161E"))
                } else {
                    root.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_container))
                }
                applyFrostedKeysStyle()
            }
            "oled" -> {
                root.setBackgroundColor(android.graphics.Color.parseColor("#000000"))
                applyOledKeysStyle()
            }
        }
        applyMicStyle(if (isRecording) MicState.RECORDING else if (isTranscribing) MicState.TRANSCRIBING else MicState.IDLE)
    }

    private fun resetKeysStyle() {
        val root = keyboardView?.findViewById<ViewGroup>(R.id.keyboard_root) ?: return
        resetKeysStyleRecursive(root)
    }

    private fun resetKeysStyleRecursive(viewGroup: ViewGroup) {
        val density = resources.displayMetrics.density
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) {
                resetKeysStyleRecursive(child)
            } else if (child is Button) {
                child.background = ContextCompat.getDrawable(this, R.drawable.key_bg)
                child.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                child.elevation = 2 * density
                child.alpha = 1.0f
            } else if (child is ImageButton) {
                if (child.id != R.id.btn_mic) {
                    child.background = ContextCompat.getDrawable(this, R.drawable.key_bg)
                    child.imageTintList = null
                    child.setColorFilter(ContextCompat.getColor(this, R.color.text_primary), PorterDuff.Mode.SRC_IN)
                    child.elevation = 2 * density
                    child.alpha = 1.0f
                }
            }
        }
    }

    private fun applyClassicKeysStyle() {
        // XML defaults
    }

    private fun applyFrostedKeysStyle() {
        val root = keyboardView?.findViewById<ViewGroup>(R.id.keyboard_root) ?: return
        applyFrostedKeysStyleRecursive(root)
    }

    private fun applyFrostedKeysStyleRecursive(viewGroup: ViewGroup) {
        val density = resources.displayMetrics.density
        val radius = 10 * density
        val textColors = ContextCompat.getColorStateList(this@OpenFreeIME, R.color.key_text_color_frosted)

        fun makeNormalBg() = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(android.graphics.Color.argb((255 * 0.08).toInt(), 255, 255, 255))
            setStroke((1 * density).toInt(), android.graphics.Color.argb((255 * 0.12).toInt(), 255, 255, 255))
        }
        fun makePressedBg() = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(android.graphics.Color.argb((255 * 0.18).toInt(), 255, 255, 255))
            setStroke((1 * density).toInt(), ContextCompat.getColor(this@OpenFreeIME, R.color.primary))
        }

        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) {
                applyFrostedKeysStyleRecursive(child)
            } else if (child is Button) {
                val states = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), makePressedBg())
                    addState(intArrayOf(), makeNormalBg())
                }
                child.background = states
                child.setTextColor(textColors)
                child.elevation = 0f
            } else if (child is ImageButton) {
                if (child.id != R.id.btn_mic) {
                    val states = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), makePressedBg())
                        addState(intArrayOf(), makeNormalBg())
                    }
                    child.background = states
                    child.imageTintList = textColors
                    child.elevation = 0f
                }
            }
        }
    }

    private fun applyOledKeysStyle() {
        val root = keyboardView?.findViewById<ViewGroup>(R.id.keyboard_root) ?: return
        applyOledKeysStyleRecursive(root)
    }

    private fun applyOledKeysStyleRecursive(viewGroup: ViewGroup) {
        val density = resources.displayMetrics.density
        val radius = 7 * density

        fun makeNormalBg() = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(android.graphics.Color.parseColor("#121212"))
            setStroke((1 * density).toInt(), android.graphics.Color.parseColor("#2A2A2A"))
        }
        fun makePressedBg() = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(android.graphics.Color.parseColor("#EDEDED"))
        }

        val textColors = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE)
        )

        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) {
                applyOledKeysStyleRecursive(child)
            } else if (child is Button) {
                val states = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), makePressedBg())
                    addState(intArrayOf(), makeNormalBg())
                }
                child.background = states
                child.setTextColor(textColors)
                child.elevation = 0f
            } else if (child is ImageButton) {
                if (child.id != R.id.btn_mic) {
                    val states = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), makePressedBg())
                        addState(intArrayOf(), makeNormalBg())
                    }
                    child.background = states
                    child.imageTintList = textColors
                    child.elevation = 0f
                }
            }
        }
    }

    private fun setLetterKeysAlpha(alpha: Float) {
        val layoutQwerty = keyboardView?.findViewById<ViewGroup>(R.id.layout_qwerty) ?: return
        setViewGroupKeysAlpha(layoutQwerty, alpha)
    }

    private fun setViewGroupKeysAlpha(viewGroup: ViewGroup, alpha: Float) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) {
                setViewGroupKeysAlpha(child, alpha)
            } else if (child is Button) {
                val txt = child.text.toString()
                if (txt.length == 1 && txt[0].isLetter()) {
                    child.alpha = alpha
                }
            }
        }
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
