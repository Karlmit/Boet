package se.jabba.boet.ai

import android.content.Context

// Single place that decides which on-device classifier backend to use, keeping all
// model-specific code out of the Repository. Stage 2 returns MlKitClassifier(context)
// (ML Kit GenAI / Gemini Nano); returning null degrades gracefully to rules + Övrigt.
object ClassifierFactory {
    fun create(context: Context): ItemClassifier? = MlKitClassifier(context)
}
