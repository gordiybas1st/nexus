package com.example.assistant.data.repository

import com.example.assistant.data.local.dao.ChatDao
import com.example.assistant.data.local.entity.ChatMessageEntity
import com.example.assistant.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Chat history storage (keeps the newest [MAX_MESSAGES] messages). */
@Singleton
class ChatRepository @Inject constructor(
    private val dao: ChatDao,
) {
    fun observeMessages(): Flow<List<ChatMessage>> = dao.observeAll().map { rows ->
        rows.map { ChatMessage(it.id, it.text, it.isUser, it.createdAt, it.taskId) }
    }

    suspend fun add(text: String, isUser: Boolean, taskId: Long? = null) {
        dao.insert(ChatMessageEntity(text = text, isUser = isUser, createdAt = System.currentTimeMillis(), taskId = taskId))
        dao.trimOld(MAX_MESSAGES)
    }

    suspend fun clear() = dao.clear()

    private companion object {
        const val MAX_MESSAGES = 500
    }
}
