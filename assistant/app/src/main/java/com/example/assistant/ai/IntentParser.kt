package com.example.assistant.ai

import com.example.assistant.domain.model.Priority
import java.time.LocalDate
import java.time.LocalTime

/** Reply language, detected from the user's message. */
enum class Lang {
    RU, EN;

    companion object {
        /** Cyrillic letters → Russian, otherwise English. */
        fun detect(text: String): Lang = if (text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) RU else EN
    }
}

/** What the user wants (named to avoid clashing with android.content.Intent). */
sealed interface UserIntent {
    data class CreateTask(
        val title: String,
        val date: LocalDate?,
        val time: LocalTime?,
        val priority: Priority?,
        val tag: String?,
    ) : UserIntent

    data object QueryToday : UserIntent
    data object QueryCompleted : UserIntent
    data object QueryHighPriority : UserIntent
    data object QueryOverdue : UserIntent
    data object ClearCompleted : UserIntent
    data object StartTimer : UserIntent

    /** [dark] = true for dark theme, false for light. */
    data class SetTheme(val dark: Boolean) : UserIntent
    data object Sync : UserIntent
    data object Advice : UserIntent
    data object Progress : UserIntent
    data object WhatToDo : UserIntent
    data class Mood(val emotion: Emotion) : UserIntent
    data object Greeting : UserIntent
    data object Thanks : UserIntent
    data object Help : UserIntent
    data object Unknown : UserIntent
}

/**
 * A matching rule: the first rule whose [pattern] matches the normalized message wins.
 *
 * @property build turns the match (and the original text) into an [UserIntent].
 */
data class Rule(
    val name: String,
    val pattern: Regex,
    val build: (match: MatchResult, original: String, today: LocalDate) -> UserIntent,
)

/** Natural-language understanding: message → [UserIntent] using ordered regex rules (RU + EN). */
class IntentParser {

    private val b0 = "(?<![\\p{L}\\d])"

