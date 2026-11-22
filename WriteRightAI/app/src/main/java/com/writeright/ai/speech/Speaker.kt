package com.writeright.ai.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads an English word aloud with the phone's text to speech engine. */
class Speaker(context: Context) {

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
        }
    }

    init {
        tts.language = Locale.US
        tts.setSpeechRate(0.85f)
    }

    fun speak(text: String) {
        if (!ready) return
        tts.language = Locale.US
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "write-right-word")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
