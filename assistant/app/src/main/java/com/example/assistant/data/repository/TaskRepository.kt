package com.example.assistant.data.repository

import androidx.room.withTransaction
import com.example.assistant.data.local.AppDatabase
import com.example.assistant.data.local.dao.SubtaskDao
import com.example.assistant.data.local.dao.TagDao
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.local.entity.SyncAction
import com.example.assistant.data.local.entity.TagEntity
import com.example.assistant.data.local.entity.toDomain
import com.example.assistant.data.local.entity.toEntity
import com.example.assistant.domain.Recurrence
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import com.example.assistant.notifications.GeofenceManager
import com.example.assistant.notifications.ReminderScheduler
import com.example.assistant.sync.SyncQueueWriter
import com.example.assistant.widget.WidgetRefresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for tasks, subtasks and tags.
 *
 * Every change is written to Room first; matching Google Calendar operations are queued in the
 * sync outbox ([SyncQueueWriter]) and sent later by the sync worker (offline-first).
 * Reminders, geofences and the home-screen widget are updated after every change, and
 * achievements are checked.
 */
@Singleton
class TaskRepository @Inject constructor(
    private val db: AppDatabase,
    private val taskDao: TaskDao,
    private val subtaskDao: SubtaskDao,
    private val tagDao: TagDao,
    private val syncQueue: SyncQueueWriter,
    private val reminders: ReminderScheduler,
    private val widget: WidgetRefresher,
    private val geofences: GeofenceManager,
    private val stats: StatsRepository,
) {
    /** All tasks with subtasks and tags, updated on every change. */
    fun observeTasks(): Flow<List<Task>> =
        taskDao.observeAllWithRelations().map { rows -> rows.map { it.toDomain() } }

    fun observeTags(): Flow<List<Tag>> =
        tagDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Number of tasks completed at or after [since] (epoch millis). */
    fun observeCompletedCountSince(since: Long): Flow<Int> = taskDao.getCompletedCountSince(since)

    suspend fun getTask(id: Long): Task? = taskDao.getWithRelations(id)?.toDomain()

    /** Open tasks with a deadline before [end] (epoch millis). */
    suspend fun getIncompleteDueBefore(end: Long): List<Task> = taskDao.getIncompleteDueBefore(end).map { it.toDomain() }

    /**
     * Inserts ([Task.id] == 0) or updates a task and replaces its subtasks.
     * Timestamps are filled in here. Returns the task id.
     */
    suspend fun saveTask(task: Task): Long {
        val id = db.withTransaction {
            val now = System.currentTimeMillis()
            val completedAt = when {
                !task.isDone -> null
                task.completedAt == null -> now
                else -> task.completedAt
            }
            val savedId = if (task.id == 0L) {
                taskDao.insert(task.copy(createdAt = now, updatedAt = now, completedAt = completedAt).toEntity())
            } else {
                taskDao.update(task.copy(updatedAt = now, completedAt = completedAt).toEntity())
                task.id
            }
            subtaskDao.deleteForTask(savedId)
            subtaskDao.insertAll(
                task.subtasks.mapIndexed { index, sub -> sub.copy(id = 0, sortOrder = index).toEntity(savedId) },
            )
            savedId
        }
        afterChange(id)
        return id
    }

    /**
     * Marks a task done / not done. Completing a repeating task (RRULE + deadline) keeps a
     * finished copy for history and statistics and moves the task to its next occurrence.
     */
    suspend fun setDone(id: Long, done: Boolean) {
        val now = System.currentTimeMillis()
        val task = taskDao.getWithRelations(id)?.toDomain()
        val rrule = task?.rrule
        val deadline = task?.deadline
        val next = if (done && !task?.isDone.orFalse() && !rrule.isNullOrBlank() && deadline != null) {
            Recurrence.nextAfter(rrule, deadline, now, ZoneId.systemDefault())
        } else {
            null
        }
        if (task != null && deadline != null && next != null) {
            val shift = next - deadline
            val historyId = db.withTransaction {
                val historyId = taskDao.insert(
                    task.copy(
                        id = 0, isDone = true, completedAt = now, createdAt = now, updatedAt = now,
                        rrule = null, syncToCalendar = false, googleEventId = null, syncedAt = null, reminderAt = null,
                        locationLat = null, locationLng = null, locationRadius = null,
                    ).toEntity(),
                )
                subtaskDao.insertAll(task.subtasks.mapIndexed { i, sub -> sub.copy(id = 0, sortOrder = i).toEntity(historyId) })
                taskDao.update(
                    task.copy(
                        deadline = next, reminderAt = task.reminderAt?.plus(shift),
                        isDone = false, completedAt = null, updatedAt = now,
                    ).toEntity(),
                )
                subtaskDao.deleteForTask(id)
                subtaskDao.insertAll(task.subtasks.mapIndexed { i, sub -> sub.copy(id = 0, isDone = false, sortOrder = i).toEntity(id) })
                historyId
            }
            afterChange(historyId)
            afterChange(id)
            return
        }
        taskDao.markDone(id, done, if (done) now else null, now)
        afterChange(id)
    }

    /** Toggles a subtask from the list; the task counts as changed (calendar description, widget). */
    suspend fun setSubtaskDone(taskId: Long, subtaskId: Long, done: Boolean) {
        subtaskDao.setDone(subtaskId, done)
        taskDao.touch(taskId, System.currentTimeMillis())
        afterChange(taskId)
    }

    /** Deletes a task; its subtasks go with it (FK cascade) and its event is queued for deletion. */
    suspend fun deleteTask(id: Long) {
        val eventId = taskDao.getWithRelations(id)?.task?.googleEventId
        taskDao.deleteById(id)
        reminders.cancel(id)
        geofences.remove(id)
        widget.refresh()
        if (eventId != null) syncQueue.enqueue(SyncAction.DELETE, id, eventId)
    }

    /** Re-inserts a previously deleted task with the same id (used by Undo). */
    suspend fun restoreTask(task: Task) {
        val eventId = task.googleEventId
        // If the event's DELETE was not sent yet, the event still exists and can just be updated.
        val eventStillExists = eventId != null && syncQueue.cancelPendingDelete(task.id)
        val restored = if (eventId != null && !eventStillExists) task.copy(googleEventId = null, syncedAt = null) else task
        db.withTransaction {
            taskDao.insert(restored.toEntity())
            subtaskDao.insertAll(
                restored.subtasks.mapIndexed { index, sub -> sub.copy(id = 0, sortOrder = index).toEntity(task.id) },
            )
        }
        afterChange(task.id)
    }

    /** Removes all completed tasks (and their events); returns how many were removed. */
    suspend fun clearCompleted(): Int {
        val withEvents = taskDao.getCompletedWithEvents()
        val removed = taskDao.clearCompleted()
        widget.refresh()
        withEvents.forEach { row ->
            row.googleEventId?.let { syncQueue.enqueue(SyncAction.DELETE, row.id, it) }
        }
        return removed
    }

    /** Creates a tag, or returns the id of an existing tag with the same name. */
    suspend fun addTag(name: String, colorHex: String): Long {
        val trimmed = name.trim()
        val id = tagDao.insert(TagEntity(name = trimmed, colorHex = colorHex))
        return if (id != -1L) id else tagDao.getByName(trimmed)?.id ?: -1L
    }

    suspend fun deleteTag(tag: Tag) = tagDao.delete(tag.toEntity())

    private suspend fun afterChange(id: Long) {
        reminders.schedule(id)
        geofences.update(id)
        widget.refresh()
        queueCalendarChange(id)
        runCatching { stats.checkAchievements() }
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false

    /**
     * Queues the calendar side of a local change:
     * sync on → CREATE/UPDATE; sync turned off with an event → DELETE and unlink.
     */
    private suspend fun queueCalendarChange(id: Long) {
        val task = taskDao.getWithRelations(id)?.task ?: return
        val eventId = task.googleEventId
        when {
            task.syncToCalendar -> syncQueue.enqueue(if (eventId == null) SyncAction.CREATE else SyncAction.UPDATE, id, eventId)
            eventId != null -> {
                syncQueue.enqueue(SyncAction.DELETE, id, eventId)
                taskDao.setSyncInfo(id, null, null)
            }
        }
    }
}
