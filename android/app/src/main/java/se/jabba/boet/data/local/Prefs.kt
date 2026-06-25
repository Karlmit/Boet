package se.jabba.boet.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
}
