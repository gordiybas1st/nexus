package com.example.assistant.data.repository

import com.example.assistant.data.local.dao.AchievementDao
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.local.dao.TimeEntryDao
import com.example.assistant.data.local.entity.AchievementEntity
import com.example.assistant.data.local.entity.toDomain
import com.example.assistant.domain.AchievementId
import com.example.assistant.domain.AchievementProgress
import com.example.assistant.domain.Achievements
import com.example.assistant.domain.FocusEntry
import com.example.assistant.domain.Stats
import com.example.assistant.domain.StatsCalculator
import com.example.assistant.notifications.NotificationHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Statistics and achievements on top of tasks and focus time. */
@Singleton
class StatsRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val timeEntryDao: TimeEntryDao,
    private val achievementDao: AchievementDao,
    private val notifications: NotificationHelper,
) {
    /** Live statistics; recomputed when tasks, focus time or achievements change. */
    fun observe(): Flow<Pair<Stats, List<AchievementProgress>>> = combine(
        taskDao.observeAllWithRelations().map { rows -> rows.map { it.toDomain() } },
        timeEntryDao.observeSince(0L),
        achievementDao.observeAll(),
    ) { tasks, entries, unlocked ->
        val zone = ZoneId.systemDefault()
        val stats = StatsCalculator.compute(
            tasks,
            entries.map { FocusEntry(it.taskId, it.startedAt, it.durationMs) },
            LocalDate.now(zone),
            System.currentTimeMillis(),
            zone,
        )
        stats to Achievements.progress(stats, unlockedMap(unlocked))
    }

    /** Stores newly earned achievements and announces each with a notification. */
    suspend fun checkAchievements() {
        val zone = ZoneId.systemDefault()
        val tasks = taskDao.getAllWithRelations().map { it.toDomain() }
        val focus = timeEntryDao.getAll().map { FocusEntry(it.taskId, it.startedAt, it.durationMs) }
        val stats = StatsCalculator.compute(tasks, focus, LocalDate.now(zone), System.currentTimeMillis(), zone)
        val now = System.currentTimeMillis()
        Achievements.newlyUnlocked(stats, unlockedMap(achievementDao.getAll())).forEach { id ->
            if (achievementDao.insert(AchievementEntity(id.name, now)) != -1L) {
                notifications.showAchievement(id)
            }
        }
    }

    private fun unlockedMap(rows: List<AchievementEntity>): Map<AchievementId, Long> =
        rows.mapNotNull { row -> runCatching { AchievementId.valueOf(row.id) }.getOrNull()?.let { it to row.unlockedAt } }.toMap()
}
