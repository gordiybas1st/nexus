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
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import com.example.assistant.notifications.ReminderScheduler
import com.example.assistant.sync.SyncQueueWriter
import com.example.assistant.widget.WidgetRefresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for tasks, subtasks and tags.
 *
 * Every change is written to Room first; matching Google Calendar operations are queued in the
 * sync outbox ([SyncQueueWriter]) and sent later by the sync worker (offline-first).
 * Reminders are rescheduled and the home-screen widget refreshed after every change.
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

    suspend fun setDone(id: Long, done: Boolean) {
        val now = System.currentTimeMillis()
        taskDao.markDone(id, done, if (done) now else null, now)
        afterChange(id)
    }

    suspend fun setSubtaskDone(id: Long, done: Boolean) = subtaskDao.setDone(id, done)

    /** Deletes a task; its subtasks go with it (FK cascade) and its event is queued for deletion. */
    suspend fun deleteTask(id: Long) {
        val eventId = taskDao.getWithRelations(id)?.task?.googleEventId
        taskDao.deleteById(id)
        reminders.cancel(id)
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
        widget.refresh()
        queueCalendarChange(id)
    }

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
