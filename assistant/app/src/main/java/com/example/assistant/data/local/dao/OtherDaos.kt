package com.example.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.assistant.data.local.entity.NoteEntity
import com.example.assistant.data.local.entity.SubtaskEntity
import com.example.assistant.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/** Data access for subtasks. */
@Dao
interface SubtaskDao {
    @Insert
    suspend fun insertAll(subtasks: List<SubtaskEntity>)

    @Query("DELETE FROM subtasks WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)

    @Query("UPDATE subtasks SET isDone = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)
}

/** Data access for tags. */
@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    /** Returns the new row id, or -1 if a tag with that name already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Delete
    suspend fun delete(tag: TagEntity)
}

/** Data access for notes. */
@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("UPDATE notes SET colorHex = :colorHex WHERE id = :id")
    suspend fun setColor(id: Long, colorHex: String?)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
