package se.jabba.boet.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.jabba.boet.data.local.*
import se.jabba.boet.data.remote.*
import java.util.UUID

// Single source of truth. The UI always reads from Room; mutations write to Room
// optimistically, enqueue a server call in the outbox, and flush opportunistically.
class Repository(
    context: Context,
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val identityProvider: () -> String?,
    baseUrlProvider: () -> String,
) {
    private val db = BoetDatabase.get(context)
    private val listDao = db.listDao()
    private val categoryDao = db.categoryDao()
    private val itemDao = db.itemDao()
    private val outboxDao = db.outboxDao()
    private val flushMutex = Mutex()

    private val _members = MutableStateFlow<List<MemberDto>>(emptyList())
    val members: StateFlow<List<MemberDto>> = _members

    private val _presence = MutableStateFlow<List<PresenceMember>>(emptyList())
    val presence: StateFlow<List<PresenceMember>> = _presence

    val realtime = RealtimeClient(
        json = api.json,
        baseUrlProvider = baseUrlProvider,
        onChange = ::applyChange,
        onPresence = { _presence.value = it },
    )

    // Reads -----------------------------------------------------------------
    fun activeLists() = listDao.activeLists()
    fun allLists() = listDao.allLists()
    fun listById(id: String) = listDao.listById(id)
    fun categories(listId: String) = categoryDao.categoriesForList(listId)
    fun items(listId: String) = itemDao.itemsForList(listId)
    fun pendingCount() = outboxDao.count()

    suspend fun firstListId(): String? = withContext(Dispatchers.IO) { listDao.anyListId() }

    // Initial sync ----------------------------------------------------------
    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        // Push local changes first (stale ops now 4xx-drop), then pull and
        // reconcile so switching servers / a reset DB self-heals.
        flushOutbox()
        try {
            val data = api.bootstrap()
            _members.value = data.members
            listDao.upsertAll(data.lists.map { it.toEntity() })
            categoryDao.upsertAll(data.categories.map { it.toEntity() })
            itemDao.upsertAll(data.items.map { it.toEntity() })

            // Reconcile: drop anything the server no longer has.
            val listIds = data.lists.map { it.id }
            if (listIds.isEmpty()) listDao.deleteAll() else listDao.deleteNotIn(listIds)
            val catIds = data.categories.map { it.id }
            if (catIds.isEmpty()) categoryDao.deleteAll() else categoryDao.deleteNotIn(catIds)
            val itemIds = data.items.map { it.id }
            if (itemIds.isEmpty()) itemDao.deleteAll() else itemDao.deleteNotIn(itemIds)
        } catch (_: Exception) { /* offline — Room already has the last snapshot */ }
    }

    // Server-side re-categorization (online only). WS pushes the moved items back.
    suspend fun autoSort(listId: String) = withContext(Dispatchers.IO) {
        runCatching { api.send("POST", "/api/lists/$listId/autosort", null) }
    }

    // Mutations -------------------------------------------------------------
    suspend fun addItems(listId: String, names: List<Pair<String, String?>>) = withContext(Dispatchers.IO) {
        val who = identityProvider()
        val entities = names
            .filter { it.first.isNotBlank() }
            .map { (name, qty) ->
                ItemEntity(id = UUID.randomUUID().toString(), listId = listId, name = name.trim(),
                    quantity = qty, addedBy = who, modifiedBy = who)
            }
        if (entities.isEmpty()) return@withContext
        entities.forEach { itemDao.upsert(it) }
        val dtos = entities.map { ItemDto(id = it.id, listId = listId, name = it.name, quantity = it.quantity, addedBy = who) }
        val body = api.json.encodeToString(AddItemsRequest.serializer(), AddItemsRequest(dtos, who))
        enqueue("POST", "/api/lists/$listId/items", body)
    }

    suspend fun toggleChecked(item: ItemEntity) = patchItem(item.copy(checked = !item.checked), mapOf("checked" to !item.checked))
    suspend fun toggleFavorite(item: ItemEntity) = patchItem(item.copy(favorite = !item.favorite), mapOf("favorite" to !item.favorite))

    suspend fun editItem(item: ItemEntity, name: String, quantity: String?, note: String?) =
        patchItem(item.copy(name = name, quantity = quantity, note = note),
            mapOf("name" to name, "quantity" to quantity, "note" to note))

    suspend fun moveItem(item: ItemEntity, categoryId: String) =
        patchItem(item.copy(categoryId = categoryId), mapOf("categoryId" to categoryId))

    private suspend fun patchItem(updated: ItemEntity, fields: Map<String, Any?>) = withContext(Dispatchers.IO) {
        val who = identityProvider()
        itemDao.upsert(updated.copy(modifiedBy = who))
        enqueue("PATCH", "/api/items/${updated.id}", buildJson(fields + ("modifiedBy" to who)))
    }

    suspend fun deleteItem(item: ItemEntity) = withContext(Dispatchers.IO) {
        itemDao.delete(item.id)
        enqueue("DELETE", "/api/items/${item.id}", null)
    }

    suspend fun clearChecked(listId: String) = withContext(Dispatchers.IO) {
        itemDao.clearChecked(listId)
        enqueue("POST", "/api/lists/$listId/clear-checked", null)
    }

    suspend fun createList(name: String, kind: String, sortPrompt: String?) = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        listDao.upsert(ListEntity(id = id, name = name, kind = kind, sortPrompt = sortPrompt))
        enqueue("POST", "/api/lists", buildJson(mapOf("id" to id, "name" to name, "kind" to kind, "sortPrompt" to sortPrompt)))
        // categories arrive via the next bootstrap / WS once the server seeds them
        id
    }

    suspend fun setArchived(list: ListEntity, archived: Boolean) = withContext(Dispatchers.IO) {
        listDao.upsert(list.copy(archived = archived))
        if (archived) enqueue("DELETE", "/api/lists/${list.id}", null)
        else enqueue("POST", "/api/lists/${list.id}/restore", null)
    }

    suspend fun updateListBackground(list: ListEntity, url: String?, blur: Int, overlay: Int) = withContext(Dispatchers.IO) {
        listDao.upsert(list.copy(bgImageUrl = url, bgBlur = blur, bgOverlay = overlay))
        enqueue("PATCH", "/api/lists/${list.id}", buildJson(mapOf("bgImageUrl" to url, "bgBlur" to blur, "bgOverlay" to overlay)))
    }

    suspend fun addCategory(listId: String, name: String) = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        categoryDao.upsert(CategoryEntity(id = id, listId = listId, name = name))
        enqueue("POST", "/api/lists/$listId/categories", buildJson(mapOf("id" to id, "name" to name)))
    }

    suspend fun reorderCategories(listId: String, order: List<String>) = withContext(Dispatchers.IO) {
        // Persist positions locally so the UI reflects the new aisle order instantly.
        order.forEachIndexed { idx, cid -> categoryDao.setPosition(cid, idx) }
        enqueue("POST", "/api/lists/$listId/categories/reorder", buildJson(mapOf("order" to order)))
    }

    // Manual item reorder within a category (drag handle). Positions are scoped
    // per category in the UI, so writing 0..n for one category's ids is enough.
    suspend fun reorderItems(listId: String, order: List<String>) = withContext(Dispatchers.IO) {
        order.forEachIndexed { idx, id -> itemDao.setPosition(id, idx) }
        enqueue("POST", "/api/lists/$listId/items/reorder", buildJson(mapOf("order" to order)))
    }

    suspend fun renameCategory(category: CategoryEntity, name: String) = withContext(Dispatchers.IO) {
        categoryDao.upsert(category.copy(name = name))
        enqueue("PATCH", "/api/categories/${category.id}", buildJson(mapOf("name" to name)))
    }

    suspend fun deleteCategory(category: CategoryEntity) = withContext(Dispatchers.IO) {
        categoryDao.delete(category.id)
        enqueue("DELETE", "/api/categories/${category.id}", null)
    }

    fun registerDevice(token: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val body = buildJson(mapOf(
                    "token" to token,
                    "memberId" to identityProvider()?.lowercase(),
                    "platform" to "android",
                ))
                api.send("POST", "/api/devices", body)
            }
        }
    }

    suspend fun uploadBackground(listId: String, dataBase64: String, contentType: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJson(mapOf("dataBase64" to dataBase64, "contentType" to contentType))
                val resp = api.send("POST", "/api/lists/$listId/background", body)
                val dto = api.json.decodeFromString(se.jabba.boet.data.remote.ListDto.serializer(), resp)
                listDao.upsert(dto.toEntity())
                true
            }.getOrDefault(false)
        }

    suspend fun updateListDisplay(list: ListEntity, blur: Int, overlay: Int) = withContext(Dispatchers.IO) {
        listDao.upsert(list.copy(bgBlur = blur, bgOverlay = overlay))
        enqueue("PATCH", "/api/lists/${list.id}", buildJson(mapOf("bgBlur" to blur, "bgOverlay" to overlay)))
    }

    // Recipe / history (network-only helpers) -------------------------------
    suspend fun parseRecipe(text: String): List<RecipeSuggestion> = withContext(Dispatchers.IO) {
        runCatching { api.parseRecipe(text).suggestions }.getOrDefault(emptyList())
    }
    suspend fun history(): List<HistoryItem> = withContext(Dispatchers.IO) {
        runCatching { api.history() }.getOrDefault(emptyList())
    }

    // Outbox ----------------------------------------------------------------
    private suspend fun enqueue(method: String, path: String, body: String?) {
        outboxDao.enqueue(OutboxOp(method = method, path = path, body = body))
        scope.launch { flushOutbox() }
    }

    suspend fun flushOutbox() = flushMutex.withLock {
        withContext(Dispatchers.IO) {
            val ops = outboxDao.pending()
            for (op in ops) {
                try {
                    api.send(op.method, op.path, op.body)
                    outboxDao.remove(op.seq)
                } catch (e: ApiClient.HttpException) {
                    // 4xx is permanent (bad request / already gone) — drop it.
                    if (e.code in 400..499) outboxDao.remove(op.seq) else return@withContext
                } catch (_: Exception) {
                    return@withContext // network down — keep the rest queued
                }
            }
        }
    }

    // Realtime application --------------------------------------------------
    private fun applyChange(ev: ChangeEvent) {
        scope.launch(Dispatchers.IO) {
            when (ev.entity) {
                "item" -> when (ev.event) {
                    "create", "update" -> runCatching { itemDao.upsert(api.json.decodeFromString(ItemDto.serializer(), ev.data.toString()).toEntity()) }
                    "delete" -> ev.data.str("id")?.let { itemDao.delete(it) }
                    "bulk-delete" -> ev.data.str("listId")?.let { itemDao.clearChecked(it) }
                }
                "list" -> when (ev.event) {
                    "create", "update" -> runCatching { listDao.upsert(api.json.decodeFromString(ListDto.serializer(), ev.data.toString()).toEntity()) }
                }
                "category" -> when (ev.event) {
                    "create", "update" -> runCatching { categoryDao.upsert(api.json.decodeFromString(CategoryDto.serializer(), ev.data.toString()).toEntity()) }
                    "delete" -> ev.data.str("id")?.let { categoryDao.delete(it) }
                }
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun buildJson(fields: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in fields) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(k).append("\":")
            when (v) {
                null -> sb.append("null")
                is Boolean, is Int, is Long, is Double -> sb.append(v.toString())
                is List<*> -> sb.append(v.joinToString(",", "[", "]") { "\"${it.toString().replace("\"", "\\\"")}\"" })
                else -> sb.append("\"").append(v.toString().replace("\"", "\\\"")).append("\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }
}
