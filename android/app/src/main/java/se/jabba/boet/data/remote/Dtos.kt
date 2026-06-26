package se.jabba.boet.data.remote

import kotlinx.serialization.Serializable
import se.jabba.boet.data.local.CategoryEntity
import se.jabba.boet.data.local.FavoriteEntity
import se.jabba.boet.data.local.ItemEntity
import se.jabba.boet.data.local.ListEntity

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
    val position: Int = 0,
) {
    fun toEntity() = CategoryEntity(id, listId, name, position)
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
