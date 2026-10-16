package com.example.assistant.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** What to do with the Google Calendar event of a task. */
enum class SyncAction { CREATE, UPDATE, DELETE }

/**
 * Pending calendar operation (offline-first outbox), processed FIFO by id.
 *
 * CREATE/UPDATE read the current task at processing time, so they carry no payload.
 * DELETE stores `{"eventId": "..."}` because the task row may already be gone.
 * [createdAt] is bumped when a newer change is merged into an existing entry.
 */
@Entity(tableName = "sync_queue", indices = [Index("taskId")])
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: SyncAction,
    val taskId: Long,
    val payloadJson: String?,
    val retryCount: Int,
    val createdAt: Long,
)

/** Log entry for a sync conflict or dropped operation (shown in Settings). */
@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    val googleEventId: String?,
    val message: String,
    val createdAt: Long,
)
