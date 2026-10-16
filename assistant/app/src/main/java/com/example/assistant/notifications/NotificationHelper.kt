package com.example.assistant.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.assistant.MainActivity
import com.example.assistant.R
import com.example.assistant.domain.model.Task
import com.example.assistant.pomodoro.PomodoroPhase
import com.example.assistant.pomodoro.PomodoroState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Notification channels, builders and alerts used across the app. */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager get() = NotificationManagerCompat.from(context)

    /** Creates channels; safe to call on every start. */
    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_REVIEW, context.getString(R.string.channel_review), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_TIMER, context.getString(R.string.channel_timer), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
            NotificationChannel(CHANNEL_TIMER_ALERTS, context.getString(R.string.channel_timer_alerts), NotificationManager.IMPORTANCE_HIGH),
        ).forEach(system::createNotificationChannel)
    }

    /** Reminder for a task with "Done" and "In 1 hour" actions. */
    fun showTaskReminder(task: Task, kind: ReminderKind, notificationId: Int) {
        val text = when (kind) {
            ReminderKind.FOLLOW_UP -> context.getString(R.string.reminder_follow_up)
            else -> task.deadline?.let { context.getString(R.string.reminder_due, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it))) }
                ?: context.getString(R.string.reminder_generic)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openUri(uriForTask(task.id), notificationId))
            .addAction(0, context.getString(R.string.action_mark_done), actionIntent(ReminderReceiver.ACTION_DONE, task.id, notificationId))
            .addAction(0, context.getString(R.string.action_snooze_hour), actionIntent(ReminderReceiver.ACTION_SNOOZE, task.id, notificationId))
            .build()
        notify(notificationId, notification)
    }

    /** Evening summary with Postpone / Keep / Delete actions for today's open tasks. */
    fun showDailyReview(tasks: List<Task>) {
        val lines = tasks.take(5).joinToString("\n") { "• ${it.title}" }
        val more = if (tasks.size > 5) "\n" + context.getString(R.string.review_more, tasks.size - 5) else ""
        val notification = NotificationCompat.Builder(context, CHANNEL_REVIEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.review_title, tasks.size, tasks.size))
            .setContentText(context.getString(R.string.review_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines + more))
            .setAutoCancel(true)
            .setContentIntent(openUri(Uri.parse(DEEP_LINK_HOME), DAILY_REVIEW_ID))
            .addAction(0, context.getString(R.string.review_postpone), actionIntent(ReminderReceiver.ACTION_REVIEW_POSTPONE, 0, DAILY_REVIEW_ID))
            .addAction(0, context.getString(R.string.review_keep), actionIntent(ReminderReceiver.ACTION_REVIEW_KEEP, 0, DAILY_REVIEW_ID))
            .addAction(0, context.getString(R.string.review_delete), actionIntent(ReminderReceiver.ACTION_REVIEW_DELETE, 0, DAILY_REVIEW_ID))
            .build()
        notify(DAILY_REVIEW_ID, notification)
    }

    /** Ongoing notification of the Pomodoro foreground service. */
    fun pomodoroNotification(state: PomodoroState): Notification {
        val title = context.getString(if (state.phase == PomodoroPhase.BREAK) R.string.timer_phase_break else R.string.timer_phase_work)
        val totalSec = (state.totalMs / 1000).toInt().coerceAtLeast(1)
        val leftSec = (state.remainingMs / 1000).toInt()
        val text = buildString {
            append(formatClock(state.remainingMs))
            if (!state.isRunning) append(" · ").append(context.getString(R.string.timer_paused))
            state.taskTitle?.let { append(" · ").append(it) }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(totalSec, totalSec - leftSec, false)
            .setContentIntent(openUri(Uri.parse(DEEP_LINK_TIMER), TIMER_NOTIFICATION_ID))
        if (state.isRunning) {
            builder.addAction(0, context.getString(R.string.timer_pause), serviceIntent(com.example.assistant.pomodoro.PomodoroService.ACTION_PAUSE))
        } else {
            builder.addAction(0, context.getString(R.string.timer_resume), serviceIntent(com.example.assistant.pomodoro.PomodoroService.ACTION_RESUME))
        }
        builder.addAction(0, context.getString(R.string.timer_skip), serviceIntent(com.example.assistant.pomodoro.PomodoroService.ACTION_SKIP))
        builder.addAction(0, context.getString(R.string.timer_stop), serviceIntent(com.example.assistant.pomodoro.PomodoroService.ACTION_STOP))
        return builder.build()
    }

    /** Sound + vibration + a heads-up notification when a Pomodoro phase ends. */
    fun alertPhaseFinished(workFinished: Boolean) {
        runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
        runCatching { vibrate() }
        val notification = NotificationCompat.Builder(context, CHANNEL_TIMER_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (workFinished) R.string.timer_work_done else R.string.timer_break_done))
            .setContentText(context.getString(if (workFinished) R.string.timer_work_done_text else R.string.timer_break_done_text))
            .setAutoCancel(true)
            .setContentIntent(openUri(Uri.parse(DEEP_LINK_TIMER), TIMER_ALERT_ID))
            .build()
        notify(TIMER_ALERT_ID, notification)
    }

    fun cancel(id: Int) = manager.cancel(id)

    @SuppressLint("MissingPermission") // checked by areNotificationsEnabled()
    private fun notify(id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(id, notification) }
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 300, 150, 300)
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun openUri(uri: Uri, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(action: String, taskId: Long, notificationId: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        // Request code must differ per (action, notification) so extras are not reused.
        val requestCode = 31 * notificationId + action.hashCode()
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun serviceIntent(action: String): PendingIntent {
        val intent = Intent(context, com.example.assistant.pomodoro.PomodoroService::class.java).setAction(action)
        return PendingIntent.getService(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_REVIEW = "daily_review"
        const val CHANNEL_TIMER = "pomodoro"
        const val CHANNEL_TIMER_ALERTS = "pomodoro_alerts"

        const val TIMER_NOTIFICATION_ID = 1_000_001
        const val TIMER_ALERT_ID = 1_000_002
        const val DAILY_REVIEW_ID = 1_000_003

        /** Deep links handled by the navigation graph. */
        const val DEEP_LINK_HOME = "assistant://home"
        const val DEEP_LINK_TIMER = "assistant://timer"
        const val DEEP_LINK_NEW_TASK = "assistant://newtask"
        fun uriForTask(id: Long): Uri = Uri.parse("assistant://task/$id")

        /** mm:ss */
        fun formatClock(millis: Long): String {
            val totalSec = ((millis + 999) / 1000).coerceAtLeast(0)
            return "%02d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }
}
