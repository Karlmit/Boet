package se.jabba.boet.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("boet_prefs")

data class Settings(
    val identity: String? = null,        // "Kalle" | "Klara"
    val language: String = "sv",         // "sv" | "en"
    val serverUrl: String = DEFAULT_SERVER,
    val notifications: Boolean = true,
    // Shopping Mode: whether checked items are hidden into the "klara" list. The
    // toggle remembers the user's last choice across trips.
    val shoppingHideCompleted: Boolean = false,
    // Shopping Mode: when this many (or fewer) items remain, offer to check off the
    // rest in one tap. Stored per device/user; 0 disables the suggestion entirely.
    val autoCompleteThreshold: Int = DEFAULT_AUTO_COMPLETE_THRESHOLD,
) {
    companion object {
        const val DEFAULT_SERVER = "https://boet.jabba.se"
        // The web app (public recipe pages live here) — a different host than
        // the API server, so share links get their own constant.
        const val WEB_BASE_URL = "https://boetweb.jabba.se"
        const val DEFAULT_AUTO_COMPLETE_THRESHOLD = 3
    }
}

class Prefs(private val context: Context) {
    private object Keys {
        val identity = stringPreferencesKey("identity")
        val language = stringPreferencesKey("language")
        val serverUrl = stringPreferencesKey("server_url")
        val notifications = booleanPreferencesKey("notifications")
        val shoppingHideCompleted = booleanPreferencesKey("shopping_hide_completed")
        val autoCompleteThreshold = intPreferencesKey("auto_complete_threshold")
        val dailyMealId = stringPreferencesKey("discover_daily_meal_id")
        val dailyMealDate = stringPreferencesKey("discover_daily_meal_date")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            identity = p[Keys.identity],
            language = p[Keys.language] ?: "sv",
            serverUrl = p[Keys.serverUrl] ?: Settings.DEFAULT_SERVER,
            notifications = p[Keys.notifications] ?: true,
            shoppingHideCompleted = p[Keys.shoppingHideCompleted] ?: false,
            autoCompleteThreshold = p[Keys.autoCompleteThreshold]
                ?: Settings.DEFAULT_AUTO_COMPLETE_THRESHOLD,
        )
    }

    suspend fun setIdentity(name: String) =
        context.dataStore.edit { it[Keys.identity] = name }.let {}

    suspend fun setLanguage(lang: String) =
        context.dataStore.edit { it[Keys.language] = lang }.let {}

    suspend fun setServerUrl(url: String) =
        context.dataStore.edit { it[Keys.serverUrl] = url.trim().trimEnd('/') }.let {}

    suspend fun setNotifications(enabled: Boolean) =
        context.dataStore.edit { it[Keys.notifications] = enabled }.let {}

    suspend fun setShoppingHideCompleted(hide: Boolean) =
        context.dataStore.edit { it[Keys.shoppingHideCompleted] = hide }.let {}

    suspend fun setAutoCompleteThreshold(count: Int) =
        context.dataStore.edit { it[Keys.autoCompleteThreshold] = count.coerceIn(0, 20) }.let {}

    // Discover's "today's pick" (Dagens slump): the same featured meal should stay
    // put across screen visits/app restarts for the whole calendar day, refreshing
    // only when the date rolls over or the user explicitly reshuffles it — a plain
    // in-memory random() on every screen load looked like it "wasn't really random
    // per day" since it changed on every visit instead.
    data class DailyMeal(val mealId: String, val date: String)

    suspend fun dailyMeal(): DailyMeal? {
        val p = context.dataStore.data.first()
        val id = p[Keys.dailyMealId] ?: return null
        val date = p[Keys.dailyMealDate] ?: return null
        return DailyMeal(id, date)
    }

    suspend fun setDailyMeal(mealId: String, date: String) =
        context.dataStore.edit { it[Keys.dailyMealId] = mealId; it[Keys.dailyMealDate] = date }.let {}
}
