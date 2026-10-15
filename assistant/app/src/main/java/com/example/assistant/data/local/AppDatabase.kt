package com.example.assistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.assistant.data.local.converter.Converters
import com.example.assistant.data.local.dao.NoteDao
import com.example.assistant.data.local.dao.SubtaskDao
import com.example.assistant.data.local.dao.TagDao
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.local.entity.NoteEntity
import com.example.assistant.data.local.entity.SubtaskEntity
import com.example.assistant.data.local.entity.TagEntity
import com.example.assistant.data.local.entity.TaskEntity

/** Local SQLite database. Later stages bump [version] and add migrations. */
@Database(
    entities = [TaskEntity::class, SubtaskEntity::class, TagEntity::class, NoteEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun subtaskDao(): SubtaskDao
    abstract fun tagDao(): TagDao
    abstract fun noteDao(): NoteDao

    companion object {
        const val NAME = "assistant.db"
    }
}
