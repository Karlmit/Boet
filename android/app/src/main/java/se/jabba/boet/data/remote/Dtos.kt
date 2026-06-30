package se.jabba.boet.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import se.jabba.boet.data.local.CategoryEntity
import se.jabba.boet.data.local.FavoriteEntity
import se.jabba.boet.data.local.ItemEntity
import se.jabba.boet.data.local.ListEntity
import se.jabba.boet.data.local.RecipeEntity

@Serializable
data class MemberDto(val id: String, val name: String)

@Serializable
data class ListDto(
    val id: String,
    val name: String,
    val kind: String = "grocery",
    val icon: String? = null,
    val position: Int = 0,
    val archived: Boolean = false,
    val sortPrompt: String? = null,
    val bgImageUrl: String? = null,
    val bgBlur: Int = 0,
    val bgOverlay: Int = 0,
    val updatedAt: String? = null,
) {
    fun toEntity() = ListEntity(id, name, kind, icon, position, archived, sortPrompt, bgImageUrl, bgBlur, bgOverlay, updatedAt)
}

@Serializable
data class CategoryDto(
    val id: String,
    val listId: String,
    val name: String,
    val icon: String? = null,
    val position: Int = 0,
) {
    fun toEntity() = CategoryEntity(id, listId, name, icon, position)
}

@Serializable
data class ItemDto(
    val id: String,
    val listId: String,
    val categoryId: String? = null,
    val name: String,
    val quantity: String? = null,
    val note: String? = null,
    val checked: Boolean = false,
    val position: Int = 0,
    val addedBy: String? = null,
    val modifiedBy: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    fun toEntity() = ItemEntity(id, listId, categoryId, name, quantity, note, checked, position, addedBy, modifiedBy, createdAt, updatedAt)
}

@Serializable
data class FavoriteDto(
    val id: String,
    val name: String,
    val categoryName: String? = null,
    val position: Int = 0,
    val updatedAt: String? = null,
) {
    fun toEntity() = FavoriteEntity(id, name, categoryName, position, updatedAt)
}

// A recipe as it travels over the wire and is mirrored in Room. `name` and
// `image` are server-derived from the document for cheap grid rendering; `data`
// is the canonical recipe document (see RecipeDoc) and is stored verbatim as a
// JSON string so the Room mirror is a single row regardless of recipe size.
@Serializable
data class RecipeDto(
    val id: String,
    val name: String = "",
    val image: String? = null,
    val categoryName: String? = null,
    val position: Int = 0,
    val data: JsonObject = JsonObject(emptyMap()),
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    fun toEntity() = RecipeEntity(id, name, image, categoryName, position, data.toString(), createdAt, updatedAt)
}

// The recipe document (the JSONB `data` blob). A Boet-flavoured, camelCase
// simplification of the Mealie format. Ingredients carry a stable `id` so steps
// can reference them (ingredientRefs) for inline amounts; steps may carry a
// timer. The editor (manual create) and the AI parser both produce this shape,
// and the detail view renders it.
@Serializable
data class RecipeDoc(
    val name: String = "",
    val description: String? = null,
    val image: String? = null,
    val servings: Double? = null,
    val totalTime: String? = null,
    val sourceUrl: String? = null,
    val ingredients: List<RecipeIngredient> = emptyList(),
    val steps: List<RecipeStep> = emptyList(),
)

@Serializable
data class RecipeIngredient(
    val id: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val food: String = "",
    val display: String = "",
    val note: String? = null,
)

@Serializable
data class RecipeStep(
    val id: String,
    val text: String = "",
    val ingredientRefs: List<String> = emptyList(),
    val timerSeconds: Int? = null,
)

// Decode/encode the recipe document stored as a JSON string in RecipeEntity.data.
// Lenient on read so a document written by a newer client (extra fields) still
// loads on an older one.
object RecipeJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(data: String): RecipeDoc =
        runCatching { json.decodeFromString(RecipeDoc.serializer(), data) }.getOrDefault(RecipeDoc())
}

// Response from POST /api/recipes/parse — the AI-structured document for the
// editor to review before saving. `recipe` is null when the parser is unavailable.
@Serializable
data class RecipeParseResponse(val recipe: RecipeDoc? = null)

@Serializable
data class AddItemsRequest(val items: List<ItemDto>, val addedBy: String? = null)

@Serializable
data class AutoSortResponse(val updated: Int = 0, val items: List<ItemDto> = emptyList())

@Serializable
data class LearnedDto(val key: String, val category: String)

@Serializable
data class BootstrapDto(
    val members: List<MemberDto> = emptyList(),
    val lists: List<ListDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val items: List<ItemDto> = emptyList(),
    val learned: List<LearnedDto> = emptyList(),
    val favorites: List<FavoriteDto> = emptyList(),
    val recipes: List<RecipeDto> = emptyList(),
)

@Serializable
data class RecipeSuggestion(val name: String, val quantity: String? = null, val category: String? = null)

@Serializable
data class RecipeResponse(val suggestions: List<RecipeSuggestion> = emptyList())

// Server-side voice cleaning (POST /api/voice/clean): the server cleans the raw
// transcript with the household's local LLM and returns ready-to-add items. The
// quantity string is already composed ("2", "1 kg") — same format the app uses.
@Serializable
data class VoiceCleanItem(val name: String, val quantity: String? = null, val category: String? = null)

@Serializable
data class VoiceCleanResponse(val items: List<VoiceCleanItem> = emptyList(), val engine: String? = null)

@Serializable
data class HistoryItem(val key: String, val name: String, val count: Int = 0, val lastAdded: String? = null)

@Serializable
data class PresenceMember(
    val memberId: String? = null,
    val name: String? = null,
    val status: String? = null,
    val listId: String? = null,
)
