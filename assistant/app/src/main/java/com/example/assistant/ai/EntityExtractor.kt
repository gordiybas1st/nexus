package com.example.assistant.ai

import com.example.assistant.domain.model.Priority
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** A value found in text together with the character range it came from. */
data class Found<T>(val value: T, val range: IntRange)

/**
 * Pulls dates, times, priority, tags and a task title out of free text (RU + EN).
 *
 * Pure Kotlin + java.time: no Android dependencies, so it is unit-tested on the JVM.
 * All matching runs on [normalize]d text, which keeps the original length, so the
 * returned ranges can be cut out of the original string.
 */
object EntityExtractor {

    /** Letter/digit boundaries that also work for Cyrillic (`\b` does not). */
    private const val B0 = "(?<![\\p{L}\\d])"
    private const val B1 = "(?![\\p{L}\\d])"

    /** Lowercases and maps ё→е without changing the string length. */
    fun normalize(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            val lower = ch.lowercaseChar()
            append(if (lower == 'ё') 'е' else lower)
        }
    }

    // ───────────────────────────── Russian helpers ─────────────────────────────

    private val RU_ENDINGS = listOf(
        "иями", "ями", "ами", "ого", "его", "ому", "ему", "ыми", "ими", "ать", "ять", "ить", "еть",
        "ешь", "ишь", "ете", "ите", "ует", "ют", "ут", "ая", "яя", "ое", "ее", "ые", "ие", "ый", "ий",
        "ой", "ов", "ев", "ей", "ам", "ям", "ах", "ях", "ом", "ем", "ю", "у", "а", "я", "ы", "и", "е", "о", "ь",
    )

    /** Very small stemmer: strips one common Russian ending from words longer than 4 letters. */
    fun stem(word: String): String {
        val w = normalize(word)
        if (w.length <= 4) return w
        val ending = RU_ENDINGS.firstOrNull { w.endsWith(it) && w.length - it.length >= 3 } ?: return w
        return w.dropLast(ending.length)
    }

    /** Canonical word → synonyms (compared by stem). */
    val SYNONYMS: Map<String, List<String>> = mapOf(
        "задача" to listOf("задача", "дело", "таск", "task", "todo"),
        "напомни" to listOf("напомни", "напоминание", "remind", "reminder"),
        "заметка" to listOf("заметка", "note"),
    )

    /** True if [text] contains [canonical] or one of its synonyms (stem match). */
    fun mentions(text: String, canonical: String): Boolean {
        val stems = (SYNONYMS[canonical] ?: listOf(canonical)).map { stem(it) }
        return words(text).any { word -> stems.any { word.startsWith(it) } }
    }

    private fun words(text: String): List<String> =
        Regex("[\\p{L}\\d]+").findAll(normalize(text)).map { stem(it.value) }.toList()

    // ───────────────────────────────── Dates ──────────────────────────────────

    private val MONTHS = listOf(
        "январ" to 1, "феврал" to 2, "март" to 3, "апрел" to 4, "мая" to 5, "май" to 5, "июн" to 6, "июл" to 7,
        "август" to 8, "сентябр" to 9, "октябр" to 10, "ноябр" to 11, "декабр" to 12,
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8,
        "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    private val WEEKDAYS = listOf(
        "понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY, "сред" to DayOfWeek.WEDNESDAY,
        "четверг" to DayOfWeek.THURSDAY, "пятниц" to DayOfWeek.FRIDAY, "суббот" to DayOfWeek.SATURDAY,
        "воскресен" to DayOfWeek.SUNDAY,
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private val ISO_DATE = Regex("$B0(\\d{4})-(\\d{1,2})-(\\d{1,2})$B1")
    private val DOT_DATE = Regex("$B0(?<![:.])(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?(?![\\d:])$B1")
    private val MONTH_NAME_DATE = Regex("$B0(\\d{1,2})\\s+([a-zа-я]+)$B1")
    private val DAY_AFTER_TOMORROW = Regex("$B0(?:послезавтра|day after tomorrow)$B1")
    private val TOMORROW = Regex("$B0(?:завтра|tomorrow)$B1")
    private val TODAY = Regex("$B0(?:сегодня|today|tonight)$B1")
    private val IN_DAYS = Regex("$B0(?:через|in)\\s+(\\d{1,3})\\s+(?:день|дня|дней|days?)$B1|${B0}в\\s+(\\d{1,3})\\s+(?:день|дней)$B1")
    private val IN_WEEKS = Regex("$B0(?:через|in)\\s+(\\d{1,2})\\s+(?:недел[а-я]*|weeks?)$B1")
    private val ONE_WEEK = Regex("$B0(?:через неделю|in a week|next week)$B1")
    private val WEEKDAY = Regex("$B0(?:(?:во|в|on|next|this)\\s+)?([a-zа-я]+)$B1")

    /** Finds a date in [text] relative to [today]. */
    fun findDate(text: String, today: LocalDate): Found<LocalDate>? {
        val t = normalize(text)

        ISO_DATE.find(t)?.let { m ->
            val (y, mo, d) = m.destructured
            safeDate(y.toInt(), mo.toInt(), d.toInt())?.let { return Found(it, m.range) }
        }
        DOT_DATE.find(t)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val yearRaw = m.groupValues[3]
            val year = when {
                yearRaw.isEmpty() -> null
                yearRaw.length == 2 -> 2000 + yearRaw.toInt()
                else -> yearRaw.toInt()
            }
            resolveDayMonth(day, month, year, today)?.let { return Found(it, m.range) }
        }
        MONTH_NAME_DATE.findAll(t).forEach { m ->
            val month = MONTHS.firstOrNull { (prefix, _) -> m.groupValues[2].startsWith(prefix) }?.second
            if (month != null) {
                resolveDayMonth(m.groupValues[1].toInt(), month, null, today)?.let { return Found(it, m.range) }
            }
        }
        DAY_AFTER_TOMORROW.find(t)?.let { return Found(today.plusDays(2), it.range) }
        TOMORROW.find(t)?.let { return Found(today.plusDays(1), it.range) }
        TODAY.find(t)?.let { return Found(today, it.range) }
        IN_DAYS.find(t)?.let { m ->
            val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toLong()
            return Found(today.plusDays(n), m.range)
        }
        IN_WEEKS.find(t)?.let { m -> return Found(today.plusWeeks(m.groupValues[1].toLong()), m.range) }
        ONE_WEEK.find(t)?.let { return Found(today.plusWeeks(1), it.range) }
        WEEKDAY.findAll(t).forEach { m ->
            val day = WEEKDAYS.firstOrNull { (prefix, _) -> m.groupValues[1].startsWith(prefix) }?.second
            if (day != null) return Found(today.with(TemporalAdjusters.nextOrSame(day)), m.range)
        }
        return null
    }

    fun extractDate(text: String, today: LocalDate = LocalDate.now()): LocalDate? = findDate(text, today)?.value

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /** Day + month without a year means the next such date (this year or next). */
    private fun resolveDayMonth(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? {
        if (year != null) return safeDate(year, month, day)
        val thisYear = safeDate(today.year, month, day) ?: return null
        return if (thisYear.isBefore(today)) safeDate(today.year + 1, month, day) else thisYear
    }

    // ───────────────────────────────── Times ──────────────────────────────────

    private val CLOCK = Regex("$B0([01]?\\d|2[0-3])[:.]([0-5]\\d)$B1(?![./]\\d)")
    private val AM_PM = Regex("$B0(\\d{1,2})\\s*(am|pm)$B1")
    private val AT_HOUR = Regex("${B0}at\\s+(\\d{1,2})$B1(?![:.]\\d)")
    private val RU_HOUR = Regex(
        "${B0}(?:в|к)\\s+(\\d{1,2})(?![:./]\\d)(?:\\s*(?:часов|часа|час|ч))?" +
            "(?:\\s+(утра|дня|вечера|ночи))?$B1(?!\\s*(?:дней|день|недел))",
    )
    private val PART_OF_DAY = listOf(
        Regex("$B0(?:в полдень|noon|midday)$B1") to LocalTime.of(12, 0),
        Regex("$B0(?:утром|morning|in the morning)$B1") to LocalTime.of(9, 0),
        Regex("$B0(?:днем|afternoon|in the afternoon)$B1") to LocalTime.of(14, 0),
        Regex("$B0(?:вечером|evening|in the evening|tonight)$B1") to LocalTime.of(19, 0),
        Regex("$B0(?:ночью|at night)$B1") to LocalTime.of(22, 0),
    )

    /** Finds a time of day in [text]. */
    fun findTime(text: String): Found<LocalTime>? {
        val t = normalize(text)
        CLOCK.find(t)?.let { m ->
            // "12.10" is more likely a date; only accept the dot form when it cannot be one.
            val isDot = t[m.range.first + m.groupValues[1].length] == '.'
            val minutes = m.groupValues[2].toInt()
            if (!isDot || minutes > 12 || minutes == 0) {
                return Found(LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()), m.range)
            }
        }
        AM_PM.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h in 1..12) {
                val hour = when {
                    m.groupValues[2] == "am" -> if (h == 12) 0 else h
                    else -> if (h == 12) 12 else h + 12
                }
                return Found(LocalTime.of(hour, 0), m.range)
            }
        }
        AT_HOUR.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h in 0..23) return Found(LocalTime.of(h, 0), m.range)
        }
        RU_HOUR.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val hour = when (m.groupValues[2]) {
                "дня", "вечера" -> if (h in 1..11) h + 12 else h
                "ночи" -> if (h == 12) 0 else h
                "утра" -> if (h == 12) 0 else h
                else -> h
            }
            if (hour in 0..23) return Found(LocalTime.of(hour, 0), m.range)
        }
        PART_OF_DAY.forEach { (regex, time) -> regex.find(t)?.let { return Found(time, it.range) } }
        return null
    }

    fun extractTime(text: String): LocalTime? = findTime(text)?.value

    // ─────────────────────────────── Priority ─────────────────────────────────

    private val LOW = Regex(
        "$B0(?:не\\s+срочн[а-я]*|не\\s+важн[а-я]*|когда-нибудь|когда нибудь|потом|не горит|someday|some day|later|low priority)$B1",
    )
    private val HIGH = Regex("$B0(?:срочн[а-я]*|важн[а-я]*|asap|urgent(?:ly)?|important|high priority)$B1|!{2,}")

    fun findPriority(text: String): Found<Priority>? {
        val t = normalize(text)
        LOW.find(t)?.let { return Found(Priority.LOW, it.range) }
        HIGH.find(t)?.let { return Found(Priority.HIGH, it.range) }
        return null
    }

    fun extractPriority(text: String): Priority? = findPriority(text)?.value

    // ────────────────────────────────── Tag ───────────────────────────────────

    private val HASH_TAG = Regex("#([\\p{L}\\d_-]+)")
    private val NAMED_TAG = Regex("$B0(?:tag|тег)\\s*:?\\s*([\\p{L}\\d_-]+)")
    private val PREFIX_TAG = Regex("^\\s*([\\p{L}]+)\\s*:\\s+")
    private val NOT_TAGS = setOf(
        "напомни", "напомнить", "добавь", "добавить", "создай", "задача", "задачу", "заметка", "запиши",
        "task", "add", "remind", "note", "todo", "нужно", "надо", "новая", "new",
    )

    /** Finds a tag: `#work`, `tag: work`, `тег работа` or a `работа:` prefix. Value keeps the user's case. */
    fun findTag(text: String): Found<String>? {
        val t = normalize(text)
        HASH_TAG.find(t)?.let { return Found(text.substring(it.groups[1]!!.range), it.range) }
        NAMED_TAG.find(t)?.let { return Found(text.substring(it.groups[1]!!.range), it.range) }
        PREFIX_TAG.find(t)?.let { m ->
            val word = m.groupValues[1]
            if (word !in NOT_TAGS) return Found(text.substring(m.groups[1]!!.range), m.range)
        }
        return null
    }

    fun extractTag(text: String): String? = findTag(text)?.value

    // ───────────────────────────────── Title ──────────────────────────────────

    private val COMMAND_WORDS = Regex(
        "^(?:пожалуйста|please|добавь|добавить|добавьте|создай|создать|запиши|записать|напомни|напомнить|" +
            "мне|новая|новую|новое|задачу|задача|дело|таск|напоминание|add|create|new|remind|me|to|task|todo|" +
            "to-do|reminder|что|чтобы|about)(?![\\p{L}\\d])[\\s,:;\\-–—]*",
    )
    private val TRAILING_PREPOSITIONS = Regex("(?:\\s+(?:в|во|на|к|до|и|at|on|by|for|in|and))+$")
    private val LEADING_PREPOSITIONS = Regex("^(?:(?:в|во|на|к|at|on|by|for|in)\\s+)+")

    /**
     * The task title: the text without command words and without the date, time,
     * priority and tag fragments that were found in it.
     */
    fun extractTitle(text: String, today: LocalDate = LocalDate.now()): String {
        val cut = listOfNotNull(
            findDate(text, today)?.range,
            findTime(text)?.range,
            findPriority(text)?.range,
            findTag(text)?.range,
        )
        var title = removeRanges(text, cut)
        // Strip command words from the start, one at a time.
        while (true) {
            val normalized = normalize(title.trimStart())
            val m = COMMAND_WORDS.find(normalized) ?: break
            title = title.trimStart().substring(m.range.last + 1)
        }
        title = title.replace(Regex("\\s+"), " ").trim().trim(',', '.', ':', ';', '-', '–', '—', '!', ' ')
        title = removeByNormalized(title, TRAILING_PREPOSITIONS)
        title = removeByNormalized(title, LEADING_PREPOSITIONS)
        title = title.trim().trim(',', '.', ':', ';', '-', '–', '—', ' ')
        return title.replaceFirstChar { it.uppercaseChar() }
    }

    private fun removeByNormalized(text: String, regex: Regex): String {
        val m = regex.find(normalize(text)) ?: return text
        return text.removeRange(m.range)
    }

    private fun removeRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sb = StringBuilder()
        text.forEachIndexed { i, ch -> if (ranges.none { i in it }) sb.append(ch) else sb.append(' ') }
        return sb.toString()
    }
}
