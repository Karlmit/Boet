package se.jabba.boet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {
    @Query("SELECT * FROM lists WHERE archived = 0 ORDER BY position, name")
    fun activeLists(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists ORDER BY archived, position, name")
    fun allLists(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE id = :id")
    fun listById(id: String): Flow<ListEntity?>

    @Upsert suspend fun upsert(list: ListEntity)
    @Upsert suspend fun upsertAll(lists: List<ListEntity>)
    @Query("DELETE FROM lists WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM lists WHERE id NOT IN (:ids)") suspend fun deleteNotIn(ids: List<String>)
    @Query("DELETE FROM lists") suspend fun deleteAll()
    @Query("SELECT id FROM lists LIMIT 1") suspend fun anyListId(): String?
    @Query("SELECT id FROM lists WHERE kind = 'grocery' AND archived = 0 ORDER BY position LIMIT 1") suspend fun firstGroceryListId(): String?
    @Query("SELECT name FROM lists WHERE id = :id") suspend fun nameById(id: String): String?
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE listId = :listId ORDER BY position")
    fun categoriesForList(listId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE listId = :listId ORDER BY position")
    suspend fun categoriesForListOnce(listId: String): List<CategoryEntity>

    @Upsert suspend fun upsert(category: CategoryEntity)
    @Upsert suspend fun upsertAll(categories: List<CategoryEntity>)
    @Query("UPDATE categories SET position = :position WHERE id = :id") suspend fun setPosition(id: String, position: Int)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM categories WHERE id NOT IN (:ids)") suspend fun deleteNotIn(ids: List<String>)
    @Query("DELETE FROM categories") suspend fun deleteAll()
    @Query("DELETE FROM categories WHERE listId = :listId") suspend fun clearForList(listId: String)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE listId = :listId ORDER BY position, createdAt")
    fun itemsForList(listId: String): Flow<List<ItemEntity>>

    // Emits on ANY items-table change (Room invalidation) — the home-screen
    // widget's refresh trigger; see the collector in BoetApp.
    @Query("SELECT * FROM items")
    fun allItemsFlow(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE listId = :listId")
    suspend fun itemsForListOnce(listId: String): List<ItemEntity>

    // Active (unchecked) item with this name in a list — used to merge favorites
    // into an existing row instead of adding a duplicate.
    @Query("SELECT * FROM items WHERE listId = :listId AND checked = 0 AND lower(name) = lower(:name) ORDER BY position LIMIT 1")
    suspend fun findActiveByName(listId: String, name: String): ItemEntity?

    @Upsert suspend fun upsert(item: ItemEntity)
    @Upsert suspend fun upsertAll(items: List<ItemEntity>)
    @Query("UPDATE items SET position = :position WHERE id = :id") suspend fun setPosition(id: String, position: Int)
    @Query("DELETE FROM items WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM items WHERE id NOT IN (:ids)") suspend fun deleteNotIn(ids: List<String>)
    @Query("DELETE FROM items") suspend fun deleteAll()
    @Query("DELETE FROM items WHERE listId = :listId AND checked = 1") suspend fun clearChecked(listId: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY position, name")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun byId(id: String): FavoriteEntity?

    @Upsert suspend fun upsert(favorite: FavoriteEntity)
    @Upsert suspend fun upsertAll(favorites: List<FavoriteEntity>)
    @Query("DELETE FROM favorites WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM favorites WHERE id NOT IN (:ids)") suspend fun deleteNotIn(ids: List<String>)
    @Query("DELETE FROM favorites") suspend fun deleteAll()
}

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY position, name")
    fun recipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun recipeById(id: String): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun byIdOnce(id: String): RecipeEntity?

    @Upsert suspend fun upsert(recipe: RecipeEntity)
    @Upsert suspend fun upsertAll(recipes: List<RecipeEntity>)
    @Query("DELETE FROM recipes WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM recipes WHERE id NOT IN (:ids)") suspend fun deleteNotIn(ids: List<String>)
    @Query("DELETE FROM recipes") suspend fun deleteAll()

    // Local mirror of the server's exclusive-select behavior (see
    // POST /recipes/:id/select) so the UI flips instantly rather than waiting for
    // the WebSocket broadcast to round-trip.
    @Query("UPDATE recipes SET selected = 0 WHERE selected = 1") suspend fun clearSelected()
    @Query("UPDATE recipes SET selected = :selected WHERE id = :id") suspend fun setSelected(id: String, selected: Boolean)
}

@Dao
interface LearnedDao {
    @Query("SELECT * FROM learned_categories")
    suspend fun all(): List<LearnedCategoryEntity>

    @Query("SELECT categoryName FROM learned_categories WHERE itemKey = :key LIMIT 1")
    suspend fun categoryFor(key: String): String?

    @Upsert suspend fun upsertAll(rows: List<LearnedCategoryEntity>)
    @Query("DELETE FROM learned_categories") suspend fun deleteAll()
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(op: OutboxOp): Long

    @Query("SELECT * FROM outbox ORDER BY seq ASC")
    suspend fun pending(): List<OutboxOp>

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun remove(seq: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Flow<Int>
}
