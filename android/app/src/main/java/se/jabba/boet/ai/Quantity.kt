package se.jabba.boet.ai

// Shared, single source of truth for the freeform `quantity` string used across
// voice capture, list display, and the edit sheet. A quantity is either a bare
// count ("2") or "<number> <unit>" ("1 kg", "250 g", "2 dl"). A count of 1 is
// stored as null (no badge) — matching the original count-only behaviour.

// Grocery-friendly subset of the unit vocabulary in server/src/ai.js (qtyRe).
// Order is the order the unit chips render in the edit sheet. Easily extensible.
val UNITS = listOf("kg", "g", "hg", "l", "dl", "paket")

// Parsed amount; unit == null means a plain count.
data class Amount(val value: Double, val unit: String?)

// Parse a stored quantity string into a number + optional unit. Accepts the
// Swedish decimal comma. Anything unrecognised falls back to a count of 1, so
// the edit sheet always has a sane starting point.
fun parseQuantity(s: String?): Amount {
    val t = s?.trim().orEmpty()
    if (t.isEmpty()) return Amount(1.0, null)
    val m = Regex("""^\s*([\d]+(?:[.,]\d+)?)\s*([\p{L}]+)?\s*$""").find(t)
        ?: return Amount(1.0, null)
    val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 1.0
    val rawUnit = m.groupValues[2].lowercase().ifBlank { null }
    val unit = rawUnit?.takeIf { it in UNITS }
    return Amount(value, unit)
}

// Format a number for display: drop a trailing .0, use the Swedish decimal comma.
fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else value.toString().replace('.', ',')

// Build the stored quantity string. Counts: >1 -> "N", otherwise null (no badge).
// Measures: always "<number> <unit>" (e.g. "10 g"), so small amounts survive.
fun composeQuantity(value: Double, unit: String?): String? {
    val u = unit?.takeIf { it in UNITS }
    if (u == null) return if (value > 1.0) formatNumber(value) else null
    return "${formatNumber(value)} $u"
}

// Merge two quantities when the same item is added twice (voice dedup, or adding
// onto an existing list item). Both bare counts -> sum. If either side carries a
// unit, prefer the incoming measure, else keep the existing one — we never try to
// add across units (no "1 kg" + "2" = "3 kg" nonsense).
fun mergeQuantity(existing: String?, incoming: String?): String? {
    val a = parseQuantity(existing)
    val b = parseQuantity(incoming)
    return when {
        b.unit != null -> composeQuantity(b.value, b.unit)
        a.unit != null -> composeQuantity(a.value, a.unit)
        else -> composeQuantity(a.value + b.value, null)
    }
}
