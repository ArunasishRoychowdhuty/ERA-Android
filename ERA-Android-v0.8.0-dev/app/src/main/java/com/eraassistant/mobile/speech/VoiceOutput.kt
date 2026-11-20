package com.eraassistant.mobile.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceOutput(context: Context) : TextToSpeech.OnInitListener {
    private val engine = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var languageTag = "bn-IN"

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) applyLanguage()
    }

    fun setLanguage(tag: String) {
        languageTag = if (tag == "en-US") "en-US" else "bn-IN"
        if (ready) applyLanguage()
    }

    private fun applyLanguage() {
        val locale = if (languageTag == "en-US") Locale.US else Locale("bn", "IN")
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
    }

    fun speak(message: String) {
        if (ready && message.isNotBlank()) {
            engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "era-${System.currentTimeMillis()}")
        }
    }

    fun stop() {
        engine.stop()
    }

    fun release() {
        engine.stop()
        engine.shutdown()
    }
}
