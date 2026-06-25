package se.jabba.boet.ai

import se.jabba.boet.data.local.CategoryDao
import se.jabba.boet.data.local.LearnedDao

// Provider-agnostic on-device classifier seam. The ML Kit / Gemini Nano backend
// implements this in its own file (MlKitClassifier) so all model-specific code is
// isolated; when no capable backend is present the engine simply skips this step.
interface ItemClassifier {
    // Whether a model is currently usable (probed lazily, cheap to read).
    val available: Boolean
    // Pick the best-fitting category *name* for [name] from [categories], or null.
    suspend fun classify(name: String, categories: List<String>): String?
    // Free-form on-device generation for a single prompt; null if unavailable.
    suspend fun generate(prompt: String): String? = null
    // Optional: ensure the model is downloaded/ready. Returns true if usable.
    suspend fun warmUp(): Boolean = available
    // Human-readable status for on-device diagnostics ("AVAILABLE"/"DOWNLOADABLE"/…).
    suspend fun status(): String = if (available) "AVAILABLE" else "UNAVAILABLE"
}

// Decides an item's category, in priority order:
//   1. learned household mapping  (synced from server; a human correction)
//   2. keyword KB                 (fast, offline, covers the common stuff)
//   3. on-device LLM              (ONLY for items 1+2 couldn't place — "runs only when necessary")
//   4. Övrigt                     (fallback)
class CategoryEngine(
    private val categoryDao: CategoryDao,
    private val learnedDao: LearnedDao,
    private val classifier: ItemClassifier?,
) {
    // categoryId may be null if the list has no categories at all. `confident` is
    // false only for the Övrigt fallback — that's the signal the LLM should weigh in.
    data class Guess(val categoryId: String?, val categoryName: String, val confident: Boolean)

    val llmAvailable: Boolean get() = classifier?.available == true

    suspend fun warmUpLlm(): Boolean = classifier?.warmUp() ?: false
    suspend fun llmStatus(): String = classifier?.status() ?: "NO_CLASSIFIER"

    // Fast, fully-offline guess: learned -> keyword -> Övrigt. Never touches the LLM.
    suspend fun guess(listId: String, name: String): Guess {
        val cats = categoryDao.categoriesForListOnce(listId)
        if (cats.isEmpty()) return Guess(null, Categorizer.OTHER, false)
        val byName = cats.associateBy { it.name.lowercase() }

        val key = Categorizer.normalizeKey(name)
        if (key.isNotEmpty()) {
            val learned = learnedDao.categoryFor(key)
            val id = learned?.let { byName[it.lowercase()]?.id }
            if (id != null) return Guess(id, learned, true)
        }

        val kw = Categorizer.keywordGuess(name)
        if (kw != null) {
            val id = byName[kw.lowercase()]?.id
            if (id != null) return Guess(id, kw, true)
        }

        val otherId = byName[Categorizer.OTHER.lowercase()]?.id ?: cats.last().id
        return Guess(otherId, Categorizer.OTHER, false)
    }

    // On-device LLM resolution for an uncertain item. Returns a category id within
    // [listId], or null if the model is unavailable / declines to answer.
    suspend fun llmCategoryId(listId: String, name: String): String? {
        val c = classifier ?: return null
        val cats = categoryDao.categoriesForListOnce(listId)
        if (cats.isEmpty()) return null
        val picked = c.classify(name, cats.map { it.name }) ?: return null
        return cats.firstOrNull { it.name.equals(picked, ignoreCase = true) }?.id
    }
}
