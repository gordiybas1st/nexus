package com.example.assistant.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.assistant.data.local.dao.SyncQueueDao
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.data.remote.AuthRequiredException
import com.example.assistant.data.remote.CalendarApiException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Hilt access for the worker (avoids a custom WorkerFactory setup). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun preferences(): UserPreferences
    fun syncManager(): CalendarSyncManager
    fun processor(): SyncQueueProcessor
    fun stateHolder(): SyncStateHolder
}

/**
 * Runs calendar sync in the background.
 *
 * Input [KEY_MODE]: [MODE_QUEUE] sends the outbox, [MODE_FULL] reconciles first.
 * Network errors and rate limits return [Result.retry] (exponential backoff from 30 s).
 */
class CalendarSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
        val preferences = deps.preferences()
        if (!preferences.syncSettings.first().calendarEnabled) return Result.success()

        val state = deps.stateHolder()
        state.begin()
        return try {
            if (inputData.getString(KEY_MODE) == MODE_FULL) deps.syncManager().fullSync()
            when (val outcome = deps.processor().processAll()) {
                SyncOutcome.Success -> Result.success()
                SyncOutcome.Retry -> retryOrGiveUp()
                SyncOutcome.AuthRequired -> Result.failure()
                is SyncOutcome.Failed -> Result.failure()
            }
        } catch (e: AuthRequiredException) {
            preferences.setAuthRequired(true)
            Result.failure()
        } catch (e: CalendarApiException) {
            when {
                // 410: sync token / resource gone — retry as a full sync from scratch.
                e.code == 410 || e.isRateLimited || e.code >= 500 -> retryOrGiveUp()
                else -> {
                    preferences.setSyncResult(at = null, error = "Calendar error ${e.code}")
                    Result.failure()
                }
            }
        } catch (e: IOException) {
            retryOrGiveUp()
        } finally {
            state.end()
        }
    }

    private fun retryOrGiveUp(): Result = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_QUEUE = "queue"
        const val MODE_FULL = "full"
        private const val MAX_ATTEMPTS = 10
    }
}

/** What the sync indicator shows. */
enum class SyncState { DISABLED, SYNCED, PENDING, SYNCING, ERROR, AUTH_REQUIRED }

/** Combined sync status for the UI. */
data class SyncStatus(
    val state: SyncState = SyncState.DISABLED,
    val pendingCount: Int = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val email: String? = null,
)

/** Derives [SyncStatus] from settings, the outbox size and running workers. */
@Singleton
class SyncStatusProvider @Inject constructor(
    preferences: UserPreferences,
    queueDao: SyncQueueDao,
    stateHolder: SyncStateHolder,
) {
    val status: Flow<SyncStatus> = combine(
        preferences.syncSettings,
        queueDao.observeCount(),
        stateHolder.running,
    ) { settings, pending, running ->
        val state = when {
            !settings.calendarEnabled -> SyncState.DISABLED
            settings.authRequired -> SyncState.AUTH_REQUIRED
            running -> SyncState.SYNCING
            settings.lastError != null -> SyncState.ERROR
            pending > 0 -> SyncState.PENDING
            else -> SyncState.SYNCED
        }
        SyncStatus(state, pending, settings.lastSyncAt, settings.lastError, settings.accountEmail)
    }
}
