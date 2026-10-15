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

    /** Deletes the task being edited (no-op for a new task). */
    fun delete() {
        val id = original?.id ?: return
        viewModelScope.launch {
            repository.deleteTask(id)
            _events.send(TaskEditEvent.Deleted)
        }
    }
}
