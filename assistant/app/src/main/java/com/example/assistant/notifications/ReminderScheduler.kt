package com.example.assistant.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.prefs.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sets and cancels task alarms through [AlarmManager]. Alarms do not survive a reboot,
 * so [BootReceiver] and app start call [rescheduleAll].
 *
 * Exact alarms are used when allowed (`USE_EXACT_ALARM` on Android 13+, or the user-granted
 * `SCHEDULE_EXACT_ALARM` on 12); otherwise inexact alarms that may fire a few minutes late.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: TaskDao,
    private val preferences: UserPreferences,
) {
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    /** (Re)schedules alarms for one task; cancels them if it is done or reminders are off. */
    suspend fun schedule(taskId: Long) {
        cancel(taskId)
        if (!preferences.productivity.first().notificationsEnabled) return
        val task = taskDao.getWithRelations(taskId)?.task ?: return
        val hour = preferredHour()
        ReminderPlanner.plan(task.isDone, task.deadline, task.reminderAt, System.currentTimeMillis(), hour, ZoneId.systemDefault())
            .forEach { setAlarm(taskId, it.kind, it.at) }
    }

    /** Re-creates alarms for every open task (after boot, app update or a settings change). */
    suspend fun rescheduleAll() {
        val enabled = preferences.productivity.first().notificationsEnabled
        val hour = preferredHour()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        taskDao.getActiveWithTimes().forEach { task ->
            cancel(task.id)
            if (enabled) {
                ReminderPlanner.plan(task.isDone, task.deadline, task.reminderAt, now, hour, zone)
                    .forEach { setAlarm(task.id, it.kind, it.at) }
            }
        }
    }

    /** Cancels all alarms of a task. */
    fun cancel(taskId: Long) {
        ReminderKind.entries.forEach { kind ->
            pendingIntent(taskId, kind, PendingIntent.FLAG_NO_CREATE)?.let {
                alarmManager?.cancel(it)
                it.cancel()
            }
        }
    }

    /** "Remind me later" from a notification. */
    fun snooze(taskId: Long, minutes: Int) {
        setAlarm(taskId, ReminderKind.SNOOZE, System.currentTimeMillis() + minutes * 60_000L)
    }

    private suspend fun preferredHour(): Int =
        ReminderPlanner.preferredHour(taskDao.getRecentCompletionTimes(HISTORY_SIZE), ZoneId.systemDefault())

    private fun setAlarm(taskId: Long, kind: ReminderKind, at: Long) {
        val am = alarmManager ?: return
        val pi = pendingIntent(taskId, kind, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    private fun pendingIntent(taskId: Long, kind: ReminderKind, flag: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REMINDER)
            .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(ReminderReceiver.EXTRA_KIND, kind.name)
        return PendingIntent.getBroadcast(context, requestCode(taskId, kind), intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val HISTORY_SIZE = 200

        /** Unique per task and kind; also used as the notification id. */
        fun requestCode(taskId: Long, kind: ReminderKind): Int = (taskId * ReminderKind.entries.size + kind.code).toInt()
    }
}
