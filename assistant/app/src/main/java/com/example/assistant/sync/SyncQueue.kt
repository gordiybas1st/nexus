package com.example.assistant.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.assistant.data.local.dao.SyncConflictDao
import com.example.assistant.data.local.dao.SyncQueueDao
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.local.entity.SyncAction
import com.example.assistant.data.local.entity.SyncConflictEntity
import com.example.assistant.data.local.entity.SyncQueueEntity
import com.example.assistant.data.local.entity.toDomain
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.data.remote.AuthRequiredException
import com.example.assistant.data.remote.CalendarApiException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks whether a sync worker is running in this process (for the UI indicator). */
@Singleton
class SyncStateHolder @Inject constructor() {
    private val active = AtomicInteger(0)
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun begin() {
        _running.value = active.incrementAndGet() > 0
    }

    fun end() {
        _running.value = active.decrementAndGet().coerceAtLeast(0) > 0
    }
}

/** Schedules [CalendarSyncWorker] runs through WorkManager. */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Processes the outbox as soon as the network is available. */
    fun requestSync() {
        val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(CalendarSyncWorker.KEY_MODE to CalendarSyncWorker.MODE_QUEUE))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Reconciles everything with Calendar now (e.g. right after connecting). */
    fun requestFullSync() {
        val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(CalendarSyncWorker.KEY_MODE to CalendarSyncWorker.MODE_FULL))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_FULL_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Full sync every 6 hours. Safe to call repeatedly. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(CalendarSyncWorker.KEY_MODE to CalendarSyncWorker.MODE_FULL))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_QUEUE)
        workManager.cancelUniqueWork(WORK_FULL_NOW)
        workManager.cancelUniqueWork(WORK_PERIODIC)
    }

    private companion object {
        const val WORK_QUEUE = "calendar_sync_queue"
        const val WORK_FULL_NOW = "calendar_sync_full"
        const val WORK_PERIODIC = "calendar_sync_periodic"
    }
}

/**
 * Adds operations to the outbox, merging them so each task has at most one pending
 * CREATE/UPDATE and at most one DELETE per event. Does nothing while sync is disabled
 * (a full sync after connecting catches up).
 */
@Singleton
class SyncQueueWriter @Inject constructor(
    private val dao: SyncQueueDao,
    private val preferences: UserPreferences,
    private val scheduler: SyncScheduler,
) {
    suspend fun enqueue(action: SyncAction, taskId: Long, eventId: String? = null, schedule: Boolean = true) {
        if (!preferences.syncSettings.first().calendarEnabled) return
        val now = System.currentTimeMillis()
        val pending = dao.getForTask(taskId)
        when (action) {
            SyncAction.CREATE, SyncAction.UPDATE -> {
                val existing = pending.firstOrNull { it.action != SyncAction.DELETE }
                if (existing != null) {
                    dao.touch(existing.id, now)
                } else {
                    dao.insert(SyncQueueEntity(action = action, taskId = taskId, payloadJson = null, retryCount = 0, createdAt = now))
                }
            }
            SyncAction.DELETE -> {
                val obsolete = pending.filter { it.action != SyncAction.DELETE }.map { it.id }
                if (obsolete.isNotEmpty()) dao.deleteAll(obsolete)
                val alreadyQueued = pending.any { it.action == SyncAction.DELETE && eventIdOf(it) == eventId }
                if (eventId != null && !alreadyQueued) {
                    dao.insert(
                        SyncQueueEntity(
                            action = SyncAction.DELETE,
                            taskId = taskId,
                            payloadJson = JSONObject().put("eventId", eventId).toString(),
                            retryCount = 0,
                            createdAt = now,
                        ),
                    )
                }
            }
        }
        if (schedule) scheduler.requestSync()
    }

    /** Cancels a not-yet-sent DELETE for [taskId]; returns true if there was one. */
    suspend fun cancelPendingDelete(taskId: Long): Boolean {
        val deletes = dao.getForTask(taskId).filter { it.action == SyncAction.DELETE }
        if (deletes.isNotEmpty()) dao.deleteAll(deletes.map { it.id })
        return deletes.isNotEmpty()
    }

    suspend fun clear() = dao.clear()

    companion object {
        /** Event id stored in a DELETE entry. */
        fun eventIdOf(entry: SyncQueueEntity): String? =
            entry.payloadJson
                ?.let { runCatching { JSONObject(it).optString("eventId") }.getOrNull() }
                ?.ifBlank { null }
    }
}

