package com.openfree.client

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.regex.Pattern


/**
 * FloatingOpenFreeService
 *
 * An AccessibilityService-based floating dictation widget.
 * Draws an interactive overlay window over other apps. Tapping the widget
 * records and transcribes voice input, then injects it into the focused input field
 * of Gboard or any other keyboard.
 */
class FloatingOpenFreeService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    enum class State { IDLE, RECORDING, TRANSCRIBING }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var btnFloatingMic: ImageButton? = null
    private var pulseRing: View? = null
    private var pulseAnimator: AnimatorSet? = null
    private var transcribingAnimator: ObjectAnimator? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var currentState = State.IDLE
    private var loadedModelPath: String? = null
    private var isDragActive = false


    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(OpenFreeIME.PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        audioRecorder = AudioRecorder()
        whisperEngine = WhisperEngine(applicationContext)
        loadModel()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == OpenFreeIME.KEY_MODEL_PATH) {
            loadModel()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Inflate overlay UI
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_layout, null)

        btnFloatingMic = floatingView.findViewById(R.id.btn_floating_mic)
        pulseRing = floatingView.findViewById(R.id.floating_pulse_ring)

        // Overlay layout parameters using specialized accessibility overlay type
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        // Store LayoutParams as class member
        windowParams = params

        // Initial absolute gravity and offsets (Bottom Right margin layout)
        params.gravity = Gravity.TOP or Gravity.START
        
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val width = (76 * density).toInt()
        val height = (76 * density).toInt()

        params.x = screenWidth - width - (16 * density).toInt()
        params.y = screenHeight - height - (100 * density).toInt()

        // Implement Drag-to-Move gesture & Click Toggle
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        isDragActive = false

        val longPressRunnable = Runnable {
            if (currentState == State.IDLE) {
                isDragActive = true
                btnFloatingMic?.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                floatingView.animate()
                    .scaleX(1.15f)
                    .scaleY(1.15f)
                    .alpha(0.85f)
                    .setDuration(150)
                    .start()
            }
        }

        btnFloatingMic?.setOnTouchListener { v, event ->
            val dm = resources.displayMetrics
            val sw = dm.widthPixels
            val sh = dm.heightPixels
            val w = floatingView.width.takeIf { it > 0 } ?: (76 * dm.density).toInt()
            val h = floatingView.height.takeIf { it > 0 } ?: (76 * dm.density).toInt()
            val maxX = sw - w
            val maxY = sh - h

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragActive = false
                    mainHandler.postDelayed(longPressRunnable, 350)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (!isDragActive && (Math.abs(deltaX) > 15 || Math.abs(deltaY) > 15)) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragActive) {
                        params.x = (initialX + deltaX.toInt()).coerceIn(0, maxX)
                        params.y = (initialY + deltaY.toInt()).coerceIn(0, maxY)
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (isDragActive) {
                        isDragActive = false
                        btnFloatingMic?.performHapticFeedback(
                            HapticFeedbackConstants.KEYBOARD_TAP,
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                        )
                        val startX = params.x
                        val targetX = if (startX + w / 2 < sw / 2) 0 else maxX
                        
                        ValueAnimator.ofInt(startX, targetX).apply {
                            duration = 200
                            addUpdateListener { animation ->
                                if (floatingView.parent != null) {
                                    params.x = animation.animatedValue as Int
                                    windowManager.updateViewLayout(floatingView, params)
                                }
                            }
                            start()
                        }

                        floatingView.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .alpha(1.0f)
                            .setDuration(200)
                            .start()
                    } else {
                        v.performClick()
                        toggleRecording()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
        Log.i(TAG, "Floating speech overlay successfully initialized")
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        if (::floatingView.isInitialized && floatingView.parent != null) {
            windowManager.removeView(floatingView)
        }
        if (currentState == State.RECORDING) {
            audioRecorder.stopRecording()
        }
        stopPulseAnimation()
        stopTranscribingAnimation()
        whisperEngine.unloadModel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Accessibility events tracking is not needed for overlay layout
    }

    override fun onInterrupt() {
        // Interrupt signal response is not required for overlay layout
    }

    // ── Speech Processing ──────────────────────────────────────────────────────

    private fun loadModel() {
        val modelPath = prefs.getString(OpenFreeIME.KEY_MODEL_PATH, "") ?: ""
        if (modelPath.isBlank()) {
            Log.w(TAG, "No voice model path configured in Settings.")
            return
        }
        if (whisperEngine.isModelLoaded && modelPath == loadedModelPath) return // already loaded

        Thread {
            val ok = whisperEngine.loadModel(modelPath)
            if (ok) {
                loadedModelPath = modelPath
            }
        }.start()
    }

    private fun toggleRecording() {
        if (currentState == State.TRANSCRIBING) return
        if (currentState == State.RECORDING) stopAndTranscribe() else startRecording()
    }

    private fun startRecording() {
        currentState = State.RECORDING
        applyMicStyle(State.RECORDING)
        performHaptic()
        startPulseAnimation()

        audioRecorder.startRecording { samples ->
            onSamplesReady(samples)
        }
    }

    private fun stopAndTranscribe() {
        currentState = State.TRANSCRIBING
        applyMicStyle(State.TRANSCRIBING)
        performHaptic()
        stopPulseAnimation()

        Thread {
            audioRecorder.stopRecording()
        }.start()
    }

    private fun onSamplesReady(samples: FloatArray) {
        if (samples.isEmpty()) {
            mainHandler.post {
                currentState = State.IDLE
                applyMicStyle(State.IDLE)
            }
            return
        }

        val text = whisperEngine.transcribe(samples)
        val correctedText = applyDictionary(text)

        mainHandler.post {
            currentState = State.IDLE
            applyMicStyle(State.IDLE)
            val trimmed = correctedText.trim()
            if (trimmed.isNotEmpty()) {
                injectText(trimmed)
            }
        }
    }

    private fun findFocusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root != null) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) return focused
        }

        val windowsList = windows
        if (windowsList != null) {
            for (window in windowsList) {
                val windowRoot = window.root
                if (windowRoot != null) {
                    val focused = windowRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null) return focused
                }
            }
        }
        return null
    }

    private fun injectText(text: String) {
        val focusedNode = findFocusedInput()
        if (focusedNode != null) {
            // Android 13+ (API 33+) accessibility input connection
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    val conn = inputMethod?.currentInputConnection
                    if (conn != null) {
                        conn.commitText(text, 1, null)
                        performHaptic()
                        Log.i(TAG, "Injected text via AccessibilityInputConnection: $text")
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to commit text via AccessibilityInputConnection", e)
                }
            }

            // Fallback for API < 33, or if connection was null
            val currentText = focusedNode.text ?: ""
            val start = focusedNode.textSelectionStart
            val end = focusedNode.textSelectionEnd
            val newText: String
            val newCursorPos: Int

            if (start >= 0 && end >= 0) {
                val startClamped = minOf(start, currentText.length)
                val endClamped = minOf(end, currentText.length)
                val before = currentText.substring(0, startClamped)
                val after = currentText.substring(endClamped)
                newText = "$before$text$after"
                newCursorPos = startClamped + text.length
            } else {
                val space = if (currentText.isNotEmpty() && !currentText.endsWith(" ")) " " else ""
                newText = "$currentText$space$text"
                newCursorPos = newText.length
            }

            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                val selArgs = Bundle()
                selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                performHaptic()
                Log.i(TAG, "Injected text via fallback ACTION_SET_TEXT: $text")
            } else {
                copyToClipboard(text)
            }
        } else {
            copyToClipboard(text)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OpenFree Voice", text)
        clipboard.setPrimaryClip(clip)
        Log.w(TAG, "No active focused input found; copied to clipboard: $text")
        mainHandler.post {
            Toast.makeText(applicationContext, "Copied to clipboard: $text", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyDictionary(text: String): String {
        var result = text
        val raw = prefs.getString(OpenFreeIME.KEY_DICTIONARY_MAPPINGS, "") ?: ""
        if (raw.isBlank()) return result

        val mappings = raw.split(";").mapNotNull {
            val parts = it.split("->")
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()

        for ((wrong, correct) in mappings) {
            val regex = "(?i)\\b${Pattern.quote(wrong)}\\b".toRegex()
            result = result.replace(regex, correct)
        }
        return result
    }

    // ── Visual Animations ──────────────────────────────────────────────────────

    private fun performHaptic() {
        btnFloatingMic?.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun applyMicStyle(state: State) {
        btnFloatingMic ?: return
        when (state) {
            State.IDLE -> {
                btnFloatingMic!!.backgroundTintList = null
                btnFloatingMic!!.setBackgroundResource(R.drawable.floating_mic_bg)
                btnFloatingMic!!.setImageResource(R.drawable.ic_mic)
                btnFloatingMic!!.setColorFilter(
                    ContextCompat.getColor(this, R.color.on_primary),
                    PorterDuff.Mode.SRC_IN
                )
                stopTranscribingAnimation()
            }
            State.RECORDING -> {
                btnFloatingMic!!.backgroundTintList = null
                btnFloatingMic!!.setBackgroundResource(R.drawable.floating_mic_bg_active)
                btnFloatingMic!!.setImageResource(R.drawable.ic_stop)
                btnFloatingMic!!.setColorFilter(
                    ContextCompat.getColor(this, R.color.on_secondary),
                    PorterDuff.Mode.SRC_IN
                )
                stopTranscribingAnimation()
            }
            State.TRANSCRIBING -> {
                btnFloatingMic!!.setBackgroundResource(R.drawable.floating_mic_bg)
                btnFloatingMic!!.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.gold)
                )
                btnFloatingMic!!.setImageResource(R.drawable.ic_mic)
                btnFloatingMic!!.setColorFilter(
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

        val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 1.0f, 1.4f)
        val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 1.0f, 1.4f)
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

    private fun startTranscribingAnimation() {
        stopTranscribingAnimation()
        btnFloatingMic?.let { mic ->
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
        btnFloatingMic?.alpha = 1.0f
    }

    companion object {
        private const val TAG = "FloatingOpenFree"
    }
}
