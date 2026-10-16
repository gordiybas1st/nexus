package com.example.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.assistant.data.local.entity.SyncConflictEntity
import com.example.assistant.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/** Access to the calendar sync outbox. */
@Dao
interface SyncQueueDao {
    @Insert
    suspend fun insert(entry: SyncQueueEntity): Long

    /** Oldest pending entry (FIFO). */
    @Query("SELECT * FROM sync_queue ORDER BY id LIMIT 1")
    suspend fun peek(): SyncQueueEntity?

    @Query("SELECT * FROM sync_queue WHERE taskId = :taskId ORDER BY id")
    suspend fun getForTask(taskId: Long): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue")
    fun observeCount(): Flow<Int>

    /** Deletes the entry only if nobody merged a newer change into it meanwhile. */
    @Query("DELETE FROM sync_queue WHERE id = :id AND createdAt = :createdAt")
    suspend fun deleteIfUnchanged(id: Long, createdAt: Long): Int

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sync_queue WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("UPDATE sync_queue SET createdAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE sync_queue SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("DELETE FROM sync_queue")
    suspend fun clear()
}

/** Access to the sync conflict log. */
@Dao
interface SyncConflictDao {
    @Insert
    suspend fun insert(entry: SyncConflictEntity)

    @Query("SELECT * FROM sync_conflicts ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SyncConflictEntity>>

    @Query("DELETE FROM sync_conflicts")
    suspend fun clear()

    /** Keeps only the newest [keep] entries. */
    @Query("DELETE FROM sync_conflicts WHERE id NOT IN (SELECT id FROM sync_conflicts ORDER BY id DESC LIMIT :keep)")
    suspend fun trimOld(keep: Int)
}
