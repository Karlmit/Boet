package se.jabba.boet.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey val id: String,
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
)

@Entity(tableName = "categories", indices = [Index("listId")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val name: String,
    val icon: String? = null,
    val position: Int = 0,
)

@Entity(tableName = "items", indices = [Index("listId"), Index("categoryId")])
data class ItemEntity(
    @PrimaryKey val id: String,
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
)

// Standalone household favorites catalogue, synced from the server (bootstrap +
// WebSocket). Independent of list items — starring/unstarring or deleting an item
// never changes this table. The id is the normalized name (lower/trim) so both
// devices and the server converge on one row per name. categoryName (not id) is
// stored because favorites are household-wide while categories are per-list.
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: String,        // normalized name key (name.trim().lowercase())
    val name: String,                  // display name
    val categoryName: String? = null,
    val position: Int = 0,
    val updatedAt: String? = null,
)

// A household recipe, synced from the server (bootstrap + WebSocket). The full
// recipe document is stored verbatim as a JSON string in `data` (parsed to a
// RecipeDoc on demand); `name`/`image` are denormalized for cheap grid rendering
// and ordering, and `categoryName`/`position` are list-view metadata mutated
// independently of the body.
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val image: String? = null,
    val categoryName: String? = null,
    val position: Int = 0,
    val data: String,                  // RecipeDoc serialized as JSON
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

// Household "knowledge base" of learned item→category mappings, synced down from
// the server on bootstrap. Lets the on-device categorizer honour corrections
// ("Mjölk → Mejeri" that Kalle set) even while offline, for both members.
@Entity(tableName = "learned_categories")
data class LearnedCategoryEntity(
    @PrimaryKey val itemKey: String,   // normalized name (see Categorizer.normalizeKey)
    val categoryName: String,
)

// Offline outbox: operations awaiting delivery to the server.
@Entity(tableName = "outbox")
data class OutboxOp(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val method: String,        // POST | PATCH | DELETE
    val path: String,          // relative API path
    val body: String?,         // JSON payload (nullable)
    val createdAt: Long = System.currentTimeMillis(),
)
