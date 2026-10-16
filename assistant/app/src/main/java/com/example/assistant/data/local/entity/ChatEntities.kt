package com.example.assistant.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One chat bubble.
 *
 * @property taskId task the bot reply refers to (shows an "Open" button), if any.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val isUser: Boolean,
    val createdAt: Long,
    val taskId: Long?,
)
