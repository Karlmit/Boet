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
class VoiceRecognizer(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    interface Callbacks {
        fun onPartial(text: String) {}
        fun onResult(text: String)
        fun onError(code: Int)
        fun onEnd() {}
    }

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String, callbacks: Callbacks) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) callbacks.onResult(text)
                callbacks.onEnd()
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) callbacks.onPartial(text)
            }
            override fun onError(error: Int) { callbacks.onError(error); callbacks.onEnd() }
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

    fun stop() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }
}
