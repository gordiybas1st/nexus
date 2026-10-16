package com.example.assistant.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.assistant.di.AppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Handles task alarms and the actions of reminder / daily review notifications. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, AppEntryPoint::class.java)
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_REMINDER -> showReminder(deps, taskId, intent.getStringExtra(EXTRA_KIND))
                    ACTION_DONE -> {
                        deps.notificationHelper().cancel(notificationId)
                        deps.taskRepository().setDone(taskId, true)
                    }
                    ACTION_SNOOZE -> {
                        deps.notificationHelper().cancel(notificationId)
                        deps.reminderScheduler().snooze(taskId, SNOOZE_MINUTES)
                    }
                    ACTION_REVIEW_POSTPONE -> {
                        deps.notificationHelper().cancel(notificationId)
                        postponeOpenTasks(deps)
                    }
                    ACTION_REVIEW_KEEP -> deps.notificationHelper().cancel(notificationId)
                    ACTION_REVIEW_DELETE -> {
                        deps.notificationHelper().cancel(notificationId)
                        openTasksDueToday(deps).forEach { deps.taskRepository().deleteTask(it.id) }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun showReminder(deps: AppEntryPoint, taskId: Long, kindName: String?) {
        val kind = kindName?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() } ?: ReminderKind.EXPLICIT
        val task = deps.taskRepository().getTask(taskId) ?: return
        if (task.isDone) return
        deps.notificationHelper().showTaskReminder(task, kind, ReminderScheduler.requestCode(taskId, kind))
    }

    /** Moves today's and overdue open tasks to tomorrow, keeping their time of day. */
    private suspend fun postponeOpenTasks(deps: AppEntryPoint) {
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.now(zone).plusDays(1)
        openTasksDueToday(deps).forEach { task ->
            val old = task.deadline ?: return@forEach
            val time = Instant.ofEpochMilli(old).atZone(zone).toLocalTime()
            val moved = tomorrow.atTime(time).atZone(zone).toInstant().toEpochMilli()
            val shift = moved - old
            deps.taskRepository().saveTask(task.copy(deadline = moved, reminderAt = task.reminderAt?.plus(shift)))
        }
    }

    private suspend fun openTasksDueToday(deps: AppEntryPoint) =
        deps.taskRepository().getIncompleteDueBefore(
            LocalDate.now(ZoneId.systemDefault()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )

    companion object {
        const val ACTION_REMINDER = "com.example.assistant.REMINDER"
        const val ACTION_DONE = "com.example.assistant.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.example.assistant.REMINDER_SNOOZE"
        const val ACTION_REVIEW_POSTPONE = "com.example.assistant.REVIEW_POSTPONE"
        const val ACTION_REVIEW_KEEP = "com.example.assistant.REVIEW_KEEP"
        const val ACTION_REVIEW_DELETE = "com.example.assistant.REVIEW_DELETE"

        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        private const val SNOOZE_MINUTES = 60
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Restores alarms and the daily review after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, AppEntryPoint::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                deps.reminderScheduler().rescheduleAll()
                deps.dailyReviewScheduler().schedule()
            } finally {
                pending.finish()
            }
        }
    }
}
