package com.example.assistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.assistant.data.local.converter.Converters
import com.example.assistant.data.local.dao.NoteDao
import com.example.assistant.data.local.dao.SubtaskDao
import com.example.assistant.data.local.dao.SyncConflictDao
import com.example.assistant.data.local.dao.SyncQueueDao
import com.example.assistant.data.local.dao.TagDao
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.local.entity.NoteEntity
import com.example.assistant.data.local.entity.SubtaskEntity
import com.example.assistant.data.local.entity.SyncConflictEntity
import com.example.assistant.data.local.entity.SyncQueueEntity
import com.example.assistant.data.local.entity.TagEntity
import com.example.assistant.data.local.entity.TaskEntity

/** Local SQLite database. Every schema change bumps [version] and adds a migration below. */
@Database(
    entities = [
        TaskEntity::class,
        SubtaskEntity::class,
        TagEntity::class,
        NoteEntity::class,
        SyncQueueEntity::class,
        SyncConflictEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun subtaskDao(): SubtaskDao
    abstract fun tagDao(): TagDao
    abstract fun noteDao(): NoteDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun syncConflictDao(): SyncConflictDao

    companion object {
        const val NAME = "assistant.db"

        /** Stage 2: calendar sync outbox and conflict log. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_queue` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`action` TEXT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, " +
                        "`payloadJson` TEXT, " +
                        "`retryCount` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_queue_taskId` ON `sync_queue` (`taskId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_conflicts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskId` INTEGER, " +
                        "`googleEventId` TEXT, " +
                        "`message` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }
    }
}
