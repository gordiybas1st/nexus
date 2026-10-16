package com.example.assistant.ai

import com.example.assistant.domain.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class IntentParserTest {

    private val parser = IntentParser()
    private val today = LocalDate.of(2026, 10, 16)

    private fun parse(text: String) = parser.parse(text, today)

    @Test
    fun createTask() {
        val intent = parse("Добавь задачу купить хлеб завтра в 10 срочно #дом")
        assertEquals(
            UserIntent.CreateTask("Купить хлеб", today.plusDays(1), LocalTime.of(10, 0), Priority.HIGH, "дом"),
            intent,
        )
        // A create command wins even if the title contains other keywords.
        assertTrue(parse("добавь задачу проверить синхронизацию") is UserIntent.CreateTask)
        assertTrue(parse("remind me to start the timer") is UserIntent.CreateTask)
    }

    @Test
    fun queries() {
        assertEquals(UserIntent.QueryToday, parse("Что на сегодня?"))
        assertEquals(UserIntent.QueryToday, parse("what's today?"))
        assertEquals(UserIntent.QueryCompleted, parse("сколько выполнено"))
        assertEquals(UserIntent.QueryHighPriority, parse("покажи задачи с высоким приоритетом"))
        assertEquals(UserIntent.QueryOverdue, parse("что просрочено"))
        assertEquals(UserIntent.Progress, parse("как успехи?"))
        assertEquals(UserIntent.WhatToDo, parse("что мне делать"))
        assertEquals(UserIntent.WhatToDo, parse("What should I do?"))
    }

    @Test
    fun commands() {
        assertEquals(UserIntent.ClearCompleted, parse("очисти выполненные"))
        assertEquals(UserIntent.StartTimer, parse("запусти таймер"))
        assertEquals(UserIntent.StartTimer, parse("start pomodoro"))
        assertEquals(UserIntent.SetTheme(dark = true), parse("включи тёмную тему"))
        assertEquals(UserIntent.SetTheme(dark = true), parse("dark mode"))
        assertEquals(UserIntent.SetTheme(dark = false), parse("светлая тема"))
        assertEquals(UserIntent.Sync, parse("синхронизируй с календарём"))
        assertEquals(UserIntent.Advice, parse("дай совет"))
        assertEquals(UserIntent.Advice, parse("give me a tip"))
    }

    @Test
    fun moodAndSmallTalk() {
        assertEquals(UserIntent.Mood(Emotion.TIRED), parse("я устал, что мне делать?"))
        assertEquals(UserIntent.Mood(Emotion.TIRED), parse("Нет сил"))
        assertEquals(UserIntent.Mood(Emotion.TIRED), parse("I'm tired"))
        assertEquals(UserIntent.Mood(Emotion.BORED), parse("мне скучно"))
        assertEquals(UserIntent.Mood(Emotion.PROUD), parse("я молодец!"))
        assertEquals(UserIntent.Greeting, parse("Привет"))
        assertEquals(UserIntent.Thanks, parse("спасибо!"))
        assertEquals(UserIntent.Help, parse("что ты умеешь?"))
        assertEquals(UserIntent.Unknown, parse("абракадабра"))
    }

    @Test
    fun languageDetection() {
        assertEquals(Lang.RU, Lang.detect("Привет"))
        assertEquals(Lang.EN, Lang.detect("Hello"))
    }
}
