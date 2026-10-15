package com.example.assistant.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.data.repository.TaskRepository
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Status filter for the task list. */
enum class TaskFilter { ALL, ACTIVE, DONE }

/** Everything the Home screen renders. */
data class HomeUiState(
    val tasks: List<Task> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val filter: TaskFilter = TaskFilter.ALL,
    val selectedTagId: Long? = null,
    val query: String = "",
    val isSearchActive: Boolean = false,
    val isLoading: Boolean = true,
)

private data class Filters(
    val filter: TaskFilter,
    val tagId: Long?,
    val query: String,
    val searchActive: Boolean,
)

/** Undone first, then by nearest deadline (none last), then by priority. */
private val taskOrder: Comparator<Task> = compareBy<Task> { it.isDone }
    .thenBy { it.deadline ?: Long.MAX_VALUE }
    .thenByDescending { it.priority.ordinal }
    .thenByDescending { it.createdAt }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TaskRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(Filters(TaskFilter.ALL, null, "", false))

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeTasks(),
        repository.observeTags(),
        filters,
    ) { tasks, tags, f ->
        val q = f.query.trim()
        val visible = tasks
            .asSequence()
            .filter {
                when (f.filter) {
                    TaskFilter.ALL -> true
                    TaskFilter.ACTIVE -> !it.isDone
                    TaskFilter.DONE -> it.isDone
                }
            }
            .filter { f.tagId == null || it.tag?.id == f.tagId }
            .filter {
                q.isEmpty() ||
                    it.title.contains(q, ignoreCase = true) ||
                    it.description?.contains(q, ignoreCase = true) == true
            }
            .sortedWith(taskOrder)
            .toList()
        HomeUiState(
            tasks = visible,
            tags = tags,
            filter = f.filter,
            // Drop a selection that points to a deleted tag.
            selectedTagId = f.tagId?.takeIf { id -> tags.any { it.id == id } },
            query = f.query,
            isSearchActive = f.searchActive,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setFilter(filter: TaskFilter) {
        filters.value = filters.value.copy(filter = filter)
    }

    /** Selects a tag, or clears the selection when the same tag is tapped again. */
    fun toggleTag(tagId: Long) {
        val current = filters.value
        filters.value = current.copy(tagId = if (current.tagId == tagId) null else tagId)
    }

    fun setQuery(query: String) {
        filters.value = filters.value.copy(query = query)
    }

    fun setSearchActive(active: Boolean) {
        filters.value = filters.value.copy(searchActive = active, query = if (active) filters.value.query else "")
    }

    fun setDone(task: Task, done: Boolean) {
        viewModelScope.launch { repository.setDone(task.id, done) }
    }

    fun delete(task: Task) {
        viewModelScope.launch { repository.deleteTask(task.id) }
    }

    fun restore(task: Task) {
        viewModelScope.launch { runCatching { repository.restoreTask(task) } }
    }

    /** Deletes completed tasks and reports how many were removed. */
    fun clearCompleted(onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(repository.clearCompleted()) }
    }
}
