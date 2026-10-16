package com.example.assistant.pomodoro

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.assistant.data.local.dao.TimeEntryDao
import com.example.assistant.data.local.entity.TimeEntryEntity
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.notifications.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class PomodoroPhase { IDLE, WORK, BREAK }

/** Timer state shown by the UI and the notification. */
data class PomodoroState(
    val phase: PomodoroPhase = PomodoroPhase.IDLE,
    val isRunning: Boolean = false,
    val remainingMs: Long = 0,
    val totalMs: Long = 0,
    val taskId: Long? = null,
    val taskTitle: String? = null,
    val completedSessions: Int = 0,
) {
    /** 0..1 of the current phase elapsed. */
    val progress: Float get() = if (totalMs <= 0) 0f else (1f - remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
}

/**
 * Pomodoro state machine: WORK → (sound + vibration) → BREAK → IDLE.
 *
 * The timer runs in this singleton; [PomodoroService] keeps the process in the foreground and
 * mirrors [state] in its notification. Focused work time is written to `time_entries`
 * (full sessions always, stopped sessions if at least one minute long).
 */
@Singleton
class PomodoroController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferences,
    private val timeEntryDao: TimeEntryDao,
    private val notifications: NotificationHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PomodoroState())
    val state: StateFlow<PomodoroState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var endAt = 0L
    private var workSegmentStart = 0L
    private var workAccumulatedMs = 0L

    /** Starts a work session, optionally for a task. */
    fun start(taskId: Long?, taskTitle: String?) {
        scope.launch {
            val current = _state.value
            if (current.phase == PomodoroPhase.WORK) logWork(current, partial = true)
            val minutes = preferences.productivity.first().pomodoroWorkMinutes
            beginPhase(PomodoroPhase.WORK, minutes, taskId, taskTitle, current.completedSessions)
            startService()
        }
    }

    fun pause() {
        val s = _state.value
        if (!s.isRunning) return
        ticker?.cancel()
        val now = System.currentTimeMillis()
        if (s.phase == PomodoroPhase.WORK) workAccumulatedMs += now - workSegmentStart
        _state.value = s.copy(isRunning = false, remainingMs = (endAt - now).coerceAtLeast(0))
    }

    fun resume() {
        val s = _state.value
        if (s.isRunning || s.phase == PomodoroPhase.IDLE) return
        val now = System.currentTimeMillis()
        endAt = now + s.remainingMs
        if (s.phase == PomodoroPhase.WORK) workSegmentStart = now
        _state.value = s.copy(isRunning = true)
        startTicker()
    }

    /** Ends the current phase now without the alert. */
    fun skip() {
        if (_state.value.phase == PomodoroPhase.IDLE) return
        ticker?.cancel()
        scope.launch { finishPhase(alert = false) }
    }

    /** Stops the timer; a work session of 1+ minute is still logged. */
    fun stop() {
        val s = _state.value
        ticker?.cancel()
        scope.launch {
            if (s.phase == PomodoroPhase.WORK) logWork(s, partial = true)
            _state.value = PomodoroState(completedSessions = s.completedSessions)
        }
    }

    private fun beginPhase(phase: PomodoroPhase, minutes: Int, taskId: Long?, taskTitle: String?, sessions: Int) {
        val total = minutes * 60_000L
        val now = System.currentTimeMillis()
        endAt = now + total
        if (phase == PomodoroPhase.WORK) {
            workAccumulatedMs = 0
            workSegmentStart = now
        }
        _state.value = PomodoroState(phase, true, total, total, taskId, taskTitle, sessions)
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val remaining = endAt - System.currentTimeMillis()
                if (remaining <= 0) {
                    _state.value = _state.value.copy(remainingMs = 0)
                    // Finish outside this job: beginning the next phase restarts the ticker.
                    scope.launch { finishPhase(alert = true) }
                    break
                }
                _state.value = _state.value.copy(remainingMs = remaining)
                delay(minOf(TICK_MS, remaining))
            }
        }
    }

    private suspend fun finishPhase(alert: Boolean) {
        val s = _state.value
        when (s.phase) {
            PomodoroPhase.WORK -> {
                logWork(s, partial = false)
                if (alert) notifications.alertPhaseFinished(workFinished = true)
                val breakMinutes = preferences.productivity.first().pomodoroBreakMinutes
                beginPhase(PomodoroPhase.BREAK, breakMinutes, s.taskId, s.taskTitle, s.completedSessions + 1)
            }
            PomodoroPhase.BREAK -> {
                if (alert) notifications.alertPhaseFinished(workFinished = false)
                _state.value = PomodoroState(taskId = s.taskId, taskTitle = s.taskTitle, completedSessions = s.completedSessions)
            }
            PomodoroPhase.IDLE -> Unit
        }
    }

    private suspend fun logWork(s: PomodoroState, partial: Boolean) {
        val now = System.currentTimeMillis()
        val total = workAccumulatedMs + if (s.isRunning) now - workSegmentStart else 0L
        workAccumulatedMs = 0
        workSegmentStart = now
        if (total <= 0 || (partial && total < MIN_LOGGED_MS)) return
        runCatching {
            timeEntryDao.insert(TimeEntryEntity(taskId = s.taskId, startedAt = now - total, endedAt = now, durationMs = total))
        }
    }

    private fun startService() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, PomodoroService::class.java))
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_LOGGED_MS = 60_000L
    }
}
