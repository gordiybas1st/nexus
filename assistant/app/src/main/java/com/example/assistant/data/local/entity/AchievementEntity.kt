package com.example.assistant.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** An unlocked achievement ([id] = `AchievementId.name`). Locked ones have no row. */
@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val unlockedAt: Long,
)
