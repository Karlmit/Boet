package se.jabba.boet.ui.discover

import se.jabba.boet.data.remote.MealCategory
import se.jabba.boet.data.remote.MealDetail

// Process-scoped cache for MealDetail objects already fetched in this session
// (random/random-selection return full detail up front) so opening a card from
// those two surfaces never re-fetches — see /api/discover/random-selection.
// Summary-only surfaces (search/filter/category/area) aren't cached here;
// MealDetailScreen falls back to a live lookup when a meal isn't present.
object DiscoverMealCache {
    private val cache = mutableMapOf<String, MealDetail>()
    fun put(meal: MealDetail) { cache[meal.id] = meal }
    fun putAll(meals: List<MealDetail>) { meals.forEach { put(it) } }
    fun get(id: String): MealDetail? = cache[id]
}

// Process-scoped browse state — survives Compose navigating away from and back
// to DiscoverScreen (opening a meal, then pressing back). Plain `remember` state
// in the screen does NOT survive that round trip (Navigation-Compose discards the
// composition of a screen you've navigated away from), which is why the random-10
// grid used to look "re-shuffled" every time you backed out of a meal — it had
// silently refetched. Only an explicit shuffle tap should change these.
object DiscoverBrowseState {
    var randomTen: List<MealDetail>? = null
    var categories: List<MealCategory>? = null
    var areas: List<String>? = null
}

// Mirrors the server's mealdb.js splitInstructions: TheMealDB's instructions are
// prose, usually one paragraph per step separated by blank lines, but not always
// cleanly enumerated. Split on blank lines first; only sentence-split a single
// giant unsplit blob as a last resort, so a recipe never collapses into one
// unreadable step. Client-side copy purely for display before import.
fun splitMealInstructions(text: String): List<String> {
    val normalized = text.replace("\r\n", "\n")
    var lines = normalized.split(Regex("\n+"))
        .map { it.trim() }
        .map { it.replace(Regex("(?i)^\\s*(?:step\\s*)?\\d+[.):]?\\s*"), "").trim() }
        .filter { it.isNotEmpty() }
    if (lines.size <= 1 && (lines.firstOrNull()?.length ?: 0) > 300) {
        lines = normalized.split(Regex("(?<=[.!?])\\s+(?=[A-Z])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    return lines
}

// TheMealDB's ?i= ingredient filter param expects lowercase, underscore-joined
// tokens (e.g. "chicken breast" -> "chicken_breast").
fun ingredientApiToken(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), "_")
