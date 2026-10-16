package com.example.assistant.domain

/** All achievements and the value needed to unlock them. */
enum class AchievementId(val target: Int) {
    FIRST_TASK(1),
    TASKS_10(10),
    TASKS_50(50),
    TASKS_100(100),
    TASKS_500(500),
    STREAK_7(7),
    WEEK_NO_OVERDUE(7),
}

/** Progress of one achievement; [unlockedAt] is set once it was earned (kept even if progress drops). */
data class AchievementProgress(
    val id: AchievementId,
    val current: Int,
    val unlockedAt: Long?,
) {
    val target: Int get() = id.target
    val unlocked: Boolean get() = unlockedAt != null || current >= target
    val fraction: Float get() = if (unlocked) 1f else (current.toFloat() / target).coerceIn(0f, 1f)
}

/** Pure achievement rules (unit-tested). */
object Achievements {

    /** Current value for each achievement from the statistics. */
    fun progress(stats: Stats, unlocked: Map<AchievementId, Long>): List<AchievementProgress> =
        AchievementId.entries.map { id ->
            val current = when (id) {
                AchievementId.STREAK_7 -> stats.streak
                // A clean week only counts once something was actually done.
                AchievementId.WEEK_NO_OVERDUE -> if (stats.done > 0) stats.daysWithoutOverdue else 0
                else -> stats.done
            }
            AchievementProgress(id, current.coerceAtMost(id.target), unlocked[id])
        }

    /** Achievements that are earned now but not stored yet. */
    fun newlyUnlocked(stats: Stats, unlocked: Map<AchievementId, Long>): List<AchievementId> =
        progress(stats, unlocked).filter { it.unlockedAt == null && it.current >= it.target }.map { it.id }
}
