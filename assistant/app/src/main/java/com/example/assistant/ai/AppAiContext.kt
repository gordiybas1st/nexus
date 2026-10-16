package com.example.assistant.ai

import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.data.repository.TaskRepository
import com.example.assistant.domain.model.Priority
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import com.example.assistant.domain.model.ThemeMode
import com.example.assistant.sync.SyncScheduler
import com.example.assistant.ui.components.TagPalette
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** [AiContext] backed by the real repositories and settings. */
@Singleton
class AppAiContext @Inject constructor(
    private val taskRepository: TaskRepository,
    private val preferences: UserPreferences,
    private val syncScheduler: SyncScheduler,
) : AiContext {

    override val zone: ZoneId get() = ZoneId.systemDefault()

    override fun today(): LocalDate = LocalDate.now(zone)

    override fun now(): Long = System.currentTimeMillis()

    override suspend fun tasks(): List<Task> = taskRepository.observeTasks().first()

    override suspend fun createTask(title: String, deadline: Long?, priority: Priority, tagName: String?): Task {
        val tag = tagName?.trim()?.takeIf { it.isNotEmpty() }?.let { findOrCreateTag(it) }
        val id = taskRepository.saveTask(Task(title = title, deadline = deadline, priority = priority, tag = tag))
        return taskRepository.getTask(id) ?: Task(id = id, title = title, deadline = deadline, priority = priority, tag = tag)
    }

    override suspend fun clearCompleted(): Int = taskRepository.clearCompleted()

    override suspend fun setDarkTheme(dark: Boolean) {
        preferences.setThemeMode(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
    }

    override suspend fun requestSync(): Boolean {
        if (!preferences.syncSettings.first().calendarEnabled) return false
        syncScheduler.requestFullSync()
        return true
    }

    override fun isTimerAvailable(): Boolean = false

    private suspend fun findOrCreateTag(name: String): Tag? {
        taskRepository.observeTags().first().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val color = TagPalette[Math.floorMod(name.lowercase().hashCode(), TagPalette.size)]
        val id = taskRepository.addTag(name, color)
        return if (id > 0) Tag(id, name, color) else null
    }
}
