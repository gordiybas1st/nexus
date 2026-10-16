package com.example.assistant

import android.app.Application
import com.example.assistant.data.backup.BackupScheduler
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.notifications.DailyReviewScheduler
import com.example.assistant.notifications.GeofenceManager
import com.example.assistant.notifications.NotificationHelper
import com.example.assistant.notifications.ReminderScheduler
import com.example.assistant.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Application class; bootstraps Hilt, notification channels, reminders and calendar sync. */
@HiltAndroidApp
class AssistantApp : Application() {

    @Inject
    lateinit var preferences: UserPreferences

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    @Inject
    lateinit var dailyReviewScheduler: DailyReviewScheduler

    @Inject
    lateinit var geofenceManager: GeofenceManager

    @Inject
    lateinit var backupScheduler: BackupScheduler

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannels()
        startupScope.launch {
            // Alarms are lost on force-stop and app updates; re-create them on every start.
            runCatching { reminderScheduler.rescheduleAll() }
            runCatching { dailyReviewScheduler.schedule() }
            runCatching { geofenceManager.registerAll() }
            if (preferences.dataSettings.first().driveBackupEnabled) backupScheduler.scheduleWeekly()
        }
        startupScope.launch {
            if (preferences.syncSettings.first().calendarEnabled) {
                syncScheduler.schedulePeriodic()
                syncScheduler.requestSync()
            }
        }
    }
}
