package com.example.assistant.data.repository

import androidx.room.withTransaction
import com.example.assistant.data.local.AppDatabase
import com.example.assistant.data.local.dao.SubtaskDao
import com.example.assistant.data.local.dao.TagDao
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.local.entity.TagEntity
import com.example.assistant.data.local.entity.toDomain
import com.example.assistant.data.local.entity.toEntity
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for tasks, subtasks and tags. */
@Singleton
class TaskRepository @Inject constructor(
    private val db: AppDatabase,
    private val taskDao: TaskDao,
    private val subtaskDao: SubtaskDao,
    private val tagDao: TagDao,
) {
    /** All tasks with subtasks and tags, updated on every change. */
    fun observeTasks(): Flow<List<Task>> =
        taskDao.observeAllWithRelations().map { rows -> rows.map { it.toDomain() } }

    fun observeTags(): Flow<List<Tag>> =
        tagDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Number of tasks completed at or after [since] (epoch millis). */
    fun observeCompletedCountSince(since: Long): Flow<Int> = taskDao.getCompletedCountSince(since)

    suspend fun getTask(id: Long): Task? = taskDao.getWithRelations(id)?.toDomain()

    /**
     * Inserts ([Task.id] == 0) or updates a task and replaces its subtasks.
     * Timestamps are filled in here. Returns the task id.
     */
    suspend fun saveTask(task: Task): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val completedAt = when {
            !task.isDone -> null
            task.completedAt == null -> now
            else -> task.completedAt
        }
        val id = if (task.id == 0L) {
            taskDao.insert(task.copy(createdAt = now, updatedAt = now, completedAt = completedAt).toEntity())
        } else {
            taskDao.update(task.copy(updatedAt = now, completedAt = completedAt).toEntity())
            task.id
        }
        subtaskDao.deleteForTask(id)
        subtaskDao.insertAll(
            task.subtasks.mapIndexed { index, sub -> sub.copy(id = 0, sortOrder = index).toEntity(id) },
        )
        id
    }

    suspend fun setDone(id: Long, done: Boolean) {
        val now = System.currentTimeMillis()
        taskDao.markDone(id, done, if (done) now else null, now)
    }

    suspend fun setSubtaskDone(id: Long, done: Boolean) = subtaskDao.setDone(id, done)

    /** Deletes a task; its subtasks are removed by the foreign-key cascade. */
    suspend fun deleteTask(id: Long) = taskDao.deleteById(id)

    /** Re-inserts a previously deleted task with the same id (used by Undo). */
    suspend fun restoreTask(task: Task) {
        db.withTransaction {
            taskDao.insert(task.toEntity())
            subtaskDao.insertAll(
                task.subtasks.mapIndexed { index, sub -> sub.copy(id = 0, sortOrder = index).toEntity(task.id) },
            )
        }
    }

    /** Removes all completed tasks; returns how many were removed. */
    suspend fun clearCompleted(): Int = taskDao.clearCompleted()

    /** Creates a tag, or returns the id of an existing tag with the same name. */
    suspend fun addTag(name: String, colorHex: String): Long {
        val trimmed = name.trim()
        val id = tagDao.insert(TagEntity(name = trimmed, colorHex = colorHex))
        return if (id != -1L) id else tagDao.getByName(trimmed)?.id ?: -1L
    }

    suspend fun deleteTag(tag: Tag) = tagDao.delete(tag.toEntity())
}
