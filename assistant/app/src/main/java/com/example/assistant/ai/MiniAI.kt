package com.example.assistant.ai

import com.example.assistant.domain.model.Priority
import com.example.assistant.domain.model.Task
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.random.Random

/** Side effect the UI should perform after showing a reply. */
sealed interface BotAction {
    /** Offer an "Open" button for a task. */
    data class OpenTask(val taskId: Long) : BotAction
    data object StartTimer : BotAction
}

/** Assistant answer. */
data class BotReply(val text: String, val action: BotAction? = null)

/**
 * What the assistant can read and change. Implemented on top of the repositories
 * (see `AppAiContext`) and by fakes in unit tests.
 */
interface AiContext {
    val zone: ZoneId
    fun today(): LocalDate
    fun now(): Long

    suspend fun tasks(): List<Task>

    /** Creates a task; [tagName] is found or created. Returns the saved task. */
    suspend fun createTask(title: String, deadline: Long?, priority: Priority, tagName: String?): Task
    suspend fun clearCompleted(): Int
    suspend fun setDarkTheme(dark: Boolean)

    /** Starts calendar sync; false when no calendar is connected. */
    suspend fun requestSync(): Boolean

    /** Whether the Pomodoro timer can be started (false in tests). */
    fun isTimerAvailable(): Boolean
}

/**
 * Offline, rule-based assistant: [IntentParser] understands the message,
 * this class performs it through [AiContext], and [ResponseGenerator] words the answer.
 */
