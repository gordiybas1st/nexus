package com.example.assistant.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.assistant.R
import com.example.assistant.domain.model.ChatMessage
import com.example.assistant.ui.components.DateText
import com.example.assistant.ui.theme.AssistantTheme
import com.example.assistant.voice.VoiceInputManager
import kotlinx.coroutines.launch

/** Assistant chat wired to [ChatViewModel], with voice input. */
@Composable
fun ChatRoute(
    onOpenTask: (Long) -> Unit,
    onOpenTimer: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) { viewModel.openTimer.collect { onOpenTimer() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val voice = remember { VoiceInputManager(context.applicationContext) }
    DisposableEffect(voice) { onDispose { voice.destroy() } }
    val listening by voice.listening.collectAsStateWithLifecycle()
    val level by voice.level.collectAsStateWithLifecycle()

    val unavailableText = stringResource(R.string.voice_unavailable)
    val deniedText = stringResource(R.string.voice_permission_denied)
    val errorText = stringResource(R.string.voice_error)
    fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun startVoice() {
        if (!voice.isAvailable()) {
            showMessage(unavailableText)
            return
        }
        voice.start(
            onText = viewModel::send,
            onFailure = { code ->
                val silent = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (!silent) showMessage(errorText)
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else showMessage(deniedText)
    }

    ChatContent(
        state = state,
        listening = listening,
        micLevel = level,
        snackbarHostState = snackbar,
        onSend = viewModel::send,
        onMicClick = {
            when {
                listening -> voice.stop()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startVoice()
                else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onOpenTask = onOpenTask,
        onClear = viewModel::clear,
    )
}

/** Stateless chat screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatUiState,
    listening: Boolean,
    micLevel: Float,
    snackbarHostState: SnackbarHostState,
    onSend: (String) -> Unit,
    onMicClick: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onClear: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val send = {
        if (input.isNotBlank()) {
            onSend(input)
            input = ""
        }
    }

    // Keep the newest message (or the typing indicator) in view.
    val itemCount = state.messages.size + if (state.isTyping) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_title)) },
                actions = {
                    if (state.messages.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.chat_clear))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.messages.isEmpty() && !state.isTyping) {
                    Text(
                        text = stringResource(R.string.chat_welcome),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message, onOpenTask)
                    }
                    if (state.isTyping) {
                        item(key = "typing") { TypingIndicator() }
                    }
                }
            }
            SuggestionRow(onPick = onSend)
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MicButton(listening, micLevel, onMicClick)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(if (listening) R.string.chat_listening else R.string.chat_input_hint)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = send, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.chat_clear_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.chat_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onOpenTask: (Long) -> Unit) {
    val mine = message.isUser
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 18.dp,
            ),
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                DateText.time(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
            message.taskId?.let { id ->
                TextButton(onClick = { onOpenTask(id) }) { Text(stringResource(R.string.chat_open_task)) }
            }
        }
    }
}

/** Three dots fading in turn. */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { index ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(durationMillis = 500, delayMillis = index * 160), RepeatMode.Reverse),
                        label = "dot$index",
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(8.dp).alpha(alpha),
                    ) {}
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.chat_typing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionRow(onPick: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(R.string.chip_today),
        stringResource(R.string.chip_tip),
        stringResource(R.string.chip_tired),
        stringResource(R.string.chip_what_next),
        stringResource(R.string.chip_help),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(suggestions) { text ->
            SuggestionChip(onClick = { onPick(text) }, label = { Text(text) })
        }
    }
}

/** Mic button that pulses with the input level while listening. */
@Composable
private fun MicButton(listening: Boolean, level: Float, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "micPulse",
    )
    val scale = if (listening) pulse + level * 0.2f else 1f
    IconButton(onClick = onClick, modifier = Modifier.scale(scale)) {
        if (listening) {
            Icon(Icons.Default.Stop, stringResource(R.string.chat_mic_stop), tint = MaterialTheme.colorScheme.error)
        } else {
            Icon(Icons.Default.Mic, stringResource(R.string.chat_mic))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatPreview() {
    val now = System.currentTimeMillis()
    AssistantTheme(dynamicColor = false) {
        ChatContent(
            state = ChatUiState(
                messages = listOf(
                    ChatMessage(1, "Добавь задачу купить хлеб завтра в 10", true, now),
                    ChatMessage(2, "✅ Задача «Купить хлеб» добавлена (срок: завтра 10:00)", false, now, taskId = 1),
                    ChatMessage(3, "Что на сегодня?", true, now),
                ),
                isTyping = true,
                isLoading = false,
            ),
            listening = false,
            micLevel = 0f,
            snackbarHostState = remember { SnackbarHostState() },
            onSend = {},
            onMicClick = {},
            onOpenTask = {},
            onClear = {},
        )
    }
}
