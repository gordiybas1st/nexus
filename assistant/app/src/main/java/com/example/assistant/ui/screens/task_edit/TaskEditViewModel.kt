package com.example.assistant.ui.screens.task_edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.data.repository.TaskRepository
import com.example.assistant.domain.model.Priority
import com.example.assistant.domain.model.RepeatOption
import com.example.assistant.domain.model.Subtask
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import com.example.assistant.notifications.GeofenceManager
import com.example.assistant.pomodoro.PomodoroController
import com.example.assistant.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reminder choice in the editor. [offsetMs] is how long before the deadline it fires.
 * AUTO = no explicit reminder (smart reminder on the deadline day + follow-up);
 * CUSTOM = a reminder time that matches none of the presets (kept as is).
 */
enum class ReminderOption(val offsetMs: Long?) {
    AUTO(null),
    AT_DEADLINE(0L),
    MIN_10(10 * 60_000L),
    HOUR_1(60 * 60_000L),
    DAY_1(24 * 60 * 60_000L),
    CUSTOM(null);

    companion object {
        fun from(deadline: Long?, reminderAt: Long?): ReminderOption {
            if (reminderAt == null || deadline == null) return AUTO
            return entries.firstOrNull { it.offsetMs != null && deadline - it.offsetMs == reminderAt } ?: CUSTOM
        }
    }
}

/** Editable subtask row; [key] is a stable UI key, not a database id. */
data class SubtaskDraft(val key: Long, val title: String, val isDone: Boolean)

/** Form state of the task editor. */
data class TaskEditUiState(
    val isNew: Boolean = true,
    val isLoading: Boolean = false,
    val title: String = "",
    val titleError: Boolean = false,
    val description: String = "",
    val priority: Priority = Priority.MEDIUM,
    val deadline: Long? = null,
    val repeat: RepeatOption = RepeatOption.NEVER,
    val customRrule: String = "",
    val tagId: Long? = null,
    val tags: List<Tag> = emptyList(),
    val subtasks: List<SubtaskDraft> = emptyList(),
    val syncToCalendar: Boolean = false,
    val reminder: ReminderOption = ReminderOption.AUTO,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val locationRadius: Float = GeofenceManager.DEFAULT_RADIUS_M,
    val locatingNow: Boolean = false,
    val locationError: Boolean = false,
)

