package se.jabba.boet.ai

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel

// On-device LLM categorizer backed by ML Kit GenAI (Gemini Nano via AICore).
// Runs fully on-device — no cloud, nothing leaves the phone — and only for items
// the deterministic layers couldn't place (see CategoryEngine). Gracefully reports
// UNAVAILABLE on devices without AICore/Gemini Nano, in which case the engine just
// falls back to Övrigt. All ML Kit symbols are confined to this file.
class MlKitClassifier(private val context: Context) : ItemClassifier {

    private val model: GenerativeModel by lazy { Generation.getClient() }

    @Volatile private var lastAvailable = false
    override val available: Boolean get() = lastAvailable

    override suspend fun status(): String = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> { lastAvailable = true; "AVAILABLE" }
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            else -> "UNAVAILABLE"
        }
    } catch (t: Throwable) {
        Log.w(TAG, "checkStatus failed", t)
        "ERROR: ${t.message}"
    }

    override suspend fun warmUp(): Boolean = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> { lastAvailable = true; true }
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                runCatching { model.download().collect { } }
                (model.checkStatus() == FeatureStatus.AVAILABLE).also { lastAvailable = it }
            }
            else -> { lastAvailable = false; false }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "warmUp failed", t)
        lastAvailable = false
        false
    }

    override suspend fun generate(prompt: String): String? {
        if (!available && !warmUp()) return null
        return try {
            val response = model.generateContent(prompt)
            response.candidates.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "generateContent failed", t)
            null
        }
    }

    override suspend fun classify(name: String, categories: List<String>): String? {
        val prompt = buildString {
            append("Du sorterar varor i en svensk inköpslista.\n")
            append("Kategorier: ").append(categories.joinToString(", ")).append(".\n")
            append("Vilken kategori passar bäst för varan \"").append(name).append("\"?\n")
            append("Svara med EXAKT ett kategorinamn från listan ovan, inget annat.")
        }
        val text = generate(prompt) ?: return null
        // The model may add punctuation/extra words — map its answer back to a
        // real category by exact match first, then substring.
        return categories.firstOrNull { it.equals(text, ignoreCase = true) }
            ?: categories.firstOrNull { text.contains(it, ignoreCase = true) }
    }

    companion object { private const val TAG = "BoetLLM" }
}
