package com.lm.inventoryvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Foreground service that:
 *   - Shows a persistent notification (required to keep overlay alive)
 *   - Draws a draggable floating mic button over all other apps
 *   - On tap: records speech → transcribes on-device → injects into focused InventoryBase field
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "inventory_voice_overlay"
        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var btnMic: ImageButton
    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening = false

    // WindowManager layout params for the floating button
    private val overlayParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 16
        y = 200
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupOverlay()
        setupSpeechRecognizer()
    }

    // ── Overlay setup ──────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView   = LayoutInflater.from(this).inflate(R.layout.overlay_mic, null)
        btnMic        = overlayView.findViewById(R.id.btnMic)

        // Drag support: move the button around the screen
        var dragStartX = 0f
        var dragStartY = 0f
        var initialParamX = 0
        var initialParamY = 0
        var isDragging = false

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX  = event.rawX
                    dragStartY  = event.rawY
                    initialParamX = overlayParams.x
                    initialParamY = overlayParams.y
                    isDragging  = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        isDragging = true
                        overlayParams.x = initialParamX + dx.toInt()
                        overlayParams.y = initialParamY + dy.toInt()
                        windowManager.updateViewLayout(overlayView, overlayParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap — toggle listening
                        toggleListening()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, overlayParams)
    }

    // ── Speech recognition ─────────────────────────────────────────────────────

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                setMicActive(true)
            }

            override fun onResults(results: Bundle?) {
                setMicActive(false)
                isListening = false

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text    = matches?.firstOrNull()?.trim() ?: return

                // Try to append to existing field content; fall back to set
                val success = VoiceAccessibilityService.appendText(text)

                if (!success) {
                    // No focused field detected — copy to clipboard as fallback
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("transcription", text)
                    )
                    Toast.makeText(
                        this@OverlayService,
                        "No field focused — copied: $text",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onError(error: Int) {
                setMicActive(false)
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH       -> "No speech detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out"
                    SpeechRecognizer.ERROR_AUDIO          -> "Audio error"
                    SpeechRecognizer.ERROR_NETWORK        -> "Network error (try offline model)"
                    else                                  -> "Recognition error ($error)"
                }
                Toast.makeText(this@OverlayService, msg, Toast.LENGTH_SHORT).show()
            }

            // Required but unused callbacks
            override fun onBeginningOfSpeech()                              {}
            override fun onRmsChanged(rmsdB: Float)                         {}
            override fun onBufferReceived(buffer: ByteArray?)               {}
            override fun onEndOfSpeech()                                    { setMicActive(false) }
            override fun onPartialResults(partialResults: Bundle?)          {}
            override fun onEvent(eventType: Int, params: Bundle?)           {}
        })
    }

    private fun toggleListening() {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
            setMicActive(false)
        } else {
            startListening()
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Prefer on-device recognition when available
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        isListening = true
        speechRecognizer.startListening(intent)
    }

    private fun setMicActive(active: Boolean) {
        btnMic.post {
            btnMic.isActivated = active
        }
    }

    // ── Notification (required for foreground service) ─────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::overlayView.isInitialized && ::windowManager.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
