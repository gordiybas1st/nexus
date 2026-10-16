package com.example.assistant.ai

import com.example.assistant.domain.model.Priority
import com.example.assistant.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * Bot phrases. Each reply has several variants picked at random so the assistant
 * sounds less robotic. Persona: friendly, short, a few emoji (✅ ☁️ 🔥 💡).
 * Russian phrases avoid gendered verb forms.
 */
class ResponseGenerator(
    private val random: Random = Random.Default,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private fun pick(vararg variants: String): String = variants[random.nextInt(variants.size)]

    private fun say(lang: Lang, ru: List<String>, en: List<String>): String =
        if (lang == Lang.RU) pick(*ru.toTypedArray()) else pick(*en.toTypedArray())

    // ── Tasks ──

    fun taskCreated(lang: Lang, task: Task): String {
        val details = buildList {
            task.deadline?.let { add(when (lang) { Lang.RU -> "срок: ${whenText(lang, it)}"; Lang.EN -> "due ${whenText(lang, it)}" }) }
            if (task.priority == Priority.HIGH) add(if (lang == Lang.RU) "🔥 высокий приоритет" else "🔥 high priority")
            if (task.priority == Priority.LOW) add(if (lang == Lang.RU) "низкий приоритет" else "low priority")
            task.tag?.let { add("#${it.name}") }
        }.joinToString(", ")
        val head = say(
            lang,
            listOf("✅ Задача «${task.title}» добавлена", "✅ Готово, записано: «${task.title}»", "✅ Добавлено в список: «${task.title}»"),
            listOf("✅ Added “${task.title}”", "✅ Done, “${task.title}” is on your list", "✅ Got it — “${task.title}” saved"),
        )
        return if (details.isEmpty()) head else "$head ($details)"
    }

    fun needTitle(lang: Lang): String = say(
        lang,
        listOf("Что именно добавить? Например: «добавь задачу купить хлеб завтра в 10»."),
        listOf("What should I add? For example: “add task buy milk tomorrow at 10”."),
    )

    fun todayList(lang: Lang, today: List<Task>, overdueCount: Int): String {
        val overdueNote = when {
            overdueCount == 0 -> ""
            lang == Lang.RU -> "\n⚠️ Ещё просрочено: $overdueCount."
            else -> "\n⚠️ Also overdue: $overdueCount."
        }
        if (today.isEmpty()) {
            return say(
                lang,
                listOf("На сегодня задач с дедлайном нет. Можно заняться чем-то из списка или отдохнуть 🙂", "Сегодня свободно — ни одного дедлайна."),
                listOf("Nothing due today. Pick something from your list or take it easy 🙂", "Your day is clear — no deadlines today."),
            ) + overdueNote
        }
        val head = say(
            lang,
            listOf("На сегодня (${today.size}):", "Вот план на сегодня (${today.size}):"),
            listOf("Today (${today.size}):", "Here's your plan for today (${today.size}):"),
        )
        return head + "\n" + today.joinToString("\n") { line(lang, it, timeOnly = true) } + overdueNote
    }

    fun completedCount(lang: Lang, today: Int, week: Int, total: Int): String = when (lang) {
        Lang.RU -> pick(
            "Выполнено сегодня: $today, за 7 дней: $week, всего в списке: $total ✅",
            "Сегодня закрыто $today, за неделю — $week. Всего выполненных: $total.",
        )
        Lang.EN -> pick(
            "Done today: $today, last 7 days: $week, total completed: $total ✅",
            "You closed $today today and $week this week. $total completed in total.",
        )
    }

    fun taskList(lang: Lang, title: String, tasks: List<Task>, empty: String): String =
        if (tasks.isEmpty()) empty else "$title\n" + tasks.joinToString("\n") { line(lang, it, timeOnly = false) }

    fun highPriority(lang: Lang, tasks: List<Task>): String = taskList(
        lang,
        if (lang == Lang.RU) "🔥 Важные задачи (${tasks.size}):" else "🔥 High-priority tasks (${tasks.size}):",
        tasks,
        say(lang, listOf("Срочных задач нет — отлично!"), listOf("No high-priority tasks — nice!")),
    )

    fun overdue(lang: Lang, tasks: List<Task>): String = taskList(
        lang,
        if (lang == Lang.RU) "⚠️ Просрочено (${tasks.size}):" else "⚠️ Overdue (${tasks.size}):",
        tasks,
        say(lang, listOf("Просроченных задач нет 👏", "Ничего не просрочено — так держать!"), listOf("Nothing overdue 👏", "No overdue tasks — keep it up!")),
    )

    fun cleared(lang: Lang, count: Int): String = when {
        count == 0 -> if (lang == Lang.RU) "Выполненных задач нет — чистить нечего." else "No completed tasks to clear."
        lang == Lang.RU -> "🧹 Удалено выполненных задач: $count."
        else -> "🧹 Cleared $count completed task(s)."
    }

    // ── Commands ──

    fun timerUnavailable(lang: Lang): String = say(
        lang,
        listOf("⏱ Таймер Pomodoro появится в следующем обновлении. А пока: 25 минут работы, 5 минут отдыха!"),
        listOf("⏱ The Pomodoro timer arrives in the next update. Meanwhile: 25 minutes of work, 5 minutes of rest!"),
    )

    fun theme(lang: Lang, dark: Boolean): String = when (lang) {
        Lang.RU -> if (dark) "🌙 Включена тёмная тема." else "☀️ Включена светлая тема."
        Lang.EN -> if (dark) "🌙 Dark theme on." else "☀️ Light theme on."
    }

    fun syncStarted(lang: Lang): String =
        if (lang == Lang.RU) "☁️ Запускаю синхронизацию с Google Календарём." else "☁️ Syncing with Google Calendar."

    fun syncNotConnected(lang: Lang): String =
        if (lang == Lang.RU) {
            "☁️ Google Календарь не подключён. Подключить можно в Настройках."
        } else {
            "☁️ Google Calendar isn't connected yet. You can connect it in Settings."
        }

    // ── Advice & analysis ──

    fun advice(lang: Lang): String = "💡 " + AdviceBank.random(lang, random)

    fun progress(lang: Lang, done: Int, planned: Int): String {
        if (done + planned == 0) {
            return if (lang == Lang.RU) "За неделю пока нет задач с дедлайном. Самое время что-нибудь запланировать!" else "No tasks with deadlines this week yet. Time to plan something!"
        }
        val percent = done * 100 / (done + planned)
        val mood = when {
            percent >= 80 -> if (lang == Lang.RU) "Отличный темп 🔥" else "Great pace 🔥"
            percent >= 50 -> if (lang == Lang.RU) "Хорошо идёшь!" else "Good going!"
            else -> if (lang == Lang.RU) "Есть куда расти — начни с одной маленькой задачи." else "Room to grow — start with one small task."
        }
        return if (lang == Lang.RU) {
            "За 7 дней: выполнено $done из ${done + planned} ($percent%). $mood"
        } else {
            "Last 7 days: $done of ${done + planned} done ($percent%). $mood"
        }
    }

    fun whatToDo(lang: Lang, task: Task?): String {
        if (task == null) {
            return say(lang, listOf("Активных задач нет. Можно отдохнуть или запланировать что-то новое 🙂"), listOf("No active tasks. Rest a bit or plan something new 🙂"))
        }
        return say(
            lang,
            listOf("Предлагаю начать с этого:", "Предлагаю взяться за:", "Самое важное сейчас:"),
            listOf("I'd start with:", "Try this one next:", "Most important right now:"),
        ) + "\n" + line(lang, task, timeOnly = false)
    }

    // ── Mood ──

    fun mood(lang: Lang, emotion: Emotion, suggestion: Task?): String = when (emotion) {
        Emotion.TIRED -> say(
            lang,
            listOf(
                "Похоже, пора на перерыв ☕ Отойди от экрана на 5–10 минут, попей воды. Потом можно запустить Pomodoro на 25 минут.",
                "Усталость — сигнал отдохнуть. Короткая прогулка творит чудеса. А потом — одна маленькая задача.",
            ),
            listOf(
                "Sounds like it's break time ☕ Step away for 5–10 minutes and drink some water. Then try a 25-minute Pomodoro.",
                "Tiredness is a signal to rest. A short walk works wonders — then just one small task.",
            ),
        )
        Emotion.STRESSED -> say(
            lang,
            listOf("Выдохни. Давай по одной задаче за раз.", "Когда всего много, помогает выбрать одно дело и сделать только его."),
            listOf("Breathe. One task at a time.", "When everything piles up, pick one thing and do just that."),
        ) + (suggestion?.let { "\n" + line(lang, it, timeOnly = false) } ?: "")
        Emotion.PROUD -> say(
            lang,
            listOf("Ты молодец! 🎉 Каждая закрытая задача — шаг вперёд.", "Так держать! 🔥 Отметь это — ты заслужил(а) отдых.", "Отличная работа! 💪"),
            listOf("Well done! 🎉 Every finished task is a step forward.", "Keep it up! 🔥 You've earned a break.", "Great job! 💪"),
        )
        Emotion.BORED -> if (suggestion != null) {
            say(lang, listOf("Скучно? Вот задача, которую можно сделать прямо сейчас:"), listOf("Bored? Here's something you could do right now:")) +
                "\n" + line(lang, suggestion, timeOnly = false)
        } else {
            advice(lang)
        }
    }

    // ── Small talk ──

    fun greeting(lang: Lang): String = say(
        lang,
        listOf("Привет! 👋 Чем помочь? Могу добавить задачу или показать план на сегодня.", "Привет! Что запланируем?"),
        listOf("Hi! 👋 How can I help? I can add a task or show today's plan.", "Hello! What shall we plan?"),
    )

    fun thanks(lang: Lang): String = say(lang, listOf("Всегда пожалуйста! 🙂", "Обращайся!"), listOf("You're welcome! 🙂", "Anytime!"))

    fun help(lang: Lang): String = if (lang == Lang.RU) {
        """
        Вот что я умею:
        • «добавь задачу купить хлеб завтра в 10 срочно #дом»
        • «напомни позвонить маме в пятницу в 18:00»
        • «что на сегодня», «что просрочено», «покажи важные задачи»
        • «сколько выполнено», «как успехи», «что мне делать»
        • «очисти выполненные», «включи тёмную тему», «синхронизируй с календарём»
        • «посоветуй», а ещё можно просто сказать, что устал(а) или скучно
        """.trimIndent()
    } else {
        """
        Here's what I can do:
        • “add task buy milk tomorrow at 10 urgent #home”
        • “remind me to call mom on friday at 6pm”
        • “what's today”, “overdue”, “show high priority tasks”
        • “how many done”, “my progress”, “what should I do”
        • “clear completed”, “dark mode”, “sync”
        • “give me a tip”, or just tell me you're tired or bored
        """.trimIndent()
    }

    fun fallback(lang: Lang): String = say(
        lang,
        listOf("Не совсем понятно 🤔 Попробуй «добавь задачу …» или «что на сегодня». Напиши «помощь», чтобы увидеть все команды."),
        listOf("I didn't quite get that 🤔 Try “add task …” or “what's today”. Type “help” to see everything I can do."),
    )

    // ── Formatting ──

    private fun line(lang: Lang, task: Task, timeOnly: Boolean): String {
        val marker = when (task.priority) {
            Priority.HIGH -> "🔥 "
            else -> "• "
        }
        val deadline = task.deadline?.let {
            if (timeOnly) Instant.ofEpochMilli(it).atZone(zone).format(TIME) + " — " else whenText(lang, it) + " — "
        }.orEmpty()
        return marker + deadline + task.title
    }

    private fun whenText(lang: Lang, millis: Long): String {
        val dt = Instant.ofEpochMilli(millis).atZone(zone)
        val today = LocalDate.now(zone)
        val time = dt.format(TIME)
        return when (dt.toLocalDate()) {
            today -> if (lang == Lang.RU) "сегодня $time" else "today $time"
            today.plusDays(1) -> if (lang == Lang.RU) "завтра $time" else "tomorrow $time"
            else -> dt.format(DateTimeFormatter.ofPattern("d MMM HH:mm", if (lang == Lang.RU) Locale("ru") else Locale.ENGLISH))
        }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
