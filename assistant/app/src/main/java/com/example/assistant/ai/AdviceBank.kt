package com.example.assistant.ai

import kotlin.random.Random

/** Short productivity tips in Russian and English. */
object AdviceBank {

    private data class Tip(val ru: String, val en: String)

    private val tips = listOf(
        Tip("Съешь лягушку: начни день с самой неприятной задачи — дальше будет легче.", "Eat the frog: start with the task you dread most — the rest of the day gets easier."),
        Tip("Правило двух минут: если дело занимает меньше двух минут, сделай его сразу.", "Two-minute rule: if it takes less than two minutes, do it now."),
        Tip("Работай блоками по 25 минут и отдыхай 5 — это метод Pomodoro.", "Work in 25-minute blocks with 5-minute breaks — the Pomodoro technique."),
        Tip("Выбери три главные задачи на день. Всё остальное — бонус.", "Pick three key tasks for the day. Everything else is a bonus."),
        Tip("Раздели большую задачу на шаги по 15–30 минут — начинать станет проще.", "Split a big task into 15–30 minute steps — starting becomes easier."),
        Tip("Отключи уведомления на время глубокой работы.", "Turn off notifications during deep work."),
        Tip("Матрица Эйзенхауэра: срочное и важное — сейчас, важное — запланируй, срочное — делегируй, остальное — удали.", "Eisenhower matrix: urgent+important now, important later, urgent delegate, the rest delete."),
        Tip("Планируй завтрашний день вечером — утром не придётся решать, с чего начать.", "Plan tomorrow in the evening — you won't waste the morning deciding where to start."),
        Tip("Группируй похожие дела: звонки, письма, покупки — одним блоком.", "Batch similar tasks: calls, emails, errands — in one block."),
        Tip("Сначала сделай, потом сделай хорошо. Черновик лучше пустой страницы.", "Done first, perfect later. A draft beats a blank page."),
        Tip("Пей воду и делай короткие прогулки — мозгу нужен кислород.", "Drink water and take short walks — your brain needs oxygen."),
        Tip("Ставь дедлайны себе сам, даже если их нет: работа заполняет всё отведённое время.", "Set your own deadlines: work expands to fill the time available."),
        Tip("Не держи задачи в голове — записывай всё сюда. Голова для идей, а не для хранения.", "Don't keep tasks in your head — write them down here. Your mind is for ideas, not storage."),
        Tip("Учись говорить «нет» задачам, которые не ведут к твоим целям.", "Learn to say no to tasks that don't move your goals forward."),
        Tip("Самое продуктивное время — первые часы после пробуждения. Береги их для важного.", "Your first hours after waking are the most productive — protect them for what matters."),
        Tip("Раз в неделю устраивай обзор: что сделано, что зависло, что убрать.", "Do a weekly review: what's done, what's stuck, what to drop."),
        Tip("Если задача висит больше недели — разбей её, делегируй или удали.", "If a task has been sitting for over a week — split it, delegate it, or delete it."),
        Tip("Одна задача за раз. Многозадачность съедает до 40% времени.", "One task at a time. Multitasking can eat up to 40% of your time."),
        Tip("Награждай себя за завершённые дела — мозг любит маленькие победы.", "Reward yourself for finished tasks — your brain loves small wins."),
        Tip("Убери телефон в другую комнату на время важной работы.", "Put your phone in another room while doing important work."),
        Tip("Сон — лучший усилитель продуктивности. 7–8 часов окупаются.", "Sleep is the best productivity booster. 7–8 hours pay off."),
        Tip("Используй теги: так легко увидеть, сколько времени уходит на работу, учёбу и дом.", "Use tags to see how much time goes to work, study and home."),
        Tip("Начни с 5 минут. Часто самое сложное — просто начать.", "Start with just 5 minutes. Starting is often the hardest part."),
        Tip("Заканчивай день, записав первый шаг на завтра.", "End the day by writing down tomorrow's first step."),
    )

    val size: Int get() = tips.size

    /** A random tip in the requested language. */
    fun random(lang: Lang, random: Random = Random.Default): String {
        val tip = tips[random.nextInt(tips.size)]
        return if (lang == Lang.RU) tip.ru else tip.en
    }
}
