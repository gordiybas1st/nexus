package com.example.assistant.ai

import com.example.assistant.domain.model.Priority
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/** In-memory [AiContext] with a fixed clock (Friday 2026-10-16 12:00 UTC). */
private class FakeContext(
    nowTime: LocalDateTime = LocalDateTime.of(2026, 10, 16, 12, 0),
) : AiContext {
    override val zone: ZoneId = ZoneOffset.UTC
    private val nowMillis = nowTime.toInstant(ZoneOffset.UTC).toEpochMilli()
    val tasks = mutableListOf<Task>()
    var dark: Boolean? = null
    var cleared = false

    override fun today(): LocalDate = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    override fun now(): Long = nowMillis
    override suspend fun tasks(): List<Task> = tasks.toList()

    override suspend fun createTask(title: String, deadline: Long?, priority: Priority, tagName: String?): Task {
        val task = Task(
            id = tasks.size + 1L,
            title = title,
            deadline = deadline,
            priority = priority,
            tag = tagName?.let { Tag(1, it, "#3B82F6") },
        )
        tasks += task
        return task
    }

    override suspend fun clearCompleted(): Int {
        cleared = true
        val done = tasks.count { it.isDone }
        tasks.removeAll { it.isDone }
        return done
    }

    override suspend fun setDarkTheme(dark: Boolean) {
        this.dark = dark
    }

    override suspend fun requestSync(): Boolean = false
    override fun isTimerAvailable(): Boolean = false

    fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
}

class MiniAITest {

    private val ai = MiniAI(Random(42))

    @Test
    fun createsTaskWithAllEntities() = runBlocking {
        val ctx = FakeContext()
        val reply = ai.processMessage("Добавь задачу купить хлеб завтра в 10 срочно #дом", ctx)

        assertEquals(1, ctx.tasks.size)
        val task = ctx.tasks.single()
        assertEquals("Купить хлеб", task.title)
        assertEquals(ctx.at(LocalDate.of(2026, 10, 17), 10), task.deadline)
        assertEquals(Priority.HIGH, task.priority)
        assertEquals("дом", task.tag?.name)
        assertTrue(reply.text.contains("Купить хлеб"))
        assertEquals(BotAction.OpenTask(1), reply.action)
    }

    @Test
    fun timeOnlyInThePastMovesToTomorrow() = runBlocking {
        val ctx = FakeContext(LocalDateTime.of(2026, 10, 16, 20, 0))
        ai.processMessage("напомни позвонить маме в 18:00", ctx)
        assertEquals(ctx.at(LocalDate.of(2026, 10, 17), 18), ctx.tasks.single().deadline)
    }

    @Test
    fun dateOnlyUsesMorning() = runBlocking {
        val ctx = FakeContext()
        ai.processMessage("add task pay rent on monday", ctx)
        assertEquals("Pay rent", ctx.tasks.single().title)
        assertEquals(ctx.at(LocalDate.of(2026, 10, 19), 9), ctx.tasks.single().deadline)
    }

    @Test
    fun emptyTitleAsksForDetails() = runBlocking {
        val ctx = FakeContext()
        val reply = ai.processMessage("добавь задачу завтра", ctx)
        assertTrue(ctx.tasks.isEmpty())
        assertTrue(reply.text.contains("добав", ignoreCase = true))
    }

    @Test
    fun todayListsOnlyTodaysTasks() = runBlocking {
        val ctx = FakeContext()
        val today = LocalDate.of(2026, 10, 16)
        ctx.tasks += Task(id = 1, title = "Сдать отчёт", deadline = ctx.at(today, 18))
        ctx.tasks += Task(id = 2, title = "Купить подарок", deadline = ctx.at(today.plusDays(3), 10))
        val reply = ai.processMessage("Что на сегодня?", ctx)
        assertTrue(reply.text.contains("Сдать отчёт"))
        assertFalse(reply.text.contains("Купить подарок"))
    }

    @Test
    fun overdueAndWhatToDo() = runBlocking {
        val ctx = FakeContext()
        val yesterday = LocalDate.of(2026, 10, 15)
        ctx.tasks += Task(id = 1, title = "Old task", deadline = ctx.at(yesterday, 10))
        ctx.tasks += Task(id = 2, title = "Important task", priority = Priority.HIGH)
        assertTrue(ai.processMessage("what's overdue?", ctx).text.contains("Old task"))
        // Overdue beats priority.
        val next = ai.processMessage("what should I do", ctx)
        assertTrue(next.text.contains("Old task"))
        assertEquals(BotAction.OpenTask(1), next.action)
    }

    @Test
    fun commandsCallContext() = runBlocking {
        val ctx = FakeContext()
        ai.processMessage("включи тёмную тему", ctx)
        assertEquals(true, ctx.dark)
        ai.processMessage("light mode please", ctx)
        assertEquals(false, ctx.dark)
        ai.processMessage("очисти выполненные", ctx)
        assertTrue(ctx.cleared)
        val sync = ai.processMessage("синхронизируй с календарём", ctx)
        assertTrue(sync.text.contains("не подключён"))
    }

    @Test
    fun unknownTextGetsFallback() = runBlocking {
        val reply = ai.processMessage("абракадабра", FakeContext())
        assertTrue(reply.text.contains("помощь"))
    }
}
