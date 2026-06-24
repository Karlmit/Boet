package se.jabba.boet.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

// Parse a spoken utterance into item names.
// "Lägg till mjölk, ägg och bananer" -> ["mjölk","ägg","bananer"]
// "Add milk, eggs and bananas"       -> ["milk","eggs","bananas"]
fun parseSpokenItems(raw: String): List<String> {
    var text = raw.trim().lowercase()
    // Strip leading command verbs.
    text = text.replace(Regex("^(lägg\\s+till|lägg\\s+i|addera|add|put|sätt\\s+upp)\\s+"), "")
    val parts = text
        .split(Regex(",|;|\\boch\\b|\\band\\b|&|\\bplus\\b"))
        .map { it.trim().removePrefix("och ").removePrefix("and ").trim() }
        .filter { it.isNotBlank() && it.length in 1..40 }
    // Re-capitalize first letter of each.
    return parts.map { it.replaceFirstChar { c -> c.uppercaseChar() } }.distinct()
}

// Thin wrapper around the on-device SpeechRecognizer. Prefers local recognition.
// Supports a continuous mode that re-arms after each utterance until stopped
// (spec: "Continuous Voice Mode").
class VoiceRecognizer(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var continuous = false
    private var stopped = false
    private var languageTag = "sv"
    private var callbacks: Callbacks? = null

    interface Callbacks {
        fun onPartial(text: String) {}
        fun onResult(text: String)
        fun onError(code: Int)
        fun onEnd() {}
    }

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String, continuous: Boolean = false, callbacks: Callbacks) {
        stopInternal()
        stopped = false
        this.continuous = continuous
        this.languageTag = languageTag
        this.callbacks = callbacks
        listen()
    }

    private fun listen() {
        if (stopped) return
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) callbacks?.onResult(text)
                next()
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) callbacks?.onPartial(text)
            }
            override fun onError(error: Int) {
                // In continuous mode, no-match / timeout just means re-arm.
                if (continuous && !stopped &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    next()
                } else {
                    if (!continuous) callbacks?.onError(error)
                    next()
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (languageTag == "en") "en-US" else "sv-SE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    // After each utterance: re-arm in continuous mode, otherwise finish.
    private fun next() {
        recognizer?.destroy()
        recognizer = null
        if (continuous && !stopped) listen() else callbacks?.onEnd()
    }

    private fun stopInternal() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    fun stop() {
        stopped = true
        continuous = false
        stopInternal()
        callbacks?.onEnd()
    }
}
