package com.example.assistant.domain

import com.example.assistant.domain.model.Task
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Logged focus time (a row of `time_entries`). */
data class FocusEntry(val taskId: Long?, val startedAt: Long, val durationMs: Long)

data class DayCount(val date: LocalDate, val count: Int)

data class TagTime(val name: String?, val colorHex: String?, val durationMs: Long)

/** Everything the statistics screen shows. */
data class Stats(
    val done: Int = 0,
    val active: Int = 0,
    val overdue: Int = 0,
    val last7Days: List<DayCount> = emptyList(),
    val streak: Int = 0,
    val doneThisWeek: Int = 0,
    val doneThisMonth: Int = 0,
    val focusThisWeekMs: Long = 0,
    /** Focus time this week per tag; `name = null` means "no tag". Largest first. */
    val focusByTag: List<TagTime> = emptyList(),
    /** Consecutive days (up to 7, ending today) without a missed deadline. */
    val daysWithoutOverdue: Int = 0,
)

/** Pure statistics over tasks and focus entries (unit-tested). */
object StatsCalculator {

    fun compute(tasks: List<Task>, focus: List<FocusEntry>, today: LocalDate, now: Long, zone: ZoneId): Stats {
        fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

        val done = tasks.filter { it.isDone }
        val completionDays = done.mapNotNull { it.completedAt }.map(::dayOf)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekStartMillis = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val monthStart = today.withDayOfMonth(1)

        val last7 = (6 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            DayCount(day, completionDays.count { it == day })
        }

        val tasksById = tasks.associateBy { it.id }
        val weekFocus = focus.filter { it.startedAt >= weekStartMillis }
        val byTag = weekFocus
            .groupBy { tasksById[it.taskId]?.tag?.name }
            .map { (name, entries) ->
                TagTime(name, entries.firstNotNullOfOrNull { tasksById[it.taskId]?.tag?.colorHex }, entries.sumOf { it.durationMs })
            }
            .sortedByDescending { it.durationMs }

        return Stats(
            done = done.size,
            active = tasks.count { !it.isDone && !it.isOverdue(now) },
            overdue = tasks.count { it.isOverdue(now) },
            last7Days = last7,
            streak = streak(completionDays.toSet(), today),
            doneThisWeek = completionDays.count { !it.isBefore(weekStart) },
            doneThisMonth = completionDays.count { !it.isBefore(monthStart) },
            focusThisWeekMs = weekFocus.sumOf { it.durationMs },
            focusByTag = byTag,
            daysWithoutOverdue = daysWithoutOverdue(tasks, today, now, zone),
        )
    }

    /**
     * Days in a row with at least one completed task, ending today —
     * or yesterday, so the streak doesn't look broken before today's first task.
     */
    fun streak(completionDays: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in completionDays) today else today.minusDays(1)
        var count = 0
        while (day in completionDays) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /** A day is "clean" if no task due that day was missed (still open after its deadline, or finished late). */
    fun daysWithoutOverdue(tasks: List<Task>, today: LocalDate, now: Long, zone: ZoneId, maxDays: Int = 7): Int {
        fun missedOn(day: LocalDate): Boolean = tasks.any { task ->
            val deadline = task.deadline ?: return@any false
            if (Instant.ofEpochMilli(deadline).atZone(zone).toLocalDate() != day) return@any false
            val completedAt = task.completedAt
            (task.isDone && completedAt != null && completedAt > deadline) || (!task.isDone && deadline < now)
        }
        var count = 0
        var day = today
        while (count < maxDays && !missedOn(day)) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
