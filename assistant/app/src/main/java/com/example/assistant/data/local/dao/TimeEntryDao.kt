package com.example.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.assistant.data.local.entity.TimeEntryEntity
import kotlinx.coroutines.flow.Flow

/** Access to Pomodoro time tracking. */
@Dao
interface TimeEntryDao {
    @Insert
    suspend fun insert(entry: TimeEntryEntity): Long

    /** Total focused milliseconds since [since] (epoch millis). */
    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM time_entries WHERE startedAt >= :since")
    fun observeTotalSince(since: Long): Flow<Long>

    @Query("SELECT * FROM time_entries ORDER BY startedAt")
    suspend fun getAll(): List<TimeEntryEntity>

    @Query("SELECT * FROM time_entries WHERE startedAt >= :since ORDER BY startedAt")
    fun observeSince(since: Long): Flow<List<TimeEntryEntity>>
}
