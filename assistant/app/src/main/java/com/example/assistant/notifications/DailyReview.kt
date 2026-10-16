package com.example.assistant.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.assistant.data.local.entity.toDomain
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.di.AppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules [DailyReviewWorker] for the next review time (21:00 by default). */
@Singleton
class DailyReviewScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferences,
) {
    /**
     * Schedules the next run, or cancels it when the review is disabled.
     *
     * @param replace true to override an already scheduled run (settings change, app start);
     * the worker itself appends the next day's run.
     */
    suspend fun schedule(replace: Boolean = true) {
        val settings = preferences.productivity.first()
        val wm = WorkManager.getInstance(context)
        if (!settings.dailyReviewEnabled || !settings.notificationsEnabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = delayUntil(settings.dailyReviewHour, settings.dailyReviewMinute, LocalDateTime.now())
        val request = OneTimeWorkRequestBuilder<DailyReviewWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(
            WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    companion object {
        private const val WORK_NAME = "daily_review"

        /** Time until the next [hour]:[minute] after [now] (tomorrow if already passed). */
        fun delayUntil(hour: Int, minute: Int, now: LocalDateTime): Duration {
            var next = now.toLocalDate().atTime(hour, minute)
            if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
            return Duration.between(now, next)
        }
    }
}

/** Evening review: lists today's and overdue open tasks and schedules the next run. */
class DailyReviewWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, AppEntryPoint::class.java)
        val zone = ZoneId.systemDefault()
        val endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val open = deps.taskDao().getIncompleteDueBefore(endOfToday).map { it.toDomain() }
        if (open.isNotEmpty() && deps.preferences().productivity.first().notificationsEnabled) {
            deps.notificationHelper().showDailyReview(open)
        }
        deps.dailyReviewScheduler().schedule(replace = false)
        return Result.success()
    }
}
