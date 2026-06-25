package se.jabba.boet.ai

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// A cleaned, ready-to-add grocery item parsed from speech.
data class VoiceItem(val name: String, val quantity: Int = 1)

// Turns raw voice transcript into a tidy shopping list using the on-device LLM:
// fixes mis-hearings ("tonjäst" -> "torrjäst"), singularizes ("citroner" -> "Citron"),
// parses quantities ("två citroner" -> qty 2), and drops anything that isn't a real
// grocery. Falls back to a simple regex split when the LLM is unavailable or unsure.
class VoiceCleaner(private val classifier: ItemClassifier?) {

    @Serializable
    private data class CleanDto(val name: String = "", val qty: Int = 1)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun clean(transcript: List<String>): List<VoiceItem> {
        val raw = transcript.joinToString(". ") { it.trim() }.trim()
        if (raw.isEmpty()) return emptyList()

        val c = classifier
        if (c != null && c.available) {
            val out = runCatching { c.generate(buildPrompt(raw)) }.getOrNull()
            val parsed = out?.let { parseJson(it) }
            if (!parsed.isNullOrEmpty()) return dedup(parsed)
            Log.w(TAG, "LLM clean returned nothing usable; falling back to regex")
        }
        return dedup(transcript.flatMap { regexItems(it) })
    }

    private fun buildPrompt(raw: String): String = buildString {
        append("Du städar en röstinspelad svensk inköpslista.\n")
        append("Råtext (kan ha taligenkänningsfel, utfyllnadsord och sånt som inte är varor):\n")
        append("\"").append(raw).append("\"\n")
        append("Regler:\n")
        append("- Behåll bara riktiga inköpsvaror (mat, dryck, hushåll). Ta bort allt annat.\n")
        append("- Rätta uppenbara taligenkänningsfel till rätt varunamn (ex: \"tonjäst\" -> \"torrjäst\").\n")
        append("- Använd singular grundform med stor första bokstav (ex: \"citroner\" -> \"Citron\").\n")
        append("- Ange antal som heltal om det sägs (ex: \"två citroner\" -> qty 2), annars 1.\n")
        append("- Slå ihop dubbletter och summera antal.\n")
        append("Svara ENBART med en JSON-array, inget annat:\n")
        append("[{\"name\":\"Citron\",\"qty\":2},{\"name\":\"Torrjäst\",\"qty\":1}]")
    }

    // Pull the first [...] block out of the model's reply and parse it leniently.
    private fun parseJson(text: String): List<VoiceItem>? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val arr = text.substring(start, end + 1)
        return runCatching {
            json.decodeFromString<List<CleanDto>>(arr)
                .mapNotNull { d ->
                    val n = d.name.trim()
                    if (n.isEmpty() || n.length > 40) null
                    else VoiceItem(n.replaceFirstChar { it.uppercaseChar() }, d.qty.coerceIn(1, 99))
                }
        }.getOrNull()
    }

    // Deterministic fallback: split one utterance into item names, no correction.
    private fun regexItems(utterance: String): List<VoiceItem> {
        var text = utterance.trim().lowercase()
        text = text.replace(Regex("^(lägg\\s+till|lägg\\s+i|addera|add|put|sätt\\s+upp)\\s+"), "")
        return text.split(Regex(",|;|\\boch\\b|\\band\\b|&|\\bplus\\b"))
            .map { it.trim().removePrefix("och ").removePrefix("and ").trim() }
            .filter { it.isNotBlank() && it.length in 1..40 }
            .map { VoiceItem(it.replaceFirstChar { c -> c.uppercaseChar() }, 1) }
    }

    // Merge same-named items (case-insensitive), summing quantities.
    private fun dedup(items: List<VoiceItem>): List<VoiceItem> {
        val order = LinkedHashMap<String, VoiceItem>()
        for (it in items) {
            val key = it.name.lowercase()
            val existing = order[key]
            order[key] = if (existing == null) it else existing.copy(quantity = (existing.quantity + it.quantity).coerceAtMost(99))
        }
        return order.values.toList()
    }

    companion object { private const val TAG = "BoetVoice" }
}
