package com.example.assistant.pomodoro

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.assistant.notifications.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the Pomodoro timer alive while the app is in the background.
 * Uses the `specialUse` type (Android 14): a countdown timer has no dedicated FGS type.
 * Stops itself when the timer becomes idle.
 */
@AndroidEntryPoint
class PomodoroService : Service() {

    @Inject
    lateinit var controller: PomodoroController

    @Inject
    lateinit var notifications: NotificationHelper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var lastShownSecond = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must enter the foreground quickly after startForegroundService().
        goForeground()
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_SKIP -> controller.skip()
            ACTION_STOP -> controller.stop()
        }
        if (!observing) {
            observing = true
            scope.launch {
                controller.state.collect { state ->
                    if (state.phase == PomodoroPhase.IDLE) {
                        ServiceCompat.stopForeground(this@PomodoroService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val second = state.remainingMs / 1000
                        if (second != lastShownSecond || !state.isRunning) {
                            lastShownSecond = second
                            runCatching {
                                NotificationManagerCompat.from(this@PomodoroService)
                                    .notify(NotificationHelper.TIMER_NOTIFICATION_ID, notifications.pomodoroNotification(state))
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NotificationHelper.TIMER_NOTIFICATION_ID,
            notifications.pomodoroNotification(controller.state.value),
            type,
        )
    }

    companion object {
        const val ACTION_PAUSE = "com.example.assistant.timer.PAUSE"
        const val ACTION_RESUME = "com.example.assistant.timer.RESUME"
        const val ACTION_SKIP = "com.example.assistant.timer.SKIP"
        const val ACTION_STOP = "com.example.assistant.timer.STOP"
    }
}
