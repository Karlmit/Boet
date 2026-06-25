package se.jabba.boet.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.jabba.boet.ai.VoiceItem
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.CategoryEntity
import se.jabba.boet.data.local.ItemEntity
import se.jabba.boet.data.local.ListEntity
import se.jabba.boet.data.remote.ItemDto

data class CategorySection(
    val id: String?,
    val name: String,
    val items: List<ItemEntity>,
)

data class ListUiState(
    val list: ListEntity? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val sections: List<CategorySection> = emptyList(),
    // Shopping Mode view: every item grouped under its category, checked items sunk
    // to the bottom of each group (shown struck-through in place, not hidden away).
    val shoppingSections: List<CategorySection> = emptyList(),
    val completed: List<ItemEntity> = emptyList(),   // checked items, newest first
    val remaining: Int = 0,
    val total: Int = 0,
)

// One category's worth of favorites in the quick-add sheet.
data class FavoriteSection(val category: String, val items: List<ItemDto>)

data class FavoritesUiState(
    val loading: Boolean = false,
    val sections: List<FavoriteSection> = emptyList(),
)

private const val MAX_COMPLETED = 50
private const val FAVORITES_FALLBACK_CATEGORY = "Övrigt"

class ListViewModel(
    private val repo: Repository,
    private val listId: String,
) : ViewModel() {

    val state: StateFlow<ListUiState> = combine(
        repo.listById(listId),
        repo.categories(listId),
        repo.items(listId),
    ) { list, categories, items ->
        val active = items.filter { !it.checked }
        val byCat = active.groupBy { it.categoryId }
        val ordered = categories.sortedBy { it.position }
        val sections = buildList {
            for (cat in ordered) {
                val its = (byCat[cat.id] ?: emptyList()).sortedBy { it.position }
                add(CategorySection(cat.id, cat.name, its))
            }
            // Uncategorized active items (no matching category).
            val orphan = active.filter { it.categoryId == null || ordered.none { c -> c.id == it.categoryId } }
            if (orphan.isNotEmpty()) add(CategorySection(null, "Övrigt", orphan.sortedBy { it.position }))
        }
        // Shopping Mode sections: include checked items in their own category, sunk
        // to the bottom of the group (unchecked first by position, then checked).
        val allByCat = items.groupBy { it.categoryId }
        val shoppingSections = buildList {
            for (cat in ordered) {
                val its = (allByCat[cat.id] ?: emptyList())
                    .sortedWith(compareBy({ it.checked }, { it.position }))
                add(CategorySection(cat.id, cat.name, its))
            }
            val orphan = items.filter { it.categoryId == null || ordered.none { c -> c.id == it.categoryId } }
            if (orphan.isNotEmpty()) {
                add(CategorySection(null, "Övrigt", orphan.sortedWith(compareBy({ it.checked }, { it.position }))))
            }
        }
        // Completed items live in a separate, newest-first bucket.
        val completed = items.filter { it.checked }.sortedByDescending { it.updatedAt ?: it.createdAt }
        ListUiState(
            list = list,
            categories = ordered,
            sections = sections,
            shoppingSections = shoppingSections,
            completed = completed,
            remaining = active.size,
            total = items.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListUiState())

    init {
        // Auto-prune the completed list: keep the 50 newest, delete older ones.
        viewModelScope.launch {
            state.collect { s ->
                if (s.completed.size > MAX_COMPLETED) {
                    s.completed.drop(MAX_COMPLETED).forEach { repo.deleteItem(it) }
                }
            }
        }
    }

    // Favorites quick-add sheet -------------------------------------------
    private val _favorites = MutableStateFlow(FavoritesUiState())
    val favorites: StateFlow<FavoritesUiState> = _favorites

    // Fetch favorites and group them by category name, sorted alphabetically.
    fun loadFavorites() {
        _favorites.value = _favorites.value.copy(loading = true)
        viewModelScope.launch {
            val favs = repo.favorites()
            val catNames = repo.allCategories().associate { it.id to it.name }
            val sections = favs
                .groupBy { catNames[it.categoryId] ?: FAVORITES_FALLBACK_CATEGORY }
                .toList()
                .sortedBy { it.first.lowercase() }
                .map { (cat, items) -> FavoriteSection(cat, items.sortedBy { it.name.lowercase() }) }
            _favorites.value = FavoritesUiState(loading = false, sections = sections)
        }
    }

    // Add a favorite to the current list. A favorite is just the item name; if it's
    // already on the list, its quantity is bumped by 1 rather than duplicated.
    fun addFavorite(fav: ItemDto) = viewModelScope.launch {
        repo.addOrIncrementFavorite(listId, fav.name)
    }

    fun addItems(text: String) {
        val names = text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return
        viewModelScope.launch { repo.addItems(listId, names.map { it to null }) }
    }

    fun addSpokenItems(names: List<String>) {
        if (names.isEmpty()) return
        viewModelScope.launch { repo.addItems(listId, names.map { it to null }) }
    }

    // Voice approval flow: clean a raw transcript with the on-device LLM, then add
    // the items the user approved (reusing/incrementing existing rows by name).
    suspend fun cleanSpoken(transcript: List<String>): List<VoiceItem> = repo.cleanSpoken(transcript)
    fun addVoiceItems(items: List<VoiceItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch { repo.addOrIncrementItems(listId, items) }
    }

    fun toggle(item: ItemEntity) = viewModelScope.launch { repo.toggleChecked(item) }
    fun toggleFavorite(item: ItemEntity) = viewModelScope.launch { repo.toggleFavorite(item) }
    // Live quantity update from the edit sheet; the sheet composes the freeform
    // string ("2", "1 kg", or null for a plain count of 1 / no badge).
    fun setQuantity(item: ItemEntity, quantity: String?) = viewModelScope.launch {
        repo.setQuantity(item, quantity)
    }
    fun delete(item: ItemEntity) = viewModelScope.launch { repo.deleteItem(item) }
    fun edit(item: ItemEntity, name: String, qty: String?, note: String?) =
        viewModelScope.launch { repo.editItem(item, name, qty, note) }
    fun move(item: ItemEntity, categoryId: String) = viewModelScope.launch { repo.moveItem(item, categoryId) }
    fun clearChecked() = viewModelScope.launch { repo.clearChecked(listId) }
    fun autoSort() = viewModelScope.launch { repo.autoSort(listId) }
    fun reorderItems(orderedIds: List<String>) = viewModelScope.launch { repo.reorderItems(listId, orderedIds) }
    fun reorderCategories(order: List<String>) = viewModelScope.launch { repo.reorderCategories(listId, order) }
    fun addCategory(name: String) = viewModelScope.launch { repo.addCategory(listId, name) }

    companion object {
        fun factory(repo: Repository, listId: String) = viewModelFactory {
            initializer { ListViewModel(repo, listId) }
        }
    }
}
