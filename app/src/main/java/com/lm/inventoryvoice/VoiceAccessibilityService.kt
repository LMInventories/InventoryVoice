package com.lm.inventoryvoice

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.Toast

/**
 * Single service that does everything:
 *   - Watches for InventoryBase becoming active, shows/hides the mic button
 *   - Tracks which text field is focused
 *   - Records and transcribes speech on-device
 *   - Injects the result into the focused field
 *
 * No overlay permission required — an AccessibilityService is granted
 * WindowManager access implicitly by the system once the user enables it.
 */
class VoiceAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var btnMic: ImageButton
    private lateinit var speechRecognizer: SpeechRecognizer

    private var focusedNode: AccessibilityNodeInfo? = null
    private var isListening = false
    private var inventoryBaseActive = false

    // Position of the draggable mic button
    private val overlayParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,  // No SYSTEM_ALERT_WINDOW needed
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 16
        y = 300
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupSpeechRecognizer()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        focusedNode = null
    }

    override fun onInterrupt() {
        stopListeningIfActive()
    }

    // ── Accessibility events ───────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val wasActive = inventoryBaseActive
                inventoryBaseActive = pkg == "com.radweb.ib"
                if (inventoryBaseActive && !wasActive) {
                    showOverlay()
                } else if (!inventoryBaseActive && wasActive) {
                    stopListeningIfActive()
                    hideOverlay()
                    focusedNode = null
                }
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (pkg == "com.radweb.ib") {
                    val node = event.source
                    if (node != null && node.isEditable) {
                        focusedNode?.recycle()
                        focusedNode = AccessibilityNodeInfo.obtain(node)
                    }
                }
            }
        }
    }

    // ── Overlay ────────────────────────────────────────────────────────────────

    private fun showOverlay() {
        if (overlayView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_mic, null)
        btnMic = view.findViewById(R.id.btnMic)

        var dragStartX = 0f
        var dragStartY = 0f
        var initX = 0
        var initY = 0
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    initX = overlayParams.x
                    initY = overlayParams.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (dx * dx + dy * dy > 64) {
                        dragging = true
                        overlayParams.x = initX + dx.toInt()
                        overlayParams.y = initY + dy.toInt()
                        windowManager.updateViewLayout(view, overlayParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) toggleListening()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, overlayParams)
        overlayView = view
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    // ── Speech recognition ─────────────────────────────────────────────────────

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                setMicActive(true)
            }

            override fun onEndOfSpeech() {
                setMicActive(false)
            }

            override fun onResults(results: Bundle?) {
                setMicActive(false)
                isListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    ?: return

                if (!injectText(text)) {
                    // Fallback: copy to clipboard
                    val clipboard = getSystemService(CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("voice", text)
                    )
                    Toast.makeText(
                        this@VoiceAccessibilityService,
                        "Copied to clipboard: $text",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onError(error: Int) {
                setMicActive(false)
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH       -> "Nothing heard — try again"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timed out — tap to try again"
                    SpeechRecognizer.ERROR_AUDIO          -> "Audio error"
                    SpeechRecognizer.ERROR_NETWORK        -> "Network error — enable offline speech in Settings"
                    else                                  -> "Error $error — tap to try again"
                }
                Toast.makeText(this@VoiceAccessibilityService, msg, Toast.LENGTH_SHORT).show()
            }

            override fun onBeginningOfSpeech()                     {}
            override fun onRmsChanged(rmsdB: Float)                {}
            override fun onBufferReceived(buffer: ByteArray?)      {}
            override fun onPartialResults(partial: Bundle?)        {}
            override fun onEvent(eventType: Int, params: Bundle?)  {}
        })
    }

    private fun toggleListening() {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
            setMicActive(false)
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }
            isListening = true
            speechRecognizer.startListening(intent)
        }
    }

    private fun stopListeningIfActive() {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
            setMicActive(false)
        }
    }

    // ── Text injection ─────────────────────────────────────────────────────────

    private fun injectText(text: String): Boolean {
        val node = focusedNode ?: return false
        return try {
            val existing = node.text?.toString()?.trimEnd() ?: ""
            val combined = if (existing.isEmpty()) text else "$existing $text"
            val args = Bundle().apply {
                putString(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    combined
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            false
        }
    }

    private fun setMicActive(active: Boolean) {
        overlayView?.post {
            if (::btnMic.isInitialized) btnMic.isActivated = active
        }
    }
}
