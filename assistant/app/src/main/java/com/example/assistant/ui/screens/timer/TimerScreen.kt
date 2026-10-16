package com.example.assistant.ui.screens.timer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.assistant.R
import com.example.assistant.data.local.dao.TimeEntryDao
import com.example.assistant.data.prefs.ProductivitySettings
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.data.repository.TaskRepository
import com.example.assistant.domain.model.Task
import com.example.assistant.notifications.NotificationHelper
import com.example.assistant.pomodoro.PomodoroController
import com.example.assistant.pomodoro.PomodoroPhase
import com.example.assistant.pomodoro.PomodoroState
import com.example.assistant.ui.navigation.Routes
import com.example.assistant.ui.theme.AssistantTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class TimerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val controller: PomodoroController,
    taskRepository: TaskRepository,
    preferences: UserPreferences,
    timeEntryDao: TimeEntryDao,
) : ViewModel() {

    val timer: StateFlow<PomodoroState> = controller.state

    /** Open tasks to pick from. */
    val tasks: StateFlow<List<Task>> = taskRepository.observeTasks()
        .map { all -> all.filter { !it.isDone }.sortedWith(compareByDescending<Task> { it.priority.ordinal }.thenBy { it.deadline ?: Long.MAX_VALUE }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<ProductivitySettings> =
        preferences.productivity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductivitySettings())

    /** Focused minutes since midnight. */
    val focusedTodayMs: StateFlow<Long> = timeEntryDao
        .observeTotalSince(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    init {
        // "Start timer" app shortcut: assistant://timer/start
        if (savedStateHandle.get<String>(Routes.ARG_AUTOSTART) == "start" && controller.state.value.phase == PomodoroPhase.IDLE) {
            savedStateHandle[Routes.ARG_AUTOSTART] = ""
            controller.start(null, null)
        }
    }

    private val _selectedTaskId = MutableStateFlow(
        savedStateHandle.get<Long>(Routes.ARG_TASK_ID)?.takeIf { it > 0 } ?: controller.state.value.taskId,
    )
    val selectedTaskId: StateFlow<Long?> = _selectedTaskId.asStateFlow()

    fun select(taskId: Long?) {
        _selectedTaskId.value = taskId
    }

    fun start() {
        val task = tasks.value.firstOrNull { it.id == _selectedTaskId.value }
        controller.start(task?.id, task?.title)
    }

    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun skip() = controller.skip()
    fun stop() = controller.stop()
}

/** Pomodoro screen wired to [TimerViewModel]. */
@Composable
fun TimerRoute(onBack: () -> Unit, viewModel: TimerViewModel = hiltViewModel()) {
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selected by viewModel.selectedTaskId.collectAsStateWithLifecycle()
    val focused by viewModel.focusedTodayMs.collectAsStateWithLifecycle()
    TimerContent(
        timer = timer,
        tasks = tasks,
        selectedTaskId = selected,
        settings = settings,
        focusedTodayMs = focused,
        actions = TimerActions(
            onBack = onBack,
            onSelect = viewModel::select,
            onStart = viewModel::start,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onSkip = viewModel::skip,
            onStop = viewModel::stop,
        ),
    )
}

/** Timer callbacks. */
data class TimerActions(
    val onBack: () -> Unit = {},
    val onSelect: (Long?) -> Unit = {},
    val onStart: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onStop: () -> Unit = {},
)

/** Stateless Pomodoro screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerContent(
    timer: PomodoroState,
    tasks: List<Task>,
    selectedTaskId: Long?,
    settings: ProductivitySettings,
    focusedTodayMs: Long,
    actions: TimerActions,
) {
    val idle = timer.phase == PomodoroPhase.IDLE
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.timer_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(
                    when (timer.phase) {
                        PomodoroPhase.IDLE -> R.string.timer_phase_idle
                        PomodoroPhase.WORK -> R.string.timer_phase_work
                        PomodoroPhase.BREAK -> R.string.timer_phase_break
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            val displayMs = if (idle) settings.pomodoroWorkMinutes * 60_000L else timer.remainingMs
            TimerRing(
                progress = if (idle) 0f else timer.progress,
                text = NotificationHelper.formatClock(displayMs),
                isBreak = timer.phase == PomodoroPhase.BREAK,
            )
            if (!idle && !timer.isRunning) {
                Text(stringResource(R.string.timer_paused), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (idle) {
                TaskPicker(tasks, selectedTaskId, actions.onSelect)
                Text(
                    stringResource(R.string.timer_durations, settings.pomodoroWorkMinutes, settings.pomodoroBreakMinutes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = actions.onStart, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(stringResource(R.string.timer_start), Modifier.padding(start = 8.dp))
                }
            } else {
                timer.taskTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (timer.isRunning) {
                        FilledTonalButton(onClick = actions.onPause) {
                            Icon(Icons.Default.Pause, null)
                            Text(stringResource(R.string.timer_pause), Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Button(onClick = actions.onResume) {
                            Icon(Icons.Default.PlayArrow, null)
                            Text(stringResource(R.string.timer_resume), Modifier.padding(start = 8.dp))
                        }
                    }
                    OutlinedButton(onClick = actions.onSkip) {
                        Icon(Icons.Default.SkipNext, null)
                        Text(stringResource(R.string.timer_skip), Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedButton(onClick = actions.onStop) {
                    Icon(Icons.Default.Stop, null)
                    Text(stringResource(R.string.timer_stop), Modifier.padding(start = 8.dp))
                }
            }

            Text(
                stringResource(R.string.timer_sessions, timer.completedSessions) + " · " +
                    stringResource(R.string.timer_focused_today, focusedTodayMs / 60_000),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimerRing(progress: Float, text: String, isBreak: Boolean) {
    val color = if (isBreak) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * progress, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(text, fontSize = 48.sp, fontWeight = FontWeight.Light)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskPicker(tasks: List<Task>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val noTask = stringResource(R.string.timer_no_task)
    val selectedTitle = tasks.firstOrNull { it.id == selectedId }?.title ?: noTask
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedTitle,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.timer_task)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(noTask) }, onClick = {
                onSelect(null)
                expanded = false
            })
            tasks.forEach { task ->
                DropdownMenuItem(text = { Text(task.title, maxLines = 1) }, onClick = {
                    onSelect(task.id)
                    expanded = false
                })
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TimerPreview() {
    AssistantTheme(dynamicColor = false) {
        TimerContent(
            timer = PomodoroState(PomodoroPhase.WORK, true, 12 * 60_000L, 25 * 60_000L, 1, "Write report", 2),
            tasks = emptyList(),
            selectedTaskId = null,
            settings = ProductivitySettings(),
            focusedTodayMs = 75 * 60_000L,
            actions = TimerActions(),
        )
    }
}