class MiniAI(
    private val random: Random,
) {
    @Inject
    constructor() : this(Random.Default)

    private val parser = IntentParser()

    /** Default time when only a date is given. */
    private val defaultTime: LocalTime = LocalTime.of(9, 0)

    /** Handles one user message and returns the reply. Never throws for bad input. */
    suspend fun processMessage(text: String, ctx: AiContext): BotReply {
        val lang = Lang.detect(text)
        val responses = ResponseGenerator(random, ctx.zone)
        return when (val intent = parser.parse(text, ctx.today())) {
            is UserIntent.CreateTask -> createTask(intent, lang, ctx, responses)
            UserIntent.QueryToday -> {
                val all = ctx.tasks()
                val today = all.filter { !it.isDone && it.deadline?.let { d -> dateOf(d, ctx) == ctx.today() } == true }
                    .sortedBy { it.deadline }
                val overdue = all.count { it.isOverdue(ctx.now()) && it.deadline?.let { d -> dateOf(d, ctx) != ctx.today() } == true }
                BotReply(responses.todayList(lang, today, overdue))
            }
            UserIntent.QueryCompleted -> {
                val all = ctx.tasks()
                val startOfToday = ctx.today().atStartOfDay(ctx.zone).toInstant().toEpochMilli()
                val weekAgo = ctx.today().minusDays(6).atStartOfDay(ctx.zone).toInstant().toEpochMilli()
                val done = all.filter { it.isDone }
                BotReply(
                    responses.completedCount(
                        lang,
                        today = done.count { (it.completedAt ?: 0L) >= startOfToday },
                        week = done.count { (it.completedAt ?: 0L) >= weekAgo },
                        total = done.size,
                    ),
                )
            }
            UserIntent.QueryHighPriority -> {
                val tasks = ctx.tasks().filter { !it.isDone && it.priority == Priority.HIGH }.sortedWith(urgency(ctx.now()))
                BotReply(responses.highPriority(lang, tasks.take(MAX_LIST)))
            }
            UserIntent.QueryOverdue -> {
                val tasks = ctx.tasks().filter { it.isOverdue(ctx.now()) }.sortedBy { it.deadline }
                BotReply(responses.overdue(lang, tasks.take(MAX_LIST)))
            }
            UserIntent.ClearCompleted -> BotReply(responses.cleared(lang, ctx.clearCompleted()))
            UserIntent.StartTimer ->
                if (ctx.isTimerAvailable()) BotReply(responses.timerStarted(lang), BotAction.StartTimer) else BotReply(responses.timerUnavailable(lang))
            is UserIntent.SetTheme -> {
                ctx.setDarkTheme(intent.dark)
                BotReply(responses.theme(lang, intent.dark))
            }
            UserIntent.Sync ->
                BotReply(if (ctx.requestSync()) responses.syncStarted(lang) else responses.syncNotConnected(lang))
            UserIntent.Advice -> BotReply(responses.advice(lang))
            UserIntent.Progress -> {
                val all = ctx.tasks()
                val weekAgo = ctx.today().minusDays(6).atStartOfDay(ctx.zone).toInstant().toEpochMilli()
                val weekEnd = ctx.today().plusDays(1).atStartOfDay(ctx.zone).toInstant().toEpochMilli()
                val done = all.count { it.isDone && (it.completedAt ?: 0L) >= weekAgo }
                val planned = all.count { !it.isDone && it.deadline != null && it.deadline < weekEnd }
                BotReply(responses.progress(lang, done, planned))
            }
            UserIntent.WhatToDo -> {
                val top = ctx.tasks().filter { !it.isDone }.sortedWith(urgency(ctx.now())).firstOrNull()
                BotReply(responses.whatToDo(lang, top), top?.let { BotAction.OpenTask(it.id) })
            }
            is UserIntent.Mood -> {
                val active = ctx.tasks().filter { !it.isDone }
                val suggestion = when (intent.emotion) {
                    Emotion.BORED -> active.filter { it.priority != Priority.HIGH }.randomOrNull(random) ?: active.randomOrNull(random)
                    Emotion.STRESSED -> active.sortedWith(urgency(ctx.now())).firstOrNull()
                    else -> null
                }
                BotReply(responses.mood(lang, intent.emotion, suggestion), suggestion?.let { BotAction.OpenTask(it.id) })
            }
            UserIntent.Greeting -> BotReply(responses.greeting(lang))
            UserIntent.Thanks -> BotReply(responses.thanks(lang))
            UserIntent.Help -> BotReply(responses.help(lang))
            UserIntent.Unknown -> BotReply(responses.fallback(lang))
        }
    }

    private suspend fun createTask(intent: UserIntent.CreateTask, lang: Lang, ctx: AiContext, responses: ResponseGenerator): BotReply {
        if (intent.title.isBlank()) return BotReply(responses.needTitle(lang))
        val deadline = deadlineOf(intent.date, intent.time, ctx)
        val task = ctx.createTask(intent.title, deadline, intent.priority ?: Priority.MEDIUM, intent.tag)
        return BotReply(responses.taskCreated(lang, task), BotAction.OpenTask(task.id))
    }

    /**
     * Date + time → epoch millis. Time only → today, or tomorrow if that time has passed.
     * Date only → [defaultTime] that day.
     */
    private fun deadlineOf(date: LocalDate?, time: LocalTime?, ctx: AiContext): Long? {
        if (date == null && time == null) return null
        val day = date ?: ctx.today()
        var millis = day.atTime(time ?: defaultTime).atZone(ctx.zone).toInstant().toEpochMilli()
        if (date == null && millis < ctx.now()) {
            millis = day.plusDays(1).atTime(time ?: defaultTime).atZone(ctx.zone).toInstant().toEpochMilli()
        }
        return millis
    }

    private fun dateOf(millis: Long, ctx: AiContext): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(ctx.zone).toLocalDate()

    private companion object {
        const val MAX_LIST = 10

        /** Overdue (relative to [now]) first, then higher priority, then nearest deadline. */
        fun urgency(now: Long): Comparator<Task> = compareBy<Task> { !it.isOverdue(now) }
            .thenByDescending { it.priority.ordinal }
            .thenBy { it.deadline ?: Long.MAX_VALUE }
    }
}
