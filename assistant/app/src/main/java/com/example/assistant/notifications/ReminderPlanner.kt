package com.example.assistant.notifications

import java.time.Instant
import java.time.ZoneId

/** Kinds of task alarms; [code] makes each alarm's PendingIntent unique per task. */
enum class ReminderKind(val code: Int) {
    /** At the time the user picked ([com.example.assistant.domain.model.Task.reminderAt]). */
    EXPLICIT(0),

    /** On the deadline day at the hour the user usually finishes tasks. */
    SMART(1),

    /** Deadline passed 2 hours ago and the task is still open. */
    FOLLOW_UP(2),

    /** "Remind me in an hour" from a notification. */
    SNOOZE(3),
}

/** One alarm to set. */
data class PlannedReminder(val kind: ReminderKind, val at: Long)

/**
 * Pure reminder logic (unit-tested on the JVM).
 *
 * - Explicit reminder → fires at `reminderAt`.
 * - No explicit reminder but a deadline → a smart reminder on the deadline day at the user's
 *   typical completion hour (or 1 h before the deadline if that hour is already too late).
 * - Any deadline → a follow-up 2 h after it, which only shows if the task is still not done.
 */
object ReminderPlanner {

    const val FOLLOW_UP_DELAY_MS = 2 * 60 * 60 * 1000L
    private const val HOUR_MS = 60 * 60 * 1000L
    const val DEFAULT_HOUR = 9
    private const val MIN_SAMPLES = 5

    fun plan(
        isDone: Boolean,
        deadline: Long?,
        reminderAt: Long?,
        now: Long,
        preferredHour: Int,
        zone: ZoneId,
    ): List<PlannedReminder> {
        if (isDone) return emptyList()
        val result = mutableListOf<PlannedReminder>()
        if (reminderAt != null && reminderAt > now) result += PlannedReminder(ReminderKind.EXPLICIT, reminderAt)
        if (deadline != null && reminderAt == null) {
            val day = Instant.ofEpochMilli(deadline).atZone(zone).toLocalDate()
            var at = day.atTime(preferredHour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
            if (at >= deadline) at = deadline - HOUR_MS
            if (at > now) result += PlannedReminder(ReminderKind.SMART, at)
        }
        if (deadline != null && deadline + FOLLOW_UP_DELAY_MS > now) {
            result += PlannedReminder(ReminderKind.FOLLOW_UP, deadline + FOLLOW_UP_DELAY_MS)
        }
        return result
    }

    /**
     * The hour of day the user most often completes tasks (pattern learning).
     * Needs at least [MIN_SAMPLES] completions; ties go to the earlier hour.
     */
    fun preferredHour(completionTimes: List<Long>, zone: ZoneId, default: Int = DEFAULT_HOUR): Int {
        if (completionTimes.size < MIN_SAMPLES) return default
        return completionTimes
            .groupingBy { Instant.ofEpochMilli(it).atZone(zone).hour }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .first()
            .key
    }
}
