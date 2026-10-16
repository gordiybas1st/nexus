package com.example.assistant.di

import android.content.Context
import androidx.room.Room
import com.example.assistant.data.local.AppDatabase
import com.example.assistant.data.local.dao.NoteDao
import com.example.assistant.data.local.dao.SubtaskDao
import com.example.assistant.data.local.dao.SyncConflictDao
import com.example.assistant.data.local.dao.SyncQueueDao
import com.example.assistant.data.local.dao.TagDao
import com.example.assistant.data.local.dao.TaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope that outlives screens — for work that must finish after a ViewModel is cleared. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideSubtaskDao(db: AppDatabase): SubtaskDao = db.subtaskDao()

    @Provides
    fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()

    @Provides
    fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideSyncQueueDao(db: AppDatabase): SyncQueueDao = db.syncQueueDao()

    @Provides
    fun provideSyncConflictDao(db: AppDatabase): SyncConflictDao = db.syncConflictDao()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
