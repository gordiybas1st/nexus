package com.example.assistant.data.local.entity

import com.example.assistant.domain.model.Note
import com.example.assistant.domain.model.Subtask
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task

/** Converts a joined row into the domain model; subtasks are ordered. */
fun TaskWithRelations.toDomain(): Task = Task(
    id = task.id,
    title = task.title,
    description = task.description,
    priority = task.priority,
    deadline = task.deadline,
    reminderAt = task.reminderAt,
    tag = tag?.toDomain(),
    isDone = task.isDone,
    createdAt = task.createdAt,
    updatedAt = task.updatedAt,
    completedAt = task.completedAt,
    googleEventId = task.googleEventId,
    syncedAt = task.syncedAt,
    rrule = task.rrule,
    syncToCalendar = task.syncToCalendar,
    subtasks = subtasks.sortedBy { it.sortOrder }.map { it.toDomain() },
)

/** Converts a domain task into a row (subtasks are stored separately). */
fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    description = description,
    priority = priority,
    deadline = deadline,
    reminderAt = reminderAt,
    tagId = tag?.id,
    isDone = isDone,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    googleEventId = googleEventId,
    syncedAt = syncedAt,
    rrule = rrule,
    syncToCalendar = syncToCalendar,
)

fun SubtaskEntity.toDomain(): Subtask = Subtask(id, taskId, title, isDone, sortOrder)

fun Subtask.toEntity(taskId: Long): SubtaskEntity =
    SubtaskEntity(id = id, taskId = taskId, title = title, isDone = isDone, sortOrder = sortOrder)

fun TagEntity.toDomain(): Tag = Tag(id, name, colorHex)

fun Tag.toEntity(): TagEntity = TagEntity(id, name, colorHex)

fun NoteEntity.toDomain(): Note = Note(id, title, content, colorHex, createdAt, updatedAt)

fun Note.toEntity(): NoteEntity = NoteEntity(id, title, content, colorHex, createdAt, updatedAt)
