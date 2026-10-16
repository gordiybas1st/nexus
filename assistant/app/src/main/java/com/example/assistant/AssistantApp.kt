package com.example.assistant

import android.app.Application
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Application class; bootstraps Hilt and keeps calendar sync scheduled. */
@HiltAndroidApp
class AssistantApp : Application() {

    @Inject
    lateinit var preferences: UserPreferences

    @Inject
    lateinit var syncScheduler: SyncScheduler

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startupScope.launch {
            if (preferences.syncSettings.first().calendarEnabled) {
                syncScheduler.schedulePeriodic()
                syncScheduler.requestSync()
            }
        }
    }
}
