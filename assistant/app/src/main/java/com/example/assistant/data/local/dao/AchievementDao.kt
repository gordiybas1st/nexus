package com.example.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.assistant.data.local.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow

/** Access to unlocked achievements. */
@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements")
    fun observeAll(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievements")
    suspend fun getAll(): List<AchievementEntity>

    /** Returns -1 if it was already unlocked. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AchievementEntity): Long
}
