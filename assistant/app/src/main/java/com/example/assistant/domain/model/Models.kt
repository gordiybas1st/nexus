package com.example.assistant.domain.model

/** Task importance. Order matters: higher ordinal = more important. */
enum class Priority { LOW, MEDIUM, HIGH }

/** App-wide theme selection. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Color palette used when Material You dynamic colors are off. */
enum class ThemePreset { DEFAULT, MINIMAL, CYBERPUNK, PASTEL }

/**
 * Preset repeat options shown in the task editor.
 *
 * @property rrule RFC 5545 rule body (without the `RRULE:` prefix), or `null`
 * for [NEVER] and [CUSTOM] (custom rules are stored verbatim on the task).
 */
enum class RepeatOption(val rrule: String?) {
    NEVER(null),
    DAILY("FREQ=DAILY"),
    WEEKLY("FREQ=WEEKLY"),
    MONTHLY("FREQ=MONTHLY"),
    CUSTOM(null);

    companion object {
        /** Maps a stored rule back to a preset, falling back to [CUSTOM]. */
        fun fromRrule(rrule: String?): RepeatOption = when {
            rrule.isNullOrBlank() -> NEVER
            else -> entries.firstOrNull { it.rrule == rrule } ?: CUSTOM
        }
    }
}

/** A colored label that can be attached to tasks. */
data class Tag(
    val id: Long = 0,
    val name: String,
    val colorHex: String,
)

/** A checklist item belonging to a [Task]. */
data class Subtask(
    val id: Long = 0,
    val taskId: Long = 0,
    val title: String,
    val isDone: Boolean = false,
    val sortOrder: Int = 0,
)

/** A to-do item with optional deadline, tag, repeat rule and subtasks. */
data class Task(
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val deadline: Long? = null,
    val reminderAt: Long? = null,
    val tag: Tag? = null,
    val isDone: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val completedAt: Long? = null,
    val googleEventId: String? = null,
    val syncedAt: Long? = null,
    val rrule: String? = null,
    val syncToCalendar: Boolean = false,
    val subtasks: List<Subtask> = emptyList(),
    /** Location reminder: fires when the user enters this circle. */
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val locationRadius: Float? = null,
) {
    /** True when a location reminder is set. */
    val hasLocation: Boolean get() = locationLat != null && locationLng != null

    /** Number of finished subtasks. */
    val completedSubtasks: Int get() = subtasks.count { it.isDone }

    /** True when the task is not done and its deadline has passed. */
    fun isOverdue(now: Long = System.currentTimeMillis()): Boolean =
        !isDone && deadline != null && deadline < now
}

/** A free-form note. */
data class Note(
    val id: Long = 0,
    val title: String,
    val content: String,
    val colorHex: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** A message in the assistant chat. [taskId] links a bot reply to a task. */
data class ChatMessage(
    val id: Long,
    val text: String,
    val isUser: Boolean,
    val createdAt: Long,
    val taskId: Long? = null,
)
