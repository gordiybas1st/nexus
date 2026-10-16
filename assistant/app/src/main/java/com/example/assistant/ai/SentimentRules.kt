package com.example.assistant.ai

/** Emotional states the assistant reacts to. */
enum class Emotion { TIRED, STRESSED, PROUD, BORED }

/** Keyword rules for detecting the user's mood (RU + EN). */
object SentimentRules {

    private const val B0 = "(?<![\\p{L}\\d])"

    private val rules: List<Pair<Emotion, Regex>> = listOf(
        Emotion.TIRED to Regex(
            "${B0}(?:устал[а-я]*|утомил[а-я]*|нет сил|без сил|выгор[а-я]*|хочу спать|вымотал[а-я]*|" +
                "tired|exhausted|no energy|burn(?:ed|t)? out|sleepy|worn out)",
        ),
        Emotion.STRESSED to Regex(
            "${B0}(?:стресс[а-я]*|паник[а-я]*|не успеваю|завал[а-я]*|слишком много|нервнича[а-я]*|" +
                "stress(?:ed)?|overwhelmed|panic[a-z]*|too much to do|anxious)",
        ),
        Emotion.PROUD to Regex(
            "${B0}(?:я молодец|я сделал[а-я]*|я справил[а-я]*|я закончил[а-я]*|всё сделал[а-я]*|все сделал[а-я]*|" +
                "получилось|done it|i did it|nailed it|i finished|i'm done|im done|finished everything)",
        ),
        Emotion.BORED to Regex("${B0}(?:скучно|скука|нечего делать|заскучал[а-я]*|bored|boring|nothing to do)"),
    )

    /** First matching emotion in [text], or null. */
    fun detect(text: String): Emotion? {
        val t = EntityExtractor.normalize(text)
        return rules.firstOrNull { (_, regex) -> regex.containsMatchIn(t) }?.first
    }
}
