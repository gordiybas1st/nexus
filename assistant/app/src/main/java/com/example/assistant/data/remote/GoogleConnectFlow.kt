package com.example.assistant.data.remote

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.StringRes
import com.example.assistant.R
import com.example.assistant.data.backup.BackupScheduler
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.sync.SyncScheduler
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of one step of connecting a Google service. */
sealed interface ConnectOutcome {
    data class Connected(@StringRes val message: Int) : ConnectOutcome

    /** Launch [pendingIntent] and pass its result to [GoogleConnectFlow.finishConsent]. */
    data class NeedsConsent(val pendingIntent: PendingIntent, val target: ConnectTarget) : ConnectOutcome
    data object Cancelled : ConnectOutcome
    data class Failed(@StringRes val message: Int, val detail: String? = null) : ConnectOutcome
}

enum class ConnectTarget { CALENDAR, DRIVE }

/**
 * Shared "connect Google" logic for onboarding and settings:
 * sign-in (Credential Manager) → authorization (Calendar or Drive) → enable sync / backups.
 */
@Singleton
class GoogleConnectFlow @Inject constructor(
    private val auth: GoogleAuthManager,
    private val preferences: UserPreferences,
    private val syncScheduler: SyncScheduler,
    private val backupScheduler: BackupScheduler,
) {
    /** Signs in (if needed) and asks for Calendar access. */
    suspend fun connectCalendar(activity: Activity): ConnectOutcome {
        var email = preferences.syncSettings.first().accountEmail
        if (email == null) {
            when (val result = auth.signIn(activity)) {
                is SignInResult.Success -> email = result.email
                SignInResult.Cancelled -> return ConnectOutcome.Cancelled
                SignInResult.NoAccount -> return ConnectOutcome.Failed(R.string.msg_no_account)
                is SignInResult.Failure -> return ConnectOutcome.Failed(R.string.msg_sign_in_failed, result.message)
            }
        }
        return handle(auth.requestCalendarAccess(email), ConnectTarget.CALENDAR, email)
    }

    /** Asks for Drive app-data access and turns weekly backups on. */
    suspend fun connectDrive(): ConnectOutcome = handle(auth.requestDriveAccess(), ConnectTarget.DRIVE, null)

    /** Completes a consent screen launched for [ConnectOutcome.NeedsConsent]. */
    suspend fun finishConsent(target: ConnectTarget, resultCode: Int, data: Intent?): ConnectOutcome {
        if (resultCode != Activity.RESULT_OK) return ConnectOutcome.Failed(R.string.msg_access_denied)
        val scope = if (target == ConnectTarget.DRIVE) GoogleAuthManager.DRIVE_APPDATA_SCOPE else GoogleAuthManager.CALENDAR_SCOPE
        return handle(auth.handleConsentResult(data, scope), target, null)
    }

    private suspend fun handle(result: CalendarAccessResult, target: ConnectTarget, email: String?): ConnectOutcome =
        when (result) {
            is CalendarAccessResult.NeedsConsent -> ConnectOutcome.NeedsConsent(result.pendingIntent, target)
            is CalendarAccessResult.Failure -> ConnectOutcome.Failed(R.string.msg_access_failed, result.message)
            is CalendarAccessResult.Granted -> when (target) {
                ConnectTarget.CALENDAR -> {
                    preferences.setConnected(result.email ?: email)
                    syncScheduler.schedulePeriodic()
                    syncScheduler.requestFullSync()
                    ConnectOutcome.Connected(R.string.msg_connected)
                }
                ConnectTarget.DRIVE -> {
                    preferences.setDriveBackupEnabled(true)
                    backupScheduler.scheduleWeekly()
                    backupScheduler.backupNow()
                    ConnectOutcome.Connected(R.string.msg_backup_enabled)
                }
            }
        }
}
