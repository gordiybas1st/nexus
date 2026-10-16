package com.example.assistant.notifications

import com.example.assistant.ui.screens.task_edit.ReminderOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

class ReminderPlannerTest {

    private val zone = ZoneOffset.UTC
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).toInstant(zone).toEpochMilli()

    private val now = at(16, 8)

    @Test
    fun doneTaskHasNoReminders() {
        assertTrue(ReminderPlanner.plan(true, at(17, 18), null, now, 9, zone).isEmpty())
    }

    @Test
    fun deadlineWithoutExplicitReminderGetsSmartAndFollowUp() {
        val plan = ReminderPlanner.plan(false, at(17, 18), null, now, 9, zone)
        assertEquals(
            listOf(
                PlannedReminder(ReminderKind.SMART, at(17, 9)),
                PlannedReminder(ReminderKind.FOLLOW_UP, at(17, 20)),
            ),
            plan,
        )
    }

    @Test
    fun smartReminderMovesBeforeAnEarlyDeadline() {
        // Usual hour is 19:00 but the task is due at 10:00 → remind at 09:00.
        val plan = ReminderPlanner.plan(false, at(17, 10), null, now, 19, zone)
        assertEquals(PlannedReminder(ReminderKind.SMART, at(17, 9)), plan.first())
    }

    @Test
    fun explicitReminderReplacesSmartOne() {
        val plan = ReminderPlanner.plan(false, at(17, 18), at(17, 17), now, 9, zone)
        assertEquals(listOf(ReminderKind.EXPLICIT, ReminderKind.FOLLOW_UP), plan.map { it.kind })
    }

    @Test
    fun pastTimesAreSkipped() {
        // Deadline 1 h ago: only the follow-up (in 1 h) remains.
        val plan = ReminderPlanner.plan(false, at(16, 7), null, now, 9, zone)
        assertEquals(listOf(PlannedReminder(ReminderKind.FOLLOW_UP, at(16, 9))), plan)
    }

    @Test
    fun preferredHourIsTheMostCommonCompletionHour() {
        val evening = List(4) { at(it + 1, 20, 15) }
        val morning = List(2) { at(it + 1, 9) }
        assertEquals(20, ReminderPlanner.preferredHour(evening + morning, zone))
        // Too little history → default.
        assertEquals(ReminderPlanner.DEFAULT_HOUR, ReminderPlanner.preferredHour(evening.take(3), zone))
    }

    @Test
    fun dailyReviewDelay() {
        val before = LocalDateTime.of(2026, 10, 16, 18, 0)
        assertEquals(Duration.ofHours(3), DailyReviewScheduler.delayUntil(21, 0, before))
        val after = LocalDateTime.of(2026, 10, 16, 22, 0)
        assertEquals(Duration.ofHours(23), DailyReviewScheduler.delayUntil(21, 0, after))
    }

    @Test
    fun reminderOptionRoundTrip() {
        val deadline = at(17, 18)
        assertEquals(ReminderOption.AUTO, ReminderOption.from(deadline, null))
        assertEquals(ReminderOption.HOUR_1, ReminderOption.from(deadline, at(17, 17)))
        assertEquals(ReminderOption.AT_DEADLINE, ReminderOption.from(deadline, deadline))
        assertEquals(ReminderOption.CUSTOM, ReminderOption.from(deadline, at(17, 13, 7)))
    }

    @Test
    fun clockFormat() {
        assertEquals("25:00", NotificationHelper.formatClock(25 * 60_000L))
        assertEquals("00:01", NotificationHelper.formatClock(1))
        assertEquals("00:00", NotificationHelper.formatClock(0))
    }
}
