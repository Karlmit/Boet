package se.jabba.boet.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ListEntity::class, CategoryEntity::class, ItemEntity::class, FavoriteEntity::class, LearnedCategoryEntity::class, OutboxOp::class],
    version = 4,
    exportSchema = false,
)
abstract class BoetDatabase : RoomDatabase() {
    abstract fun listDao(): ListDao
    abstract fun categoryDao(): CategoryDao
    abstract fun itemDao(): ItemDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun learnedDao(): LearnedDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile private var instance: BoetDatabase? = null

        fun get(context: Context): BoetDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BoetDatabase::class.java,
                    "boet.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
