package com.example.assistant.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.ai.AppAiContext
import com.example.assistant.ai.BotAction
import com.example.assistant.ai.BotReply
import com.example.assistant.ai.Lang
import com.example.assistant.ai.MiniAI
import com.example.assistant.data.repository.ChatRepository
import com.example.assistant.domain.model.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.random.Random

/** Chat screen state. */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isTyping: Boolean = false,
    val isLoading: Boolean = true,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val ai: MiniAI,
    private val aiContext: AppAiContext,
) : ViewModel() {

    private val typing = MutableStateFlow(false)
    private val mutex = Mutex()

    val state: StateFlow<ChatUiState> = combine(chat.observeMessages(), typing) { messages, isTyping ->
        ChatUiState(messages = messages, isTyping = isTyping, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    /** Stores the user's message, shows "typing…" for 400–800 ms and stores the bot reply. */
    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        viewModelScope.launch {
            mutex.withLock {
                chat.add(message, isUser = true)
                typing.value = true
                try {
                    val started = System.currentTimeMillis()
                    val reply = try {
                        ai.processMessage(message, aiContext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        BotReply(if (Lang.detect(message) == Lang.RU) "Что-то пошло не так 😕 Попробуй ещё раз." else "Something went wrong 😕 Please try again.")
                    }
                    val wait = Random.nextLong(TYPING_MIN_MS, TYPING_MAX_MS + 1) - (System.currentTimeMillis() - started)
                    if (wait > 0) delay(wait)
                    chat.add(reply.text, isUser = false, taskId = (reply.action as? BotAction.OpenTask)?.taskId)
                } finally {
                    typing.value = false
                }
            }
        }
    }

    fun clear() {
        viewModelScope.launch { chat.clear() }
    }

    private companion object {
        const val TYPING_MIN_MS = 400L
        const val TYPING_MAX_MS = 800L
    }
}
