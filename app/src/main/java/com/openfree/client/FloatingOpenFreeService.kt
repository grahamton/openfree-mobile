package com.openfree.client

import android.accessibilityservice.AccessibilityService
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
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
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
class FloatingOpenFreeService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var btnFloatingMic: ImageButton? = null
    private var pulseRing: View? = null
    private var pulseAnimator: android.animation.AnimatorSet? = null

    private var isRecording = false
    private var isTranscribing = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(OpenFreeIME.PREFS_NAME, MODE_PRIVATE)
        audioRecorder = AudioRecorder()
        whisperEngine = WhisperEngine(applicationContext)
        loadModel()
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

        // Initial gravity and offsets (Bottom Right)
        params.gravity = Gravity.BOTTOM or Gravity.END
        params.x = 50
        params.y = 350

        // Implement Drag-to-Move gesture & Click Toggle
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        btnFloatingMic?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        // Under Gravity.BOTTOM or Gravity.END, moving left increases params.x, moving up increases params.y
                        params.x = initialX + (initialTouchX - event.rawX).toInt()
                        params.y = initialY + (initialTouchY - event.rawY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        // Process single tap
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
        if (::floatingView.isInitialized && floatingView.parent != null) {
            windowManager.removeView(floatingView)
        }
        if (isRecording) {
            audioRecorder.stopRecording()
        }
        stopPulseAnimation()
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
        Thread {
            whisperEngine.loadModel(modelPath)
        }.start()
    }

    private fun toggleRecording() {
        if (isTranscribing) return
        if (isRecording) stopAndTranscribe() else startRecording()
    }

    private fun startRecording() {
        isRecording = true
        applyMicStyle(recording = true)
        startPulseAnimation()

        audioRecorder.startRecording { samples ->
            onSamplesReady(samples)
        }
    }

    private fun stopAndTranscribe() {
        isRecording = false
        isTranscribing = true
        applyMicStyle(recording = false)
        stopPulseAnimation()

        Thread {
            audioRecorder.stopRecording()
        }.start()
    }

    private fun onSamplesReady(samples: FloatArray) {
        if (samples.isEmpty()) {
            mainHandler.post {
                isTranscribing = false
            }
            return
        }

        val text = whisperEngine.transcribe(samples)
        val correctedText = applyDictionary(text)

        mainHandler.post {
            isTranscribing = false
            val trimmed = correctedText.trim()
            if (trimmed.isNotEmpty()) {
                injectText(trimmed)
            }
        }
    }

    private fun injectText(text: String) {
        val rootNode = rootInActiveWindow ?: return
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val currentText = focusedNode.text ?: ""
            val space = if (currentText.isNotEmpty() && !currentText.endsWith(" ")) " " else ""
            val newText = "$currentText$space$text"
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.i(TAG, "Injected text successfully: $newText")
        } else {
            // Fallback: Copy to clipboard if text field not found
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OpenFree Voice", text)
            clipboard.setPrimaryClip(clip)
            Log.w(TAG, "No active focused input found; copied to clipboard: $text")
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

    private fun applyMicStyle(recording: Boolean) {
        btnFloatingMic ?: return
        if (recording) {
            btnFloatingMic!!.setBackgroundResource(R.drawable.mic_button_bg_active)
            btnFloatingMic!!.setImageResource(R.drawable.ic_stop)
            btnFloatingMic!!.setColorFilter(
                ContextCompat.getColor(this, R.color.on_secondary),
                PorterDuff.Mode.SRC_IN
            )
        } else {
            btnFloatingMic!!.setBackgroundResource(R.drawable.mic_button_bg)
            btnFloatingMic!!.setImageResource(R.drawable.ic_mic)
            btnFloatingMic!!.setColorFilter(
                ContextCompat.getColor(this, R.color.on_primary),
                PorterDuff.Mode.SRC_IN
            )
        }
    }

    private fun startPulseAnimation() {
        val ring = pulseRing ?: return
        ring.visibility = View.VISIBLE
        ring.alpha = 0.6f
        ring.scaleX = 1.0f
        ring.scaleY = 1.0f

        val scaleX = android.animation.ObjectAnimator.ofFloat(ring, "scaleX", 1.0f, 1.4f)
        val scaleY = android.animation.ObjectAnimator.ofFloat(ring, "scaleY", 1.0f, 1.4f)
        val alpha = android.animation.ObjectAnimator.ofFloat(ring, "alpha", 0.6f, 0.0f)

        scaleX.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleX.repeatMode = android.animation.ValueAnimator.RESTART
        scaleY.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleY.repeatMode = android.animation.ValueAnimator.RESTART
        alpha.repeatCount = android.animation.ValueAnimator.INFINITE
        alpha.repeatMode = android.animation.ValueAnimator.RESTART

        pulseAnimator = android.animation.AnimatorSet().apply {
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

    companion object {
        private const val TAG = "FloatingOpenFree"
    }
}
