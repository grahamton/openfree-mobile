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
import android.view.VelocityTracker
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.regex.Pattern
import android.os.PowerManager


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
    private var transcribingAnimator: ObjectAnimator? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var currentState = State.IDLE
    private var loadedModelPath: String? = null
    private var isDragActive = false

    private var pillContainer: View? = null
    private var tvTimer: TextView? = null
    private var waveformContainer: View? = null
    private var velocityTracker: VelocityTracker? = null
    private var lastDragX = 0
    private var recordingSeconds = 0


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
        pillContainer = floatingView.findViewById(R.id.floating_pill_container)
        tvTimer = floatingView.findViewById(R.id.tv_timer)
        waveformContainer = floatingView.findViewById(R.id.waveform_container)

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
                
                // Scale from 1.0 to 1.12 using spring/animator
                ValueAnimator.ofFloat(1.0f, 1.12f).apply {
                    duration = 200
                    interpolator = android.view.animation.OvershootInterpolator(1.2f)
                    addUpdateListener { animation ->
                        val scale = animation.animatedValue as Float
                        floatingView.scaleX = scale
                        floatingView.scaleY = scale
                        pillContainer?.elevation = (6 + (10 * (scale - 1.0f) / 0.12f)) * density
                    }
                    start()
                }

                // Dim background behind overlay to 0.7 dim amount
                windowParams?.flags = windowParams!!.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                windowParams?.dimAmount = 0.7f
                windowManager.updateViewLayout(floatingView, windowParams)
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
                    lastDragX = params.x
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragActive = false
                    
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    
                    mainHandler.postDelayed(longPressRunnable, 350)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (!isDragActive && (Math.abs(deltaX) > 15 || Math.abs(deltaY) > 15)) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    
                    velocityTracker?.addMovement(event)
                    
                    if (isDragActive) {
                        val currentX = (initialX + deltaX.toInt()).coerceIn(0, maxX)
                        params.x = currentX
                        params.y = (initialY + deltaY.toInt()).coerceIn(0, maxY)
                        
                        // CLOCK_TICK midline haptic check
                        val prevX = lastDragX
                        val midline = sw / 2
                        if ((prevX < midline && currentX >= midline) || (prevX >= midline && currentX < midline)) {
                            btnFloatingMic?.performHapticFeedback(
                                HapticFeedbackConstants.CLOCK_TICK,
                                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                            )
                        }
                        lastDragX = currentX
                        
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vX = velocityTracker?.xVelocity ?: 0f
                    val vY = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    if (isDragActive) {
                        isDragActive = false
                        btnFloatingMic?.performHapticFeedback(
                            HapticFeedbackConstants.KEYBOARD_TAP,
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                        )
                        
                        // Restore scale and elevation
                        ValueAnimator.ofFloat(floatingView.scaleX, 1.0f).apply {
                            duration = 200
                            addUpdateListener { animation ->
                                val scale = animation.animatedValue as Float
                                floatingView.scaleX = scale
                                floatingView.scaleY = scale
                                pillContainer?.elevation = (6 + (10 * (scale - 1.0f) / 0.12f)) * density
                            }
                            start()
                        }

                        // Clear background dim
                        windowParams?.flags = windowParams!!.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                        windowParams?.dimAmount = 0.0f
                        windowManager.updateViewLayout(floatingView, windowParams)

                        // Calculate target rails
                        val startX = params.x
                        val startY = params.y
                        
                        val insets = if (android.os.Build.VERSION.SDK_INT >= 30) {
                            floatingView.rootWindowInsets
                        } else {
                            null
                        }
                        val insetL = if (android.os.Build.VERSION.SDK_INT >= 30) {
                            insets?.getInsets(android.view.WindowInsets.Type.systemBars())?.left ?: 0
                        } else {
                            0
                        }
                        val insetR = if (android.os.Build.VERSION.SDK_INT >= 30) {
                            insets?.getInsets(android.view.WindowInsets.Type.systemBars())?.right ?: 0
                        } else {
                            0
                        }
                        
                        val targetLeftX = insetL + (8 * density).toInt()
                        val targetRightX = sw - w - insetR - (8 * density).toInt()
                        
                        val targetX = if (vX > 1000f) {
                            targetRightX
                        } else if (vX < -1000f) {
                            targetLeftX
                        } else {
                            if (startX + w / 2 < sw / 2) targetLeftX else targetRightX
                        }
                        
                        val statusBar = if (android.os.Build.VERSION.SDK_INT >= 30) {
                            insets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: (24 * density).toInt()
                        } else {
                            (24 * density).toInt()
                        }
                        val gestureInset = if (android.os.Build.VERSION.SDK_INT >= 30) {
                            insets?.getInsets(android.view.WindowInsets.Type.systemGestures())?.bottom ?: (48 * density).toInt()
                        } else {
                            (48 * density).toInt()
                        }
                        val minPlayY = statusBar + (16 * density).toInt()
                        val maxPlayY = sh - h - gestureInset - (72 * density).toInt()
                        val targetY = startY.coerceIn(minPlayY, maxPlayY)
                        
                        // Damped harmonic oscillator snapping
                        startSpringAnimation(startX, targetX, startY, targetY, vX, vY, params, sw, sh, w, h, maxX)
                    } else {
                        v.performClick()
                        toggleRecording()
                    }
                    true
                }
                else -> false
            }
        }

        val initialFocused = findFocusedInput()
        floatingView.visibility = if (initialFocused != null) {
            initialFocused.recycle()
            View.VISIBLE
        } else {
            View.GONE
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
        stopBlinkingAnimation()
        whisperEngine.unloadModel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val focusedNode = findFocusedInput()
        if (focusedNode != null) {
            floatingView.visibility = View.VISIBLE
            focusedNode.recycle()
        } else {
            floatingView.visibility = View.GONE
        }
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

    private val visualizerRunnable = object : Runnable {
        override fun run() {
            if (currentState == State.RECORDING) {
                val amplitude = audioRecorder.peakAmplitude
                val container = waveformContainer as? android.view.ViewGroup
                if (container != null && container.visibility == View.VISIBLE) {
                    val count = container.childCount
                    val density = resources.displayMetrics.density
                    for (i in 0 until count) {
                        val bar = container.getChildAt(i)
                        val timeFactor = System.currentTimeMillis() / 120.0
                        val wave = Math.sin(timeFactor + i * 0.6) * 0.5 + 0.5
                        val scaleFactor = 0.2 + 0.8 * amplitude * wave
                        val maxBarHeight = 24 * density
                        val newHeight = (maxBarHeight * scaleFactor).toInt().coerceAtLeast((3 * density).toInt())
                        
                        val lp = bar.layoutParams
                        lp.height = newHeight
                        bar.layoutParams = lp
                    }
                }
                mainHandler.postDelayed(this, 50)
            }
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (currentState == State.RECORDING) {
                recordingSeconds++
                val minutes = recordingSeconds / 60
                val seconds = recordingSeconds % 60
                tvTimer?.text = String.format("%d:%02d", minutes, seconds)
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun startRecording() {
        currentState = State.RECORDING
        applyMicStyle(State.RECORDING)
        performHaptic()

        tvTimer?.text = "0:00"
        recordingSeconds = 0

        morphPillWidth(true)

        audioRecorder.startRecording { samples ->
            onSamplesReady(samples)
        }

        // Apply frosted glass blur to overlay if theme is frosted
        val theme = prefs.getString("pref_key_theme", "classic") ?: "classic"
        if (theme == "frosted" && android.os.Build.VERSION.SDK_INT >= 31) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            val isPowerSave = pm?.isPowerSaveMode ?: false
            if (!isPowerSave) {
                val blurRadius = prefs.getInt("pref_key_blur_radius", 20)
                windowParams?.let { wp ->
                    wp.flags = wp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    try {
                        val field = WindowManager.LayoutParams::class.java.getField("blurBehindRadius")
                        field.setInt(wp, blurRadius)
                    } catch (_: Exception) { }
                    try { windowManager.updateViewLayout(floatingView, wp) } catch (_: Exception) { }
                }
            }
        }

        mainHandler.postDelayed(timerRunnable, 1000)
        mainHandler.post(visualizerRunnable)
    }

    private fun stopAndTranscribe() {
        currentState = State.TRANSCRIBING
        applyMicStyle(State.TRANSCRIBING)
        performHaptic()

        morphPillWidth(false)

        // Clear frosted glass blur
        clearFrostedBlur()

        Thread {
            audioRecorder.stopRecording()
        }.start()
    }

    private fun onSamplesReady(samples: FloatArray) {
        if (samples.isEmpty()) {
            mainHandler.post {
                currentState = State.IDLE
                applyMicStyle(State.IDLE)
                morphPillWidth(false)
                triggerHaptic(false)
            }
            return
        }

        val text = whisperEngine.transcribe(samples)
        val correctedText = applyDictionary(text)

        mainHandler.post {
            currentState = State.IDLE
            applyMicStyle(State.IDLE)
            morphPillWidth(false)
            clearFrostedBlur()
            val trimmed = correctedText.trim()
            if (trimmed.isNotEmpty()) {
                injectText(trimmed)
                triggerHaptic(true)
            } else {
                triggerHaptic(false)
            }
        }
    }

    private fun triggerHaptic(success: Boolean) {
        val haptic = if (success) {
            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
        btnFloatingMic?.performHapticFeedback(
            haptic,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun morphPillWidth(recording: Boolean) {
        val container = pillContainer ?: return
        val density = resources.displayMetrics.density
        val startWidth = container.width
        val targetWidth = if (recording) (168 * density).toInt() else (56 * density).toInt()
        
        if (startWidth == targetWidth) return
        
        if (recording) {
            tvTimer?.visibility = View.VISIBLE
            waveformContainer?.visibility = View.VISIBLE
        } else {
            tvTimer?.visibility = View.GONE
            waveformContainer?.visibility = View.GONE
        }

        ValueAnimator.ofInt(startWidth, targetWidth).apply {
            duration = 350
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val w = animation.animatedValue as Int
                val lp = container.layoutParams
                lp.width = w
                container.layoutParams = lp
            }
            start()
        }
    }

    private fun startSpringAnimation(startX: Int, targetX: Int, startY: Int, targetY: Int, vX: Float, vY: Float, params: WindowManager.LayoutParams, sw: Int, sh: Int, w: Int, h: Int, maxX: Int) {
        val density = resources.displayMetrics.density
        
        val insets = if (android.os.Build.VERSION.SDK_INT >= 30) {
            floatingView.rootWindowInsets
        } else {
            null
        }
        val statusBar = if (android.os.Build.VERSION.SDK_INT >= 30) {
            insets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: (24 * density).toInt()
        } else {
            (24 * density).toInt()
        }
        val gestureInset = if (android.os.Build.VERSION.SDK_INT >= 30) {
            insets?.getInsets(android.view.WindowInsets.Type.systemGestures())?.bottom ?: (48 * density).toInt()
        } else {
            (48 * density).toInt()
        }

        val minPlayY = statusBar + (16 * density).toInt()
        val maxPlayY = sh - h - gestureInset - (72 * density).toInt()

        val startTime = System.currentTimeMillis()
        // Calibrated for stiffness = 1500 (omega0 = 38.73) and damping = 0.75 (gamma = 29.05, omegaD = 25.62)
        val gamma = 29.05
        val omegaD = 25.62
        val Ax = (startX - targetX).toDouble()
        val Bx = (vX + gamma * Ax) / omegaD
        
        val Ay = (startY - targetY).toDouble()
        val By = (vY + gamma * Ay) / omegaD

        val springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            addUpdateListener { anim ->
                if (floatingView.parent == null) {
                    anim.cancel()
                    return@addUpdateListener
                }
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val multiplier = Math.exp(-gamma * elapsed)
                
                val cx = (multiplier * (Ax * Math.cos(omegaD * elapsed) + Bx * Math.sin(omegaD * elapsed)) + targetX).toInt()
                val cy = (multiplier * (Ay * Math.cos(omegaD * elapsed) + By * Math.sin(omegaD * elapsed)) + targetY).toInt()

                params.x = cx.coerceIn(0, maxX)
                params.y = cy.coerceIn(minPlayY, maxPlayY)
                
                try {
                    windowManager.updateViewLayout(floatingView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating layout in spring", e)
                }

                if (multiplier < 0.005) {
                    anim.cancel()
                }
            }
        }
        springAnimator.start()
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
            val hintText = focusedNode.hintText?.toString()
            var currentText = focusedNode.text?.toString() ?: ""
            if ((hintText != null && currentText == hintText) || 
                currentText == "RCS message" || 
                currentText == "Text message" || 
                currentText == "Type a message") {
                currentText = ""
            }

            val start = focusedNode.textSelectionStart
            val end = focusedNode.textSelectionEnd
            val newText: String
            val newCursorPos: Int

            if (currentText.isEmpty()) {
                newText = text
                newCursorPos = text.length
            } else if (start >= 0 && end >= 0) {
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

    // ── Frosted Blur ──────────────────────────────────────────────────────────

    private fun clearFrostedBlur() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            windowParams?.let { wp ->
                wp.flags = wp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                try {
                    val field = WindowManager.LayoutParams::class.java.getField("blurBehindRadius")
                    field.setInt(wp, 0)
                } catch (_: Exception) { }
                try { windowManager.updateViewLayout(floatingView, wp) } catch (_: Exception) { }
            }
        }
    }

    // ── Visual Animations ──────────────────────────────────────────────────────

    private fun performHaptic() {
        btnFloatingMic?.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun applyMicStyle(state: State) {
        val btn = btnFloatingMic ?: return
        val container = pillContainer ?: return
        val theme = prefs.getString("pref_key_theme", "classic") ?: "classic"
        val density = resources.displayMetrics.density
        
        btn.backgroundTintList = null
        btn.alpha = 1.0f
        
        // Disable individual background on the ImageButton to prevent duplicate layering over the container
        btn.setBackgroundResource(0)
        
        if (state == State.IDLE) {
            stopBlinkingAnimation()
        } else {
            startBlinkingAnimation()
        }

        // Apply theme-specific styling to the outer container and visualizer components
        when (theme) {
            "oled" -> {
                // OLED: pure black background, 1.5px solid border, 0 elevation
                container.elevation = 0f
                val strokeColor = if (state == State.RECORDING) "#7FFFCB" else "#EDEDED"
                val oledBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 28 * density
                    setColor(android.graphics.Color.parseColor("#000000"))
                    setStroke((1.5f * density).toInt(), android.graphics.Color.parseColor(strokeColor))
                }
                container.background = oledBg
                
                // Mic Button Icon Tint
                val iconColor = if (state == State.RECORDING) "#7FFFCB" else "#EDEDED"
                btn.setColorFilter(android.graphics.Color.parseColor(iconColor), PorterDuff.Mode.SRC_IN)
                btn.setImageResource(if (state == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
                
                // Timer Text Color
                tvTimer?.setTextColor(android.graphics.Color.parseColor("#EDEDED"))
                
                // Waveform bars tint
                val waveContainer = waveformContainer as? android.view.ViewGroup
                if (waveContainer != null) {
                    val count = waveContainer.childCount
                    for (i in 0 until count) {
                        waveContainer.getChildAt(i).setBackgroundColor(android.graphics.Color.parseColor("#7FFFCB"))
                    }
                }
            }
            "frosted" -> {
                // Frosted Glass: translucent dark background, accent/white border, custom shadows
                container.elevation = if (state == State.RECORDING) 10f * density else 8f * density
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val shadowColor = if (state == State.RECORDING) "#D0BCFF" else "#FFFFFF"
                    container.outlineSpotShadowColor = android.graphics.Color.parseColor(shadowColor)
                    container.outlineAmbientShadowColor = android.graphics.Color.parseColor(shadowColor)
                }
                
                val bgColor = if (state == State.RECORDING) "#8C302D3A" else "#99302D3A"
                val strokeColor = if (state == State.RECORDING) "#80D0BCFF" else "#2EFFFFFF"
                
                val glassBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 28 * density
                    setColor(android.graphics.Color.parseColor(bgColor))
                    setStroke((1 * density).toInt(), android.graphics.Color.parseColor(strokeColor))
                }
                container.background = glassBg
                
                btn.setColorFilter(android.graphics.Color.parseColor("#D0BCFF"), PorterDuff.Mode.SRC_IN)
                btn.setImageResource(if (state == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
                
                tvTimer?.setTextColor(android.graphics.Color.parseColor("#CAC4D0"))
                
                val waveContainer = waveformContainer as? android.view.ViewGroup
                if (waveContainer != null) {
                    val count = waveContainer.childCount
                    for (i in 0 until count) {
                        waveContainer.getChildAt(i).setBackgroundColor(android.graphics.Color.parseColor("#D0BCFF"))
                    }
                }
            }
            else -> {
                // Classic M3 theme
                container.elevation = 6f * density
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    container.outlineSpotShadowColor = android.graphics.Color.BLACK
                    container.outlineAmbientShadowColor = android.graphics.Color.BLACK
                }
                
                val bgRes = if (state == State.RECORDING) R.drawable.floating_mic_bg_active else R.drawable.floating_mic_bg
                container.setBackgroundResource(bgRes)
                
                val iconColorRes = if (state == State.RECORDING) R.color.on_secondary else R.color.on_primary
                btn.setColorFilter(ContextCompat.getColor(this, iconColorRes), PorterDuff.Mode.SRC_IN)
                btn.setImageResource(if (state == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
                
                val timerColorRes = if (state == State.RECORDING) R.color.on_secondary else R.color.on_primary
                tvTimer?.setTextColor(ContextCompat.getColor(this, timerColorRes))
                
                val waveContainer = waveformContainer as? android.view.ViewGroup
                if (waveContainer != null) {
                    val count = waveContainer.childCount
                    val barColor = ContextCompat.getColor(this, if (state == State.RECORDING) R.color.on_secondary else R.color.on_primary)
                    for (i in 0 until count) {
                        waveContainer.getChildAt(i).setBackgroundColor(barColor)
                    }
                }
            }
        }
    }

    private fun startBlinkingAnimation() {
        stopBlinkingAnimation()
        btnFloatingMic?.let { mic ->
            transcribingAnimator = ObjectAnimator.ofFloat(mic, "alpha", 1.0f, 0.4f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun stopBlinkingAnimation() {
        transcribingAnimator?.cancel()
        transcribingAnimator = null
        btnFloatingMic?.alpha = 1.0f
    }

    companion object {
        private const val TAG = "FloatingOpenFree"
    }
}
