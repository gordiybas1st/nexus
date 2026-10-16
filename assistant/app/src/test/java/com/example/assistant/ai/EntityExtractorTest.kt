package com.example.assistant.ai

import com.example.assistant.domain.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class EntityExtractorTest {

    /** Friday. */
    private val today = LocalDate.of(2026, 10, 16)

    private fun date(text: String) = EntityExtractor.extractDate(text, today)

    @Test
    fun relativeDates() {
        assertEquals(today, date("сегодня"))
        assertEquals(today.plusDays(1), date("завтра"))
        assertEquals(today.plusDays(2), date("послезавтра"))
        assertEquals(today.plusDays(1), date("tomorrow"))
        assertEquals(today.plusDays(3), date("через 3 дня"))
        assertEquals(today.plusDays(5), date("в 5 дней"))
        assertEquals(today.plusDays(3), date("in 3 days"))
        assertEquals(today.plusWeeks(1), date("next week"))
        assertEquals(today.plusWeeks(2), date("через 2 недели"))
    }

    @Test
    fun weekdays() {
        assertEquals(today, date("в пятницу"))
        assertEquals(LocalDate.of(2026, 10, 19), date("в понедельник"))
        assertEquals(LocalDate.of(2026, 10, 21), date("во среду"))
        assertEquals(LocalDate.of(2026, 10, 18), date("on sunday"))
    }

    @Test
    fun absoluteDates() {
        assertEquals(LocalDate.of(2026, 1, 5), date("2026-01-05"))
        assertEquals(LocalDate.of(2026, 10, 20), date("20.10"))
        assertEquals(LocalDate.of(2027, 10, 12), date("12.10")) // already passed this year
        assertEquals(LocalDate.of(2026, 12, 25), date("25 декабря"))
        assertEquals(LocalDate.of(2026, 11, 3), date("3 nov"))
        assertEquals(LocalDate.of(2027, 3, 1), date("01.03.2027"))
        assertNull(date("купить хлеб"))
    }

    @Test
    fun times() {
        assertEquals(LocalTime.of(18, 0), EntityExtractor.extractTime("в 18:00"))
        assertEquals(LocalTime.of(9, 30), EntityExtractor.extractTime("at 9:30"))
        assertEquals(LocalTime.of(15, 0), EntityExtractor.extractTime("at 3pm"))
        assertEquals(LocalTime.of(0, 0), EntityExtractor.extractTime("12am"))
        assertEquals(LocalTime.of(10, 0), EntityExtractor.extractTime("at 10"))
        assertEquals(LocalTime.of(10, 0), EntityExtractor.extractTime("в 10"))
        assertEquals(LocalTime.of(19, 0), EntityExtractor.extractTime("в 7 вечера"))
        assertEquals(LocalTime.of(15, 0), EntityExtractor.extractTime("в 3 дня"))
        assertEquals(LocalTime.of(9, 0), EntityExtractor.extractTime("утром"))
        assertNull(EntityExtractor.extractTime("в 10 дней"))
        assertNull(EntityExtractor.extractTime("12.10"))
    }

    @Test
    fun priority() {
        assertEquals(Priority.HIGH, EntityExtractor.extractPriority("это срочно"))
        assertEquals(Priority.HIGH, EntityExtractor.extractPriority("urgent: fix bug"))
        assertEquals(Priority.LOW, EntityExtractor.extractPriority("не срочно"))
        assertEquals(Priority.LOW, EntityExtractor.extractPriority("someday learn piano"))
        assertNull(EntityExtractor.extractPriority("неважно что"))
    }

    @Test
    fun tags() {
        assertEquals("работа", EntityExtractor.extractTag("сделать отчёт #работа"))
        assertEquals("work", EntityExtractor.extractTag("write report tag: work"))
        assertEquals("Работа", EntityExtractor.extractTag("Работа: позвонить клиенту"))
        assertNull(EntityExtractor.extractTag("напомни: купить хлеб"))
    }

    @Test
    fun titles() {
        assertEquals("Купить хлеб", EntityExtractor.extractTitle("Добавь задачу купить хлеб завтра в 10 срочно #дом", today))
        assertEquals("Позвонить маме", EntityExtractor.extractTitle("напомни позвонить маме в пятницу в 18:00", today))
        assertEquals("Buy milk", EntityExtractor.extractTitle("add task buy milk tomorrow at 10", today))
        assertEquals("Call John", EntityExtractor.extractTitle("remind me to call John at 3pm", today))
        assertEquals("Сдать отчёт", EntityExtractor.extractTitle("новая задача: сдать отчёт 25 декабря", today))
    }

    @Test
    fun stemAndSynonyms() {
        assertEquals("задач", EntityExtractor.stem("задачами"))
        assertEquals(true, EntityExtractor.mentions("добавь дело", "задача"))
        assertEquals(true, EntityExtractor.mentions("new task", "задача"))
        assertEquals(false, EntityExtractor.mentions("купить хлеб", "задача"))
    }
}
