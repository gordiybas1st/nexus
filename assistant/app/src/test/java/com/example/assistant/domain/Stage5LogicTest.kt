package com.example.assistant.domain

import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class RecurrenceTest {

    /** Friday 2026-10-16 18:00. */
    private val friday = LocalDateTime.of(2026, 10, 16, 18, 0)

    @Test
    fun simpleFrequencies() {
        assertEquals(friday.plusDays(1), Recurrence.next("FREQ=DAILY", friday))
        assertEquals(friday.plusDays(3), Recurrence.next("RRULE:FREQ=DAILY;INTERVAL=3", friday))
        assertEquals(friday.plusWeeks(1), Recurrence.next("FREQ=WEEKLY", friday))
        assertEquals(friday.plusMonths(1), Recurrence.next("FREQ=MONTHLY", friday))
        assertEquals(friday.plusYears(1), Recurrence.next("freq=yearly", friday))
        assertNull(Recurrence.next("FREQ=SECONDLY", friday))
    }

    @Test
    fun weeklyByDay() {
        // Fri → next is Monday of the following week.
        assertEquals(LocalDateTime.of(2026, 10, 19, 18, 0), Recurrence.next("FREQ=WEEKLY;BYDAY=MO,WE,FR", friday))
        val wednesday = LocalDateTime.of(2026, 10, 14, 9, 0)
        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 0), Recurrence.next("FREQ=WEEKLY;BYDAY=MO,WE,FR", wednesday))
        // Every 2 weeks on Monday.
        assertEquals(LocalDateTime.of(2026, 10, 26, 18, 0), Recurrence.next("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", friday))
    }

    @Test
    fun untilEndsTheRule() {
        assertNull(Recurrence.next("FREQ=DAILY;UNTIL=20261016T235959Z", friday))
        assertEquals(friday.plusDays(1), Recurrence.next("FREQ=DAILY;UNTIL=20261231", friday))
    }

    @Test
    fun nextAfterSkipsPastOccurrences() {
        val zone = ZoneOffset.UTC
        val deadline = LocalDateTime.of(2026, 10, 10, 9, 0).toInstant(zone).toEpochMilli()
        val now = friday.toInstant(zone).toEpochMilli()
        val next = Recurrence.nextAfter("FREQ=DAILY", deadline, now, zone)
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 0).toInstant(zone).toEpochMilli(), next)
    }
}

class StatsTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 16) // Friday
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).toInstant(zone).toEpochMilli()
    private val now = at(16, 12)

    @Test
    fun streakCountsBackFromTodayOrYesterday() {
        val days = setOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(4))
        assertEquals(3, StatsCalculator.streak(days, today))
        // Nothing today yet: the streak up to yesterday still counts.
        assertEquals(2, StatsCalculator.streak(setOf(today.minusDays(1), today.minusDays(2)), today))
        assertEquals(0, StatsCalculator.streak(setOf(today.minusDays(3)), today))
    }

    @Test
    fun computesCountsAndFocusByTag() {
        val work = Tag(1, "work", "#3B82F6")
        val tasks = listOf(
            Task(id = 1, title = "a", isDone = true, completedAt = at(16, 9), tag = work),
            Task(id = 2, title = "b", isDone = true, completedAt = at(15, 9)),
            Task(id = 3, title = "c", isDone = true, completedAt = at(1, 9)),
            Task(id = 4, title = "d", deadline = at(15, 18)), // overdue
            Task(id = 5, title = "e"),
        )
        val focus = listOf(
            FocusEntry(1, at(16, 8), 3_600_000),
            FocusEntry(null, at(14, 8), 1_800_000),
            FocusEntry(1, at(5, 8), 9_999_999), // previous week
        )
        val stats = StatsCalculator.compute(tasks, focus, today, now, zone)

        assertEquals(3, stats.done)
        assertEquals(1, stats.active)
        assertEquals(1, stats.overdue)
        assertEquals(2, stats.doneThisWeek)
        assertEquals(3, stats.doneThisMonth)
        assertEquals(2, stats.streak)
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1), stats.last7Days.map { it.count })
        assertEquals(5_400_000, stats.focusThisWeekMs)
        assertEquals(TagTime("work", "#3B82F6", 3_600_000), stats.focusByTag.first())
        // The overdue task (due yesterday) breaks the clean streak: only today counts.
        assertEquals(1, stats.daysWithoutOverdue)
    }

    @Test
    fun achievements() {
        val stats = Stats(done = 12, streak = 7, daysWithoutOverdue = 3)
        val progress = Achievements.progress(stats, emptyMap()).associateBy { it.id }
        assertTrue(progress.getValue(AchievementId.TASKS_10).unlocked)
        assertEquals(0.24f, progress.getValue(AchievementId.TASKS_50).fraction, 0.001f)
        assertEquals(
            listOf(AchievementId.FIRST_TASK, AchievementId.TASKS_10, AchievementId.STREAK_7),
            Achievements.newlyUnlocked(stats, emptyMap()),
        )
        // Already stored ones are not reported again, and stay unlocked.
        val stored = mapOf(AchievementId.FIRST_TASK to 1L, AchievementId.TASKS_10 to 1L)
        assertEquals(listOf(AchievementId.STREAK_7), Achievements.newlyUnlocked(stats, stored))
        assertTrue(Achievements.progress(Stats(done = 0), stored).first { it.id == AchievementId.TASKS_10 }.unlocked)
    }
}
