package com.example.assistant.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.assistant.R
import com.example.assistant.domain.model.Priority
import com.example.assistant.domain.model.Subtask
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import com.example.assistant.sync.SyncState
import com.example.assistant.ui.components.ColorDot
import com.example.assistant.ui.components.SyncIndicator
import com.example.assistant.ui.components.TaskCard
import com.example.assistant.ui.components.toColorOrNull
import com.example.assistant.ui.theme.AssistantTheme
import com.example.assistant.ui.theme.PriorityColors
import kotlinx.coroutines.launch

/** Home screen wired to [HomeViewModel]. */
@Composable
fun HomeRoute(
    onAddTask: () -> Unit,
    onEditTask: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTimer: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.task_deleted)
    val undoText = stringResource(R.string.action_undo)
    val clearedTemplate = stringResource(R.string.completed_cleared)

    HomeContent(
        state = state,
        snackbarHostState = snackbar,
        onAddTask = onAddTask,
        onTaskClick = { onEditTask(it.id) },
        onToggleDone = viewModel::setDone,
        onSubtaskToggle = viewModel::setSubtaskDone,
        onDelete = { task ->
            viewModel.delete(task)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) viewModel.restore(task)
            }
        },
        onFilterChange = viewModel::setFilter,
        onTagClick = viewModel::toggleTag,
        onQueryChange = viewModel::setQuery,
        onSearchActiveChange = viewModel::setSearchActive,
        onTimerClick = onOpenTimer,
        onSyncClick = {
            if (state.syncStatus.state == SyncState.AUTH_REQUIRED || state.syncStatus.state == SyncState.ERROR) {
                onOpenSettings()
            } else {
                viewModel.syncNow()
            }
        },
        onClearCompleted = {
            viewModel.clearCompleted { count ->
                scope.launch { snackbar.showSnackbar(clearedTemplate.format(count)) }
            }
        },
    )
}

/** Stateless Home screen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    snackbarHostState: SnackbarHostState,
    onAddTask: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onToggleDone: (Task, Boolean) -> Unit,
    onSubtaskToggle: (Task, Subtask, Boolean) -> Unit,
    onDelete: (Task) -> Unit,
    onFilterChange: (TaskFilter) -> Unit,
    onTagClick: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onTimerClick: () -> Unit,
    onSyncClick: () -> Unit,
    onClearCompleted: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (state.isSearchActive) {
                        SearchField(state.query, onQueryChange)
                    } else {
                        Text(stringResource(R.string.tasks_title))
                    }
                },
                actions = {
                    if (state.isSearchActive) {
                        IconButton(onClick = { onSearchActiveChange(false) }) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_close_search))
                        }
                    } else {
                        SyncIndicator(state.syncStatus, onSyncClick)
                        IconButton(onClick = onTimerClick) {
                            Icon(Icons.Outlined.Timer, stringResource(R.string.timer_title))
                        }
                        IconButton(onClick = { onSearchActiveChange(true) }) {
                            Icon(Icons.Default.Search, stringResource(R.string.action_search))
                        }
                        IconButton(onClick = onClearCompleted) {
                            Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.action_clear_completed))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTask) {
                Icon(Icons.Default.Add, stringResource(R.string.add_task))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "filters") {
                        FilterRow(state, onFilterChange, onTagClick)
                    }
                    if (state.tasks.isEmpty()) {
                        item(key = "empty") { EmptyTasks() }
                    }
                    items(state.tasks, key = { it.id }) { task ->
                        SwipeableTaskCard(
                            task = task,
                            onClick = { onTaskClick(task) },
                            onToggleDone = { onToggleDone(task, it) },
                            onSubtaskToggle = { sub, done -> onSubtaskToggle(task, sub, done) },
                            onDelete = { onDelete(task) },
                            modifier = Modifier.animateItemPlacement(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    state: HomeUiState,
    onFilterChange: (TaskFilter) -> Unit,
    onTagClick: (Long) -> Unit,
) {
    val labels = mapOf(
        TaskFilter.ALL to stringResource(R.string.filter_all),
        TaskFilter.ACTIVE to stringResource(R.string.filter_active),
        TaskFilter.DONE to stringResource(R.string.filter_done),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TaskFilter.entries, key = { "f_${it.name}" }) { filter ->
            FilterChip(
                selected = state.filter == filter,
                onClick = { onFilterChange(filter) },
                label = { Text(labels.getValue(filter)) },
            )
        }
        items(state.tags, key = { "t_${it.id}" }) { tag ->
            FilterChip(
                selected = state.selectedTagId == tag.id,
                onClick = { onTagClick(tag.id) },
                label = { Text(tag.name) },
                leadingIcon = {
                    ColorDot(tag.colorHex.toColorOrNull() ?: MaterialTheme.colorScheme.secondary)
                },
            )
        }
    }
}

/** Swipe right toggles done, swipe left deletes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskCard(
    task: Task,
    onClick: () -> Unit,
    onToggleDone: (Boolean) -> Unit,
    onSubtaskToggle: (Subtask, Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onToggleDone(!task.isDone)
                    false // snap back; the list re-sorts by itself
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> PriorityColors.Low
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                },
                label = "swipeBackground",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Icon(Icons.Outlined.TaskAlt, null, tint = Color.White)
                    SwipeToDismissBoxValue.EndToStart -> Icon(Icons.Outlined.Delete, null, tint = Color.White)
                    SwipeToDismissBoxValue.Settled -> Unit
                }
            }
        },
    ) {
        TaskCard(task = task, onToggleDone = onToggleDone, onClick = onClick, onSubtaskToggle = onSubtaskToggle)
    }
}

@Composable
private fun EmptyTasks() {
    Text(
        text = stringResource(R.string.tasks_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp),
    )
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    val work = Tag(1, "work", "#3B82F6")
    AssistantTheme(dynamicColor = false) {
        HomeContent(
            state = HomeUiState(
                tasks = listOf(
                    Task(id = 1, title = "Prepare presentation", priority = Priority.HIGH, deadline = System.currentTimeMillis() + 7_200_000, tag = work),
                    Task(id = 2, title = "Buy groceries", priority = Priority.LOW),
                    Task(id = 3, title = "Call the bank", isDone = true),
                ),
                tags = listOf(work),
                isLoading = false,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onAddTask = {},
            onTaskClick = {},
            onToggleDone = { _, _ -> },
            onSubtaskToggle = { _, _, _ -> },
            onDelete = {},
            onFilterChange = {},
            onTagClick = {},
            onQueryChange = {},
            onSearchActiveChange = {},
            onTimerClick = {},
            onSyncClick = {},
            onClearCompleted = {},
        )
    }
}