    /**
     * Rules in priority order. "create_task" is first because it only matches a message that
     * starts with a create verb, whose title may contain other keywords ("…sync settings").
     */
    val rules: List<Rule> = listOf(
        Rule(
            "create_task",
            Regex("^\\s*(?:пожалуйста\\s+|please\\s+)?(?:добав|созда|запиш|напомн|нов(?:ая|ую|ое)\\s+(?:задач|дел)|add|create|remind|new task|todo)"),
        ) { _, original, today ->
            UserIntent.CreateTask(
                title = EntityExtractor.extractTitle(original, today),
                date = EntityExtractor.extractDate(original, today),
                time = EntityExtractor.extractTime(original),
                priority = EntityExtractor.extractPriority(original),
                tag = EntityExtractor.extractTag(original),
            )
        },
        Rule(
            "clear_completed",
            Regex("$b0(?:очист|удал|убер|сотри|clear|delete|remove)[a-zа-я]*\\s+(?:все\\s+|all\\s+)?(?:выполненн|сделанн|завершенн|готов|completed|done|finished)"),
        ) { _, _, _ -> UserIntent.ClearCompleted },
        Rule(
            "start_timer",
            Regex("$b0(?:(?:запусти|включи|начни|поставь|старт|start|run|begin|set)[a-zа-я]*\\s+(?:a\\s+)?(?:таймер|помодоро|pomodoro|timer|фокус|focus)|^\\s*(?:таймер|помодоро|pomodoro|timer)\\s*[!.]?\\s*$)"),
        ) { _, _, _ -> UserIntent.StartTimer },
        Rule(
            "dark_theme",
            Regex("$b0(?:темн[а-я]*|ночн[а-я]*|dark)\\s*(?:тем[а-я]*|режим[а-я]*|mode|theme)"),
        ) { _, _, _ -> UserIntent.SetTheme(dark = true) },
        Rule(
            "light_theme",
            Regex("$b0(?:светл[а-я]*|дневн[а-я]*|light)\\s*(?:тем[а-я]*|режим[а-я]*|mode|theme)"),
        ) { _, _, _ -> UserIntent.SetTheme(dark = false) },
        Rule(
            "sync",
            Regex("$b0(?:синхрониз[а-я]*|sync[a-z]*)"),
        ) { _, _, _ -> UserIntent.Sync },
        Rule(
            "overdue",
            Regex("$b0(?:просроч[а-я]*|overdue|missed|late tasks)"),
        ) { _, _, _ -> UserIntent.QueryOverdue },
        Rule(
            "high_priority",
            Regex("(?:$b0(?:высок|важн|срочн|high|urgent|important)[a-zа-я]*.*(?:приоритет|задач|дел|tasks?|priority))|(?:(?:покажи|show|list).*(?:важн|срочн|high|urgent))"),
        ) { _, _, _ -> UserIntent.QueryHighPriority },
        Rule(
            "completed_count",
            Regex("(?:(?:сколько|how many).*(?:выполн|сделал|сделан|заверш|done|complet|finish))|$b0(?:выполнено|completed today)"),
        ) { _, _, _ -> UserIntent.QueryCompleted },
        Rule(
            "progress",
            Regex("$b0(?:как\\s+(?:успехи|мои успехи|прогресс)|прогресс|статистик[а-я]*|итоги недели|my progress|how am i doing|progress|stats)"),
        ) { _, _, _ -> UserIntent.Progress },
        Rule(
            "today",
            Regex("(?:(?:что|какие|покажи|what'?s?|what is|show).*(?:сегодня|today))|$b0(?:на сегодня|план на день|planned today|today'?s?\\s+(?:plan|tasks)|agenda)"),
        ) { _, _, _ -> UserIntent.QueryToday },
        Rule(
            "what_to_do",
            Regex("(?:что\\s+(?:мне\\s+)?(?:делать|сделать|дальше)|с чего начать|за что взяться|what should i do|what (?:to do|next)|what now)"),
        ) { _, _, _ -> UserIntent.WhatToDo },
        Rule(
            "advice",
            Regex("$b0(?:посоветуй|совет[а-я]*|подскажи|лайфхак|мотивац[а-я]*|tip|tips|advice|advise|motivat[a-z]*)"),
        ) { _, _, _ -> UserIntent.Advice },
        Rule(
            "greeting",
            Regex("^\\s*(?:привет|здравствуй[а-я]*|добрый\\s+\\p{L}+|доброе утро|хай|hi|hello|hey|good morning|good evening)"),
        ) { _, _, _ -> UserIntent.Greeting },
        Rule(
            "thanks",
            Regex("$b0(?:спасибо|благодарю|thanks|thank you|thx)"),
        ) { _, _, _ -> UserIntent.Thanks },
        Rule(
            "help",
            Regex("$b0(?:помощь|помоги|что ты умеешь|что умеешь|команды|help|what can you do|commands)"),
        ) { _, _, _ -> UserIntent.Help },
    )

    /** Parses [text]; mood keywords are checked right after explicit commands. */
    fun parse(text: String, today: LocalDate = LocalDate.now()): UserIntent {
        val normalized = EntityExtractor.normalize(text).trim()
        if (normalized.isEmpty()) return UserIntent.Unknown

        val commandRules = rules.takeWhile { it.name != "overdue" }
        val queryRules = rules.dropWhile { it.name != "overdue" }

        matchFirst(commandRules, normalized, text, today)?.let { return it }
        SentimentRules.detect(normalized)?.let { return UserIntent.Mood(it) }
        matchFirst(queryRules, normalized, text, today)?.let { return it }
        return UserIntent.Unknown
    }

    private fun matchFirst(rules: List<Rule>, normalized: String, original: String, today: LocalDate): UserIntent? {
        for (rule in rules) {
            val match = rule.pattern.find(normalized) ?: continue
            return rule.build(match, original, today)
        }
        return null
    }
}
