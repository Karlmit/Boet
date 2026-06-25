package se.jabba.boet.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

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
    private val main = Handler(Looper.getMainLooper())
    // Force the offline model? Default false: forcing offline fails outright when the
    // language's on-device model isn't installed (the "nothing happens" symptom).
    private var preferOffline = false

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
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "no recognition service available on this device")
            callbacks?.onError(SpeechRecognizer.ERROR_CLIENT)
            callbacks?.onEnd()
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                Log.i(TAG, "onResults: '${text ?: ""}'")
                if (!text.isNullOrBlank()) callbacks?.onResult(text)
                rearm(120)
            }
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) { Log.i(TAG, "onPartial: '$text'"); callbacks?.onPartial(text) }
            }
            override fun onError(error: Int) {
                Log.w(TAG, "onError: ${errorName(error)} (continuous=$continuous)")
                // In continuous mode every error (no speech yet, timeout, busy) just
                // means "re-arm and keep listening". A short delay avoids hammering the
                // recognizer into ERROR_RECOGNIZER_BUSY.
                if (!continuous) callbacks?.onError(error)
                rearm(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 500 else 250)
            }
            override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "onReadyForSpeech") }
            override fun onBeginningOfSpeech() { Log.i(TAG, "onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.i(TAG, "onEndOfSpeech") }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (languageTag == "en") "en-US" else "sv-SE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Only hint offline when explicitly requested; forcing it breaks recognition
            // on devices that lack the language's offline pack.
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        Log.i(TAG, "startListening lang=${if (languageTag == "en") "en-US" else "sv-SE"} offline=$preferOffline")
        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "startListening threw", t)
            rearm(400)
        }
    }

    // Tear down the current recognizer and, in continuous mode, re-arm after a short
    // delay (posted to the main thread). Otherwise signal the session is done.
    private fun rearm(delayMs: Long) {
        recognizer?.destroy()
        recognizer = null
        if (continuous && !stopped) main.postDelayed({ listen() }, delayMs) else callbacks?.onEnd()
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        else -> "ERROR_$code"
    }

    private fun stopInternal() {
        main.removeCallbacksAndMessages(null)
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

    companion object { private const val TAG = "BoetVoice" }
}
