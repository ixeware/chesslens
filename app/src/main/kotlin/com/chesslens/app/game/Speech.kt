package com.chesslens.app.game

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Thin TextToSpeech wrapper exposing what is currently being said (for the waveform pill). */
class Speech(context: Context) {
    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking
    private var ready = false
    private var counter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            tts.setSpeechRate(1.05f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    _speaking.value = null
                }
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    _speaking.value = null
                }
            })
        }
    }

    fun say(text: String) {
        _speaking.value = text
        if (!ready) {
            // Still show the caption briefly even without a TTS engine.
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${counter++}")
    }

    fun clearCaption() {
        _speaking.value = null
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
