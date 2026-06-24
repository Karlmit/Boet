package se.jabba.boet.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.CategoryEntity
import se.jabba.boet.data.local.ItemEntity
import se.jabba.boet.data.local.ListEntity

data class CategorySection(
    val id: String?,
    val name: String,
    val items: List<ItemEntity>,
)

data class ListUiState(
    val list: ListEntity? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val sections: List<CategorySection> = emptyList(),
    val remaining: Int = 0,
    val total: Int = 0,
)

class ListViewModel(
    private val repo: Repository,
    private val listId: String,
) : ViewModel() {

    val state: StateFlow<ListUiState> = combine(
        repo.listById(listId),
        repo.categories(listId),
        repo.items(listId),
    ) { list, categories, items ->
        val byCat = items.groupBy { it.categoryId }
        val ordered = categories.sortedBy { it.position }
        val sections = buildList {
            for (cat in ordered) {
                val its = (byCat[cat.id] ?: emptyList()).sortedWith(compareBy({ it.checked }, { it.position }))
                add(CategorySection(cat.id, cat.name, its))
            }
            // Uncategorized items (no matching category).
            val orphan = items.filter { it.categoryId == null || ordered.none { c -> c.id == it.categoryId } }
            if (orphan.isNotEmpty()) add(CategorySection(null, "Övrigt", orphan.sortedWith(compareBy({ it.checked }, { it.position }))))
        }
        ListUiState(
            list = list,
            categories = ordered,
            sections = sections,
            remaining = items.count { !it.checked },
            total = items.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListUiState())

    fun addItems(text: String) {
        val names = text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return
        viewModelScope.launch { repo.addItems(listId, names.map { it to null }) }
    }

    fun addSpokenItems(names: List<String>) {
        if (names.isEmpty()) return
        viewModelScope.launch { repo.addItems(listId, names.map { it to null }) }
    }

    fun toggle(item: ItemEntity) = viewModelScope.launch { repo.toggleChecked(item) }
    fun toggleFavorite(item: ItemEntity) = viewModelScope.launch { repo.toggleFavorite(item) }
    fun delete(item: ItemEntity) = viewModelScope.launch { repo.deleteItem(item) }
    fun edit(item: ItemEntity, name: String, qty: String?, note: String?) =
        viewModelScope.launch { repo.editItem(item, name, qty, note) }
    fun move(item: ItemEntity, categoryId: String) = viewModelScope.launch { repo.moveItem(item, categoryId) }
    fun clearChecked() = viewModelScope.launch { repo.clearChecked(listId) }
    fun reorderCategories(order: List<String>) = viewModelScope.launch { repo.reorderCategories(listId, order) }
    fun addCategory(name: String) = viewModelScope.launch { repo.addCategory(listId, name) }

    companion object {
        fun factory(repo: Repository, listId: String) = viewModelFactory {
            initializer { ListViewModel(repo, listId) }
        }
    }
}
