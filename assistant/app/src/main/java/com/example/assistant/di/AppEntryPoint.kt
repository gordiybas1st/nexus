package com.example.assistant.di

import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.data.repository.TaskRepository
import com.example.assistant.notifications.DailyReviewScheduler
import com.example.assistant.notifications.GeofenceManager
import com.example.assistant.notifications.NotificationHelper
import com.example.assistant.notifications.ReminderScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt access for broadcast receivers, workers and the widget (no constructor injection there). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun taskDao(): TaskDao
    fun taskRepository(): TaskRepository
    fun preferences(): UserPreferences
    fun notificationHelper(): NotificationHelper
    fun reminderScheduler(): ReminderScheduler
    fun dailyReviewScheduler(): DailyReviewScheduler
    fun geofenceManager(): GeofenceManager
}
