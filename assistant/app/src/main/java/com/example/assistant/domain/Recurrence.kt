package com.example.assistant.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Minimal RFC 5545 RRULE support for repeating tasks:
 * `FREQ=DAILY|WEEKLY|MONTHLY|YEARLY`, `INTERVAL`, `BYDAY` (weekly) and `UNTIL`.
 * `COUNT` and other parts are ignored.
 */
object Recurrence {

    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )

    /** The next occurrence after [from], or null if the rule ended or is not understood. */
    fun next(rrule: String, from: LocalDateTime): LocalDateTime? {
        val parts = rrule.trim().removePrefix("RRULE:").split(';')
            .mapNotNull { part -> part.split('=').takeIf { it.size == 2 }?.let { it[0].trim().uppercase() to it[1].trim().uppercase() } }
            .toMap()
        val interval = parts["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1
        val until = parts["UNTIL"]?.take(8)?.let { runCatching { LocalDate.parse("${it.take(4)}-${it.substring(4, 6)}-${it.substring(6, 8)}") }.getOrNull() }
        val next = when (parts["FREQ"]) {
            "DAILY" -> from.plusDays(interval)
            "WEEKLY" -> {
                val byDay = parts["BYDAY"]?.split(',')?.mapNotNull { DAYS[it.trim().takeLast(2)] }?.toSortedSet()
                if (byDay.isNullOrEmpty()) from.plusWeeks(interval) else nextByDay(from, byDay, interval)
            }
            "MONTHLY" -> from.plusMonths(interval)
            "YEARLY" -> from.plusYears(interval)
            else -> return null
        }
        return if (until != null && next.toLocalDate().isAfter(until)) null else next
    }

    /**
     * Next occurrence strictly after [now] (epoch millis), starting from [deadline].
     * Skips occurrences that are already in the past (e.g. a daily task finished late).
     */
    fun nextAfter(rrule: String, deadline: Long, now: Long, zone: ZoneId): Long? {
        var current = Instant.ofEpochMilli(deadline).atZone(zone).toLocalDateTime()
        repeat(MAX_STEPS) {
            current = next(rrule, current) ?: return null
            val millis = current.atZone(zone).toInstant().toEpochMilli()
            if (millis > now) return millis
        }
        return null
    }

    private fun nextByDay(from: LocalDateTime, days: Set<DayOfWeek>, interval: Long): LocalDateTime {
        // Later day in the same week?
        days.firstOrNull { it > from.dayOfWeek }?.let { return from.with(TemporalAdjusters.next(it)) }
        // Otherwise the first listed day, [interval] weeks later.
        val monday = from.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(interval)
        return monday.with(TemporalAdjusters.nextOrSame(days.first())).atTime(from.toLocalTime())
    }

    private const val MAX_STEPS = 2_000
}
