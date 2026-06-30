package se.jabba.boet.ui.recipes

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// On-device OCR: read the text out of a picked image so the "recipe from photo"
// path can feed the same server AI parser as pasted text. Returns "" on any
// failure (unreadable image, no text) so the caller degrades gracefully.
suspend fun recognizeText(context: Context, uri: Uri): String =
    suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(context, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { cont.resume("") }
        } catch (e: Exception) {
            cont.resume("")
        }
    }
