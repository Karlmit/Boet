package se.jabba.boet.ai

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// A cleaned, ready-to-add grocery item parsed from speech. `quantity` is the
// freeform amount string ("2", "1 kg", "10 g") or null for a plain count of 1.
data class VoiceItem(val name: String, val quantity: String? = null)

// Turns raw voice transcript into a tidy shopping list: fixes mis-hearings
// ("tonjäst" -> "torrjäst"), singularizes ("citroner" -> "Citron"), parses amounts
// ("två citroner" -> qty 2; "ett kilo fläsk" -> 1 kg), and drops anything that isn't
// a real grocery.
//
// Cleaning is tried in order: (1) the server's household-local LLM, so every phone
// gets the same quality even without an on-device model (Klara's Samsung S24 has no
// Gemini Nano); (2) the on-device LLM if present and the server is unreachable;
// (3) a deterministic regex split offline. `serverClean` returns null when the
// server is unreachable/offline so we degrade gracefully.
class VoiceCleaner(
    private val classifier: ItemClassifier?,
    private val serverClean: (suspend (List<String>) -> List<VoiceItem>?)? = null,
) {

    @Serializable
    private data class CleanDto(val name: String = "", val qty: String = "1", val unit: String = "")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun clean(transcript: List<String>): List<VoiceItem> {
        val raw = transcript.joinToString(". ") { it.trim() }.trim()
        if (raw.isEmpty()) return emptyList()

        // 1. Server-side local LLM — consistent quality on every device.
        val sc = serverClean
        if (sc != null) {
            val server = runCatching { sc(transcript) }.getOrNull()
            if (!server.isNullOrEmpty()) return dedup(server)
            Log.w(TAG, "server clean unavailable; trying on-device")
        }

        // 2. On-device LLM (Gemini Nano) if the server couldn't help.
        val c = classifier
        if (c != null && c.available) {
            val out = runCatching { c.generate(buildPrompt(raw)) }.getOrNull()
            val parsed = out?.let { parseJson(it) }
            if (!parsed.isNullOrEmpty()) return dedup(parsed)
            Log.w(TAG, "LLM clean returned nothing usable; falling back to regex")
        }

        // 3. Deterministic split (offline / no model anywhere).
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
        append("- \"qty\" = antalet/mängden som siffra om det sägs (ex: \"två\" -> \"2\", \"tio\" -> \"10\"), annars \"1\".\n")
        append("- \"unit\" = enheten om en vikt/volym/förpackning sägs, en av [kg, g, hg, l, dl, paket]. Annars tom sträng \"\".\n")
        append("  Ex: \"ett kilo fläsk\" -> qty \"1\", unit \"kg\". \"tio gram saffran\" -> qty \"10\", unit \"g\". \"två äpplen\" -> qty \"2\", unit \"\".\n")
        append("- Slå ihop dubbletter (summera bara rena antal, inte vikter/volymer).\n")
        append("Svara ENBART med en JSON-array, inget annat:\n")
        append("[{\"name\":\"Fläsk\",\"qty\":\"1\",\"unit\":\"kg\"},{\"name\":\"Saffran\",\"qty\":\"10\",\"unit\":\"g\"},{\"name\":\"Äpple\",\"qty\":\"2\",\"unit\":\"\"}]")
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
                    else {
                        val value = (d.qty.replace(',', '.').toDoubleOrNull() ?: 1.0).coerceIn(0.0, 9999.0)
                        val unit = d.unit.trim().lowercase().takeIf { it in UNITS }
                        VoiceItem(n.replaceFirstChar { it.uppercaseChar() }, composeQuantity(value, unit))
                    }
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
            .map { VoiceItem(it.replaceFirstChar { c -> c.uppercaseChar() }) }
    }

    // Merge same-named items (case-insensitive). Counts sum; units don't (see
    // mergeQuantity).
    private fun dedup(items: List<VoiceItem>): List<VoiceItem> {
        val order = LinkedHashMap<String, VoiceItem>()
        for (it in items) {
            val key = it.name.lowercase()
            val existing = order[key]
            order[key] = if (existing == null) it
                else existing.copy(quantity = mergeQuantity(existing.quantity, it.quantity))
        }
        return order.values.toList()
    }

    companion object { private const val TAG = "BoetVoice" }
}
