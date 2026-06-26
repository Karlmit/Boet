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
import se.jabba.boet.ai.CategoryEngine
import se.jabba.boet.ai.ClassifierFactory
import se.jabba.boet.ai.VoiceCleaner
import se.jabba.boet.ai.VoiceItem
import se.jabba.boet.ai.mergeQuantity
import se.jabba.boet.data.local.*
import se.jabba.boet.data.remote.*
import java.util.UUID

data class AutoSortResult(
    val updated: Int = 0,
    val ok: Boolean = true,
)

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
    private val favoriteDao = db.favoriteDao()
    private val learnedDao = db.learnedDao()
    private val outboxDao = db.outboxDao()
    private val flushMutex = Mutex()

    // On-device categorization (learned mapping -> keyword KB -> on-device LLM -> Övrigt).
    private val classifier = ClassifierFactory.create(context)
    private val engine = CategoryEngine(categoryDao, learnedDao, classifier)
    // Cleans raw voice transcript into tidy grocery items: prefers the server's
    // household-local LLM (works on every phone), falls back to the on-device LLM,
    // then a deterministic split. The server lambda returns null when offline so
    // the on-device path takes over.
    private val voiceCleaner = VoiceCleaner(classifier) { transcript, categoryNames ->
        withContext(Dispatchers.IO) {
            runCatching { api.cleanVoice(transcript, categoryNames).items.map { VoiceItem(it.name, it.quantity, it.category) } }
                .getOrNull()
        }
    }

    init {
        // Probe / warm up the on-device LLM in the background and log its status so
        // device capability (e.g. Gemini Nano on this phone) is visible in logcat.
        scope.launch(Dispatchers.Default) {
            runCatching { android.util.Log.i("BoetLLM", "on-device classifier status=${engine.llmStatus()}") }
        }
    }
    // Serializes favorite add/increment so rapid taps don't create duplicates.
    private val favoriteMutex = Mutex()

    private val _members = MutableStateFlow<List<MemberDto>>(emptyList())
    val members: StateFlow<List<MemberDto>> = _members

    private val _presence = MutableStateFlow<List<PresenceMember>>(emptyList())
    val presence: StateFlow<List<PresenceMember>> = _presence

    val realtime = RealtimeClient(
        json = api.json,
        baseUrlProvider = baseUrlProvider,
        onChange = ::applyChange,
        onPresence = { _presence.value = it },
        // After a dropped socket reconnects, re-pull a full snapshot so changes the
        // other member made during the gap (never delivered over the live stream)
        // are reconciled instead of waiting for the next app restart.
        onReconnect = { scope.launch { bootstrap() } },
    )

    // Reads -----------------------------------------------------------------
    fun activeLists() = listDao.activeLists()
    fun allLists() = listDao.allLists()
    fun listById(id: String) = listDao.listById(id)
    fun categories(listId: String) = categoryDao.categoriesForList(listId)
    fun items(listId: String) = itemDao.itemsForList(listId)
    fun favorites() = favoriteDao.favorites()
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
            favoriteDao.upsertAll(data.favorites.map { it.toEntity() })

            // Refresh the on-device learned-mappings mirror (small table; replace wholesale).
            learnedDao.deleteAll()
            learnedDao.upsertAll(data.learned.map { LearnedCategoryEntity(it.key, it.category) })

            // Reconcile: drop anything the server no longer has.
            val listIds = data.lists.map { it.id }
            if (listIds.isEmpty()) listDao.deleteAll() else listDao.deleteNotIn(listIds)
            val catIds = data.categories.map { it.id }
            if (catIds.isEmpty()) categoryDao.deleteAll() else categoryDao.deleteNotIn(catIds)
            val itemIds = data.items.map { it.id }
            if (itemIds.isEmpty()) itemDao.deleteAll() else itemDao.deleteNotIn(itemIds)
            val favIds = data.favorites.map { it.id }
            if (favIds.isEmpty()) favoriteDao.deleteAll() else favoriteDao.deleteNotIn(favIds)
        } catch (_: Exception) { /* offline — Room already has the last snapshot */ }
    }

    // Server-backed AI re-sort. Network-only by design; websocket/bootstrap will
    // also reconcile the same updates idempotently.
    suspend fun autoSort(listId: String): AutoSortResult = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.autoSort(listId)
            response.items.forEach { itemDao.upsert(it.toEntity()) }
            AutoSortResult(updated = response.updated, ok = true)
        }.getOrElse {
            AutoSortResult(ok = false)
        }
    }

    // Mutations -------------------------------------------------------------
    suspend fun addItems(
        listId: String,
        names: List<Pair<String, String?>>,
        categoryHints: Map<String, String?> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val who = identityProvider()
        val categories = categoryDao.categoriesForListOnce(listId)
        val categoriesByName = categories.associateBy { it.name.trim().lowercase() }
        val hintsByName = categoryHints.mapKeys { it.key.trim().lowercase() }
        // Categorize each new item on-device, immediately, so it lands in the right
        // group even offline (the server respects a client-provided categoryId).
        val hintedIds = mutableSetOf<String>()
        val entities = names
            .filter { it.first.isNotBlank() }
            .map { (name, qty) ->
                val trimmed = name.trim()
                val hintedCategoryId = hintsByName[trimmed.lowercase()]
                    ?.trim()
                    ?.lowercase()
                    ?.let { categoriesByName[it]?.id }
                if (hintedCategoryId != null) hintedIds += trimmed.lowercase()
                val guessedCategoryId = hintedCategoryId ?: engine.guess(listId, trimmed).categoryId
                ItemEntity(id = UUID.randomUUID().toString(), listId = listId, name = trimmed,
                    quantity = qty, categoryId = guessedCategoryId, addedBy = who, modifiedBy = who)
            }
        if (entities.isEmpty()) return@withContext
        entities.forEach { itemDao.upsert(it) }
        val dtos = entities.map { ItemDto(id = it.id, listId = listId, categoryId = it.categoryId, name = it.name, quantity = it.quantity, addedBy = who) }
        val body = api.json.encodeToString(AddItemsRequest.serializer(), AddItemsRequest(dtos, who))
        enqueue("POST", "/api/lists/$listId/items", body)
        // A server voice category is trusted AI knowledge. The add POST stores the
        // category id, and this normal PATCH path teaches the server mapping so
        // future typed/offline adds resolve the same way household-wide.
        entities
            .filter { it.categoryId != null && it.name.trim().lowercase() in hintedIds }
            .forEach {
                enqueue("PATCH", "/api/items/${it.id}", buildJson(mapOf("categoryId" to it.categoryId, "modifiedBy" to who)))
            }

        // Background upgrade: for items the KB couldn't confidently place, ask the
        // on-device LLM and quietly move them if it has a better answer.
        if (engine.llmAvailable) {
            scope.launch(Dispatchers.Default) {
                for (e in entities) {
                    if (e.name.trim().lowercase() in hintedIds) continue
                    val g = engine.guess(listId, e.name)
                    if (g.confident) continue
                    val better = runCatching { engine.llmCategoryId(listId, e.name) }.getOrNull()
                    if (better != null && better != e.categoryId) {
                        itemDao.byId(e.id)?.let { latest -> autoMove(latest, better) }
                    }
                }
            }
        }
    }

    // Move an item to a category as an automatic decision (not a human correction):
    // updates Room and PATCHes with autosort:true so the server skips learning it.
    private suspend fun autoMove(item: ItemEntity, categoryId: String) = withContext(Dispatchers.IO) {
        val who = identityProvider()
        itemDao.upsert(item.copy(categoryId = categoryId, modifiedBy = who))
        enqueue("PATCH", "/api/items/${item.id}", buildJson(mapOf("categoryId" to categoryId, "autosort" to true, "modifiedBy" to who)))
    }

    // Voice: clean a raw transcript into corrected {name, qty} grocery items.
    suspend fun cleanSpoken(transcript: List<String>, categoryNames: List<String> = emptyList()): List<VoiceItem> =
        voiceCleaner.clean(transcript, categoryNames)

    // Add a batch of (already-approved) voice items. Reuses an existing active item
    // with the same name by bumping its quantity, instead of adding a duplicate row.
    suspend fun addOrIncrementItems(listId: String, items: List<VoiceItem>) = favoriteMutex.withLock {
        withContext(Dispatchers.IO) {
            for (it in items) {
                val name = it.name.trim()
                if (name.isEmpty()) continue
                val existing = itemDao.findActiveByName(listId, name)
                if (existing != null) {
                    setQuantity(existing, mergeQuantity(existing.quantity, it.quantity))
                } else {
                    addItems(listId, listOf(name to it.quantity), mapOf(name to it.category))
                }
            }
        }
    }

    // Add a favorite to the list. If an active item with the same name already
    // exists, bump its quantity by 1 instead of adding a duplicate row.
    suspend fun addOrIncrementFavorite(listId: String, name: String) = favoriteMutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = itemDao.findActiveByName(listId, name.trim())
            if (existing != null) {
                // Bump the count by one; a measured quantity (e.g. "1 kg") is kept as-is.
                setQuantity(existing, mergeQuantity(existing.quantity, null))
            } else {
                addItems(listId, listOf(name to null))
            }
        }
    }

    suspend fun toggleChecked(item: ItemEntity) = patchItem(item.copy(checked = !item.checked), mapOf("checked" to !item.checked))
    suspend fun setQuantity(item: ItemEntity, quantity: String?) = patchItem(item.copy(quantity = quantity), mapOf("quantity" to quantity))

    // Favorites are a standalone, server-synced catalogue — independent of any list
    // item, so deleting an item never removes the favorite. The id is the normalized
    // name (must match the server's favKey) so adds are idempotent across devices.
    private fun favKey(name: String) = name.trim().lowercase()
    // URL-encode a favorite id (a name, may contain spaces/diacritics) for the DELETE
    // path; '+' -> '%20' so Express decodes it back to the original spaces.
    private fun encPath(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // Toggle a favorite from an item's star: on if absent, off if present. Stores the
    // item's category name so the quick-add sheet can group it without a list context.
    suspend fun toggleFavorite(name: String, categoryName: String?) = withContext(Dispatchers.IO) {
        if (favoriteDao.byId(favKey(name)) != null) removeFavorite(name) else addFavorite(name, categoryName)
    }

    suspend fun addFavorite(name: String, categoryName: String?) = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext
        favoriteDao.upsert(FavoriteEntity(id = favKey(trimmed), name = trimmed, categoryName = categoryName))
        enqueue("POST", "/api/favorites", buildJson(mapOf("name" to trimmed, "categoryName" to categoryName)))
    }

    suspend fun removeFavorite(name: String) = withContext(Dispatchers.IO) {
        val id = favKey(name)
        favoriteDao.delete(id)
        enqueue("DELETE", "/api/favorites/${encPath(id)}", null)
    }

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

    suspend fun addCategory(listId: String, name: String, icon: String? = null) = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        categoryDao.upsert(CategoryEntity(id = id, listId = listId, name = name, icon = icon))
        enqueue("POST", "/api/lists/$listId/categories", buildJson(mapOf("id" to id, "name" to name, "icon" to icon)))
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

    suspend fun setCategoryIcon(category: CategoryEntity, icon: String?) = withContext(Dispatchers.IO) {
        categoryDao.upsert(category.copy(icon = icon))
        enqueue("PATCH", "/api/categories/${category.id}", buildJson(mapOf("icon" to icon)))
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
                "favorite" -> when (ev.event) {
                    "create", "update" -> runCatching { favoriteDao.upsert(api.json.decodeFromString(FavoriteDto.serializer(), ev.data.toString()).toEntity()) }
                    "delete" -> ev.data.str("id")?.let { favoriteDao.delete(it) }
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
