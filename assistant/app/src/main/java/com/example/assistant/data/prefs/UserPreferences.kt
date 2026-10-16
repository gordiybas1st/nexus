package com.example.assistant.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.assistant.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

/** Snapshot of user-facing settings. */
data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

/** DataStore-backed user settings. */
@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Current settings; falls back to defaults if the file is unreadable. */
    val settings: Flow<UserSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            UserSettings(
                themeMode = prefs[THEME_MODE]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    /** Google account and calendar sync state. No tokens are stored here. */
    val syncSettings: Flow<SyncSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            SyncSettings(
                accountEmail = prefs[ACCOUNT_EMAIL],
                calendarEnabled = prefs[CALENDAR_ENABLED] ?: false,
                authRequired = prefs[AUTH_REQUIRED] ?: false,
                lastSyncAt = prefs[LAST_SYNC_AT],
                lastError = prefs[LAST_ERROR],
            )
        }

    /** Marks the account as connected with calendar access granted. */
    suspend fun setConnected(email: String?) {
        context.dataStore.edit {
            if (email != null) it[ACCOUNT_EMAIL] = email
            it[CALENDAR_ENABLED] = true
            it[AUTH_REQUIRED] = false
            it.remove(LAST_ERROR)
        }
    }

    /** Stops calendar sync but keeps the signed-in account. */
    suspend fun setCalendarDisabled() {
        context.dataStore.edit {
            it[CALENDAR_ENABLED] = false
            it[AUTH_REQUIRED] = false
            it.remove(LAST_ERROR)
        }
    }

    /** Forgets the account and all sync state. */
    suspend fun clearAccount() {
        context.dataStore.edit {
            it.remove(ACCOUNT_EMAIL)
            it.remove(LAST_SYNC_AT)
            it.remove(LAST_ERROR)
            it[CALENDAR_ENABLED] = false
            it[AUTH_REQUIRED] = false
        }
    }

    suspend fun setAuthRequired(required: Boolean) {
        context.dataStore.edit { it[AUTH_REQUIRED] = required }
    }

    /** Records a finished sync: [error] is null on success. */
    suspend fun setSyncResult(at: Long?, error: String?) {
        context.dataStore.edit {
            it.remove(LAST_ERROR)
            if (at != null) it[LAST_SYNC_AT] = at
            if (error != null) it[LAST_ERROR] = error
        }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ACCOUNT_EMAIL = stringPreferencesKey("account_email")
        val CALENDAR_ENABLED = booleanPreferencesKey("calendar_enabled")
        val AUTH_REQUIRED = booleanPreferencesKey("auth_required")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_ERROR = stringPreferencesKey("last_sync_error")
    }
}

/** Snapshot of account / sync settings. */
data class SyncSettings(
    val accountEmail: String? = null,
    val calendarEnabled: Boolean = false,
    val authRequired: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)
