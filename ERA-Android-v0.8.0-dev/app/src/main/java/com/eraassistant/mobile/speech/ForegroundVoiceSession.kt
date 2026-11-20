package com.eraassistant.mobile.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * User-started continuous speech session. It must be stopped when the Activity
 * leaves the foreground; this is not an always-on wake-word service.
 */
class ForegroundVoiceSession(
    private val context: Context,
    private val onCommand: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var language = "bn-IN"
    private var consecutiveErrors = 0
    private val restart = Runnable { startListeningInternal() }

    fun start(languageTag: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("এই ফোনে speech recognition service পাওয়া যায়নি।")
            return
        }
        language = if (languageTag == "en-US") "en-US" else "bn-IN"
        active = true
        consecutiveErrors = 0
        runCatching {
            ensureRecognizer()
            startListeningInternal()
        }.onFailure {
            active = false
            runCatching { recognizer?.destroy() }
            recognizer = null
            onStatus("Voice session শুরু করা যায়নি: ${it.message ?: "Unknown error"}")
            return
        }
        onStatus("Foreground voice session চালু। ERA foreground-এ থাকলেই শুনবে।")
    }

    fun stop() {
        active = false
        handler.removeCallbacks(restart)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        onStatus("Voice session বন্ধ।")
    }

    fun isActive(): Boolean = active

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onStatus("শুনছি… বলো কী করতে হবে।")
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    consecutiveErrors = 0
                    if (!spoken.isNullOrBlank() && active) onCommand(spoken)
                    scheduleRestart(650L)
                }

                override fun onError(error: Int) {
                    if (!active) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stop()
                        onStatus("Voice session-এর জন্য microphone permission দরকার।")
                    } else {
                        consecutiveErrors += 1
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            stop()
                            onStatus("Voice recognition বারবার ব্যর্থ হয়েছে। Session বন্ধ; আবার নিজে Start করো।")
                        } else {
                            onStatus("Voice recognition আবার চেষ্টা করছে (error $error)…")
                            scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 1000L)
                        }
                    }
                }
            })
        }
    }

    private fun startListeningInternal() {
        if (!active) return
        ensureRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure {
                onStatus("Speech recognition শুরু করা যায়নি: ${it.message ?: "Unknown error"}")
                scheduleRestart(1200L)
            }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!active) return
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMs)
    }

    private companion object { const val MAX_CONSECUTIVE_ERRORS = 5 }
}
