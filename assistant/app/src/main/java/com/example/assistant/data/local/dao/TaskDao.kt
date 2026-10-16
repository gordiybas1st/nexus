package com.example.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.assistant.data.local.entity.TaskEntity
import com.example.assistant.data.local.entity.TaskWithRelations
import com.example.assistant.domain.model.Priority
import kotlinx.coroutines.flow.Flow

/** Data access for tasks. */
@Dao
interface TaskDao {

    /** Inserts a new task. Aborts on id clash (REPLACE would cascade-delete subtasks). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAll(): Flow<List<TaskEntity>>

    @Transaction
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAllWithRelations(): Flow<List<TaskWithRelations>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getWithRelations(id: Long): TaskWithRelations?

    /** Tasks whose deadline falls into [[start], [end]] (epoch millis, inclusive). */
    @Query("SELECT * FROM tasks WHERE deadline BETWEEN :start AND :end ORDER BY deadline")
    fun getToday(start: Long, end: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE tagId = :tagId ORDER BY createdAt DESC")
    fun getByTag(tagId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE priority = :priority ORDER BY createdAt DESC")
    fun getByPriority(priority: Priority): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM tasks WHERE title LIKE '%' || :query || '%' " +
            "OR description LIKE '%' || :query || '%' ORDER BY createdAt DESC",
    )
    fun search(query: String): Flow<List<TaskEntity>>

    @Query("UPDATE tasks SET isDone = :done, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markDone(id: Long, done: Boolean, completedAt: Long?, updatedAt: Long)

    @Query("SELECT * FROM tasks WHERE isDone = 0 AND deadline IS NOT NULL AND deadline < :now ORDER BY deadline")
    fun getOverdue(now: Long): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM tasks WHERE isDone = 1 AND completedAt >= :since")
    fun getCompletedCountSince(since: Long): Flow<Int>

    /** Stores calendar link info without touching [TaskEntity.updatedAt]. */
    @Query("UPDATE tasks SET googleEventId = :eventId, syncedAt = :syncedAt WHERE id = :id")
    suspend fun setSyncInfo(id: Long, eventId: String?, syncedAt: Long?)

    @Query("SELECT * FROM tasks WHERE isDone = 1 AND googleEventId IS NOT NULL")
    suspend fun getCompletedWithEvents(): List<TaskEntity>

    /** Tasks that should exist in Google Calendar. */
    @Transaction
    @Query("SELECT * FROM tasks WHERE syncToCalendar = 1")
    suspend fun getSyncCandidates(): List<TaskWithRelations>

    @Query("SELECT * FROM tasks WHERE googleEventId IS NOT NULL")
    suspend fun getAllWithEvents(): List<TaskEntity>

    /** Not-done tasks that may need reminders. */
    @Query("SELECT * FROM tasks WHERE isDone = 0 AND (deadline IS NOT NULL OR reminderAt IS NOT NULL)")
    suspend fun getActiveWithTimes(): List<TaskEntity>

    /** Completion times of recently finished tasks (for the reminder-hour heuristic). */
    @Query("SELECT completedAt FROM tasks WHERE isDone = 1 AND completedAt IS NOT NULL ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getRecentCompletionTimes(limit: Int): List<Long>

    /** Not-done tasks due before [end] (today's and overdue ones). */
    @Transaction
    @Query("SELECT * FROM tasks WHERE isDone = 0 AND deadline IS NOT NULL AND deadline < :end ORDER BY deadline")
    suspend fun getIncompleteDueBefore(end: Long): List<TaskWithRelations>

    @Transaction
    @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun getAllWithRelations(): List<TaskWithRelations>

    /** Open tasks with a location reminder. */
    @Query("SELECT * FROM tasks WHERE isDone = 0 AND locationLat IS NOT NULL AND locationLng IS NOT NULL")
    suspend fun getWithLocation(): List<TaskEntity>

    @Query("UPDATE tasks SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    /** Deletes every completed task and returns how many were removed. */
    @Query("DELETE FROM tasks WHERE isDone = 1")
    suspend fun clearCompleted(): Int
}
