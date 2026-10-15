package com.example.assistant.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.assistant.domain.model.Priority

/** Room row for a task. See [com.example.assistant.domain.model.Task]. */
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("tagId"), Index("deadline"), Index("isDone")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String?,
    val priority: Priority,
    val deadline: Long?,
    val reminderAt: Long?,
    val tagId: Long?,
    val isDone: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val googleEventId: String?,
    val syncedAt: Long?,
    val rrule: String?,
    val syncToCalendar: Boolean,
)

/** Room row for a subtask; deleted together with its parent task. */
@Entity(
    tableName = "subtasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class SubtaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val title: String,
    val isDone: Boolean,
    val sortOrder: Int,
)

/** Room row for a tag. Names are unique. */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String,
)

/** Room row for a note. */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val colorHex: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A task joined with its subtasks and tag. */
data class TaskWithRelations(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "id", entityColumn = "taskId")
    val subtasks: List<SubtaskEntity>,
    @Relation(parentColumn = "tagId", entityColumn = "id")
    val tag: TagEntity?,
)