/** One-off results that close the screen. */
sealed interface TaskEditEvent {
    data object Saved : TaskEditEvent
    data object Deleted : TaskEditEvent
}

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TaskRepository,
    private val pomodoro: PomodoroController,
    private val geofences: GeofenceManager,
) : ViewModel() {

    private val taskId: Long = savedStateHandle.get<Long>(Routes.ARG_TASK_ID) ?: -1L
    private var original: Task? = null
    private var nextKey = 1L

    private val _state = MutableStateFlow(TaskEditUiState(isNew = taskId <= 0, isLoading = taskId > 0))
    val state: StateFlow<TaskEditUiState> = _state.asStateFlow()

    private val _events = Channel<TaskEditEvent>(Channel.BUFFERED)
    val events: Flow<TaskEditEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeTags().collect { tags -> _state.update { it.copy(tags = tags) } }
        }
        if (taskId > 0) {
            viewModelScope.launch {
                val task = repository.getTask(taskId)
                original = task
                _state.update { s ->
                    if (task == null) {
                        s.copy(isNew = true, isLoading = false)
                    } else {
                        val repeat = RepeatOption.fromRrule(task.rrule)
                        s.copy(
                            isLoading = false,
                            title = task.title,
                            description = task.description.orEmpty(),
                            priority = task.priority,
                            deadline = task.deadline,
                            repeat = repeat,
                            customRrule = if (repeat == RepeatOption.CUSTOM) task.rrule.orEmpty() else "",
                            tagId = task.tag?.id,
                            subtasks = task.subtasks.map { SubtaskDraft(nextKey++, it.title, it.isDone) },
                            syncToCalendar = task.syncToCalendar,
                            reminder = ReminderOption.from(task.deadline, task.reminderAt),
                            locationLat = task.locationLat,
                            locationLng = task.locationLng,
                            locationRadius = task.locationRadius ?: GeofenceManager.DEFAULT_RADIUS_M,
                        )
                    }
                }
            }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value, titleError = false) }

    fun onDescriptionChange(value: String) = _state.update { it.copy(description = value) }

    fun onPriorityChange(value: Priority) = _state.update { it.copy(priority = value) }

    fun onDeadlineChange(value: Long?) = _state.update { it.copy(deadline = value) }

    fun onRepeatChange(value: RepeatOption) = _state.update { it.copy(repeat = value) }

    fun onCustomRruleChange(value: String) = _state.update { it.copy(customRrule = value) }

    /** Selects a tag, or clears it when [tagId] is null. */
    fun onTagSelect(tagId: Long?) = _state.update { it.copy(tagId = tagId) }

    fun onSyncToggle(value: Boolean) = _state.update { it.copy(syncToCalendar = value) }

    fun onReminderChange(value: ReminderOption) = _state.update { it.copy(reminder = value) }

    /** True when location permission is granted (background is checked separately for geofences). */
    fun hasLocationPermission(): Boolean = geofences.hasForegroundPermission()

    fun hasBackgroundLocationPermission(): Boolean = geofences.hasBackgroundPermission()

    /** Saves the current position as the reminder place. */
    fun useCurrentLocation() {
        _state.update { it.copy(locatingNow = true, locationError = false) }
        viewModelScope.launch {
            val position = geofences.currentLocation()
            _state.update {
                if (position == null) {
                    it.copy(locatingNow = false, locationError = true)
                } else {
                    it.copy(locatingNow = false, locationLat = position.first, locationLng = position.second)
                }
            }
        }
    }

    fun onLocationRadiusChange(value: Float) = _state.update { it.copy(locationRadius = value) }

    fun clearLocation() = _state.update { it.copy(locationLat = null, locationLng = null, locationError = false) }

    /** Starts a Pomodoro session for this (already saved) task. Returns its id, or null for a new task. */
    fun startFocus(): Long? {
        val task = original ?: return null
        pomodoro.start(task.id, _state.value.title.ifBlank { task.title })
        return task.id
    }

    /** Creates a tag and selects it. Blank names are ignored. */
    fun addTag(name: String, colorHex: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.addTag(name, colorHex)
            if (id > 0) _state.update { it.copy(tagId = id) }
        }
    }

    fun addSubtask(title: String) {
        if (title.isBlank()) return
        _state.update { it.copy(subtasks = it.subtasks + SubtaskDraft(nextKey++, title.trim(), false)) }
    }

    fun renameSubtask(key: Long, title: String) = _state.update { s ->
        s.copy(subtasks = s.subtasks.map { if (it.key == key) it.copy(title = title) else it })
    }

    fun toggleSubtask(key: Long) = _state.update { s ->
        s.copy(subtasks = s.subtasks.map { if (it.key == key) it.copy(isDone = !it.isDone) else it })
    }

    fun removeSubtask(key: Long) = _state.update { s -> s.copy(subtasks = s.subtasks.filterNot { it.key == key }) }

    /** Validates and stores the task, then emits [TaskEditEvent.Saved]. */
    fun save() {
        val s = _state.value
        if (s.title.isBlank()) {
            _state.update { it.copy(titleError = true) }
            return
        }
        val rrule = when (s.repeat) {
            RepeatOption.CUSTOM -> s.customRrule.trim().removePrefix("RRULE:").ifBlank { null }
            else -> s.repeat.rrule
        }
        val base = original ?: Task(title = "")
        val task = base.copy(
            title = s.title.trim(),
            description = s.description.trim().ifBlank { null },
            priority = s.priority,
            deadline = s.deadline,
            reminderAt = reminderAt(s),
            locationLat = s.locationLat,
            locationLng = s.locationLng,
            locationRadius = if (s.locationLat != null) s.locationRadius else null,
            rrule = rrule,
            tag = s.tags.firstOrNull { it.id == s.tagId },
            syncToCalendar = s.syncToCalendar,
            subtasks = s.subtasks
                .filter { it.title.isNotBlank() }
                .map { Subtask(title = it.title.trim(), isDone = it.isDone) },
        )
        viewModelScope.launch {
            repository.saveTask(task)
            _events.send(TaskEditEvent.Saved)
        }
    }

    private fun reminderAt(s: TaskEditUiState): Long? {
        val deadline = s.deadline ?: return null
        return when (s.reminder) {
            ReminderOption.AUTO -> null
            // Keep a custom time only while the deadline is unchanged.
            ReminderOption.CUSTOM -> original?.reminderAt?.takeIf { original?.deadline == deadline }
            else -> s.reminder.offsetMs?.let { deadline - it }
        }
    }

    /** Deletes the task being edited (no-op for a new task). */
    fun delete() {
        val id = original?.id ?: return
        viewModelScope.launch {
            repository.deleteTask(id)
            _events.send(TaskEditEvent.Deleted)
        }
    }
}