/** Overall result of a sync run. */
sealed interface SyncOutcome {
    data object Success : SyncOutcome
    data object Retry : SyncOutcome
    data object AuthRequired : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/** Processes the outbox FIFO, one entry at a time. */
@Singleton
class SyncQueueProcessor @Inject constructor(
    private val dao: SyncQueueDao,
    private val taskDao: TaskDao,
    private val conflictDao: SyncConflictDao,
    private val syncManager: CalendarSyncManager,
    private val preferences: UserPreferences,
) {
    private val mutex = Mutex()

    private sealed interface ItemResult {
        data object Done : ItemResult
        data class Drop(val reason: String) : ItemResult
        data object Retry : ItemResult
        data object Auth : ItemResult
        data class Fatal(val message: String) : ItemResult
    }

    /** Sends pending operations until the outbox is empty or an error stops the run. */
    suspend fun processAll(): SyncOutcome = mutex.withLock {
        repeatLoop@ for (attempt in 0 until MAX_ITEMS_PER_RUN) {
            val entry = dao.peek() ?: return@withLock finish(SyncOutcome.Success)
            when (val result = process(entry)) {
                ItemResult.Done -> dao.deleteIfUnchanged(entry.id, entry.createdAt)
                is ItemResult.Drop -> {
                    log(entry, "Dropped ${entry.action}: ${result.reason}")
                    dao.delete(entry.id)
                }
                ItemResult.Retry -> {
                    if (entry.retryCount + 1 >= MAX_RETRIES) {
                        log(entry, "Dropped ${entry.action} after $MAX_RETRIES failed attempts")
                        dao.delete(entry.id)
                        continue@repeatLoop
                    }
                    dao.incrementRetry(entry.id)
                    return@withLock finish(SyncOutcome.Retry)
                }
                ItemResult.Auth -> {
                    preferences.setAuthRequired(true)
                    return@withLock finish(SyncOutcome.AuthRequired)
                }
                is ItemResult.Fatal -> return@withLock finish(SyncOutcome.Failed(result.message))
            }
        }
        // More entries than one run handles: come back for the rest.
        finish(SyncOutcome.Retry)
    }

    private suspend fun process(entry: SyncQueueEntity): ItemResult {
        return try {
            when (entry.action) {
                SyncAction.DELETE -> {
                    val eventId = SyncQueueWriter.eventIdOf(entry) ?: return ItemResult.Drop("no event id")
                    try {
                        syncManager.deleteEvent(eventId)
                    } catch (e: CalendarApiException) {
                        if (e.code != 404 && e.code != 410) throw e // already gone = success
                    }
                    ItemResult.Done
                }
                SyncAction.CREATE, SyncAction.UPDATE -> pushTask(entry.taskId)
            }
        } catch (e: AuthRequiredException) {
            ItemResult.Auth
        } catch (e: CalendarApiException) {
            classify(e)
        } catch (e: IOException) {
            ItemResult.Retry
        }
    }

    /** Creates or updates the event for a task; recreates it if it vanished in Calendar. */
    private suspend fun pushTask(taskId: Long): ItemResult {
        val task = taskDao.getWithRelations(taskId)?.toDomain() ?: return ItemResult.Done
        if (!task.syncToCalendar) return ItemResult.Done
        val now = System.currentTimeMillis()
        val eventId = task.googleEventId
        if (eventId != null) {
            try {
                syncManager.updateEvent(task)
                taskDao.setSyncInfo(task.id, eventId, now)
                return ItemResult.Done
            } catch (e: CalendarApiException) {
                if (e.code != 404 && e.code != 410) throw e
                conflictDao.insert(
                    SyncConflictEntity(taskId = task.id, googleEventId = eventId, message = "Event missing in Calendar; recreated (local wins)", createdAt = now),
                )
            }
        }
        val newId = syncManager.createEvent(task.copy(googleEventId = null)) ?: return ItemResult.Drop("Calendar returned no event id")
        taskDao.setSyncInfo(task.id, newId, now)
        return ItemResult.Done
    }

    private fun classify(e: CalendarApiException): ItemResult = when {
        e.code == 401 -> ItemResult.Auth
        e.isRateLimited || e.code >= 500 -> ItemResult.Retry
        e.code == 403 -> ItemResult.Fatal(
            "403 Forbidden: enable Google Calendar API and add the calendar.events scope on the OAuth consent screen",
        )
        else -> ItemResult.Drop("rejected by Calendar (HTTP ${e.code})")
    }

    private suspend fun finish(outcome: SyncOutcome): SyncOutcome {
        when (outcome) {
            SyncOutcome.Success -> preferences.setSyncResult(at = System.currentTimeMillis(), error = null)
            is SyncOutcome.Failed -> preferences.setSyncResult(at = null, error = outcome.message)
            SyncOutcome.Retry, SyncOutcome.AuthRequired -> Unit
        }
        return outcome
    }

    private suspend fun log(entry: SyncQueueEntity, message: String) {
        conflictDao.insert(
            SyncConflictEntity(
                taskId = entry.taskId.takeIf { it > 0 },
                googleEventId = SyncQueueWriter.eventIdOf(entry),
                message = message,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        const val MAX_ITEMS_PER_RUN = 200
        const val MAX_RETRIES = 8
    }
}
