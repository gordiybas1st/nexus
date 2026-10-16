package com.example.assistant.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.assistant.domain.model.ThemeMode
import com.example.assistant.domain.model.ThemePreset
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
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
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
                themePreset = prefs[THEME_PRESET]
                    ?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() }
                    ?: ThemePreset.DEFAULT,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    suspend fun setThemePreset(preset: ThemePreset) {
        context.dataStore.edit { it[THEME_PRESET] = preset.name }
    }

    /** Onboarding and backup state. */
    val dataSettings: Flow<DataSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            DataSettings(
                onboardingDone = prefs[ONBOARDING_DONE] ?: false,
                driveBackupEnabled = prefs[DRIVE_BACKUP_ENABLED] ?: false,
                lastBackupAt = prefs[LAST_BACKUP_AT],
                lastBackupError = prefs[LAST_BACKUP_ERROR],
            )
        }

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    suspend fun setDriveBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DRIVE_BACKUP_ENABLED] = enabled }
    }

    /** Records a backup attempt: [error] is null on success. */
    suspend fun setBackupResult(at: Long?, error: String?) {
        context.dataStore.edit {
            it.remove(LAST_BACKUP_ERROR)
            if (at != null) it[LAST_BACKUP_AT] = at
            if (error != null) it[LAST_BACKUP_ERROR] = error
        }
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

    /** Reminders, daily review and Pomodoro settings. */
    val productivity: Flow<ProductivitySettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            ProductivitySettings(
                notificationsEnabled = prefs[NOTIFICATIONS_ENABLED] ?: true,
                dailyReviewEnabled = prefs[DAILY_REVIEW_ENABLED] ?: true,
                dailyReviewHour = prefs[DAILY_REVIEW_HOUR] ?: 21,
                dailyReviewMinute = prefs[DAILY_REVIEW_MINUTE] ?: 0,
                pomodoroWorkMinutes = prefs[POMODORO_WORK] ?: 25,
                pomodoroBreakMinutes = prefs[POMODORO_BREAK] ?: 5,
                notificationPermissionAsked = prefs[NOTIFICATION_PERMISSION_ASKED] ?: false,
            )
        }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setDailyReview(enabled: Boolean, hour: Int, minute: Int) {
        context.dataStore.edit {
            it[DAILY_REVIEW_ENABLED] = enabled
            it[DAILY_REVIEW_HOUR] = hour.coerceIn(0, 23)
            it[DAILY_REVIEW_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setPomodoroDurations(workMinutes: Int, breakMinutes: Int) {
        context.dataStore.edit {
            it[POMODORO_WORK] = workMinutes.coerceIn(1, 120)
            it[POMODORO_BREAK] = breakMinutes.coerceIn(1, 60)
        }
    }

    suspend fun setNotificationPermissionAsked() {
        context.dataStore.edit { it[NOTIFICATION_PERMISSION_ASKED] = true }
    }

    private companion object {
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DRIVE_BACKUP_ENABLED = booleanPreferencesKey("drive_backup_enabled")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val DAILY_REVIEW_ENABLED = booleanPreferencesKey("daily_review_enabled")
        val DAILY_REVIEW_HOUR = intPreferencesKey("daily_review_hour")
        val DAILY_REVIEW_MINUTE = intPreferencesKey("daily_review_minute")
        val POMODORO_WORK = intPreferencesKey("pomodoro_work_minutes")
        val POMODORO_BREAK = intPreferencesKey("pomodoro_break_minutes")
        val NOTIFICATION_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
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

/** Snapshot of reminder / review / Pomodoro settings. */
data class ProductivitySettings(
    val notificationsEnabled: Boolean = true,
    val dailyReviewEnabled: Boolean = true,
    val dailyReviewHour: Int = 21,
    val dailyReviewMinute: Int = 0,
    val pomodoroWorkMinutes: Int = 25,
    val pomodoroBreakMinutes: Int = 5,
    val notificationPermissionAsked: Boolean = false,
)

/** Onboarding and backup state. */
data class DataSettings(
    val onboardingDone: Boolean = false,
    val driveBackupEnabled: Boolean = false,
    val lastBackupAt: Long? = null,
    val lastBackupError: String? = null,
)
