package com.example.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.assistant.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

/** Access to chat history. */
@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY id")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clear()

    /** Keeps only the newest [keep] messages. */
    @Query("DELETE FROM chat_messages WHERE id NOT IN (SELECT id FROM chat_messages ORDER BY id DESC LIMIT :keep)")
    suspend fun trimOld(keep: Int)
}
