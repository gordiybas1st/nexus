package com.example.assistant.ui.screens.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.assistant.R
import com.example.assistant.domain.model.Note
import com.example.assistant.ui.components.ColorSwatchRow
import com.example.assistant.ui.components.NotePalette
import com.example.assistant.ui.components.toColorOrNull
import com.example.assistant.ui.theme.AssistantTheme

/** Text color used on top of pastel note colors (readable in both themes). */
private val OnNoteColor = Color(0xDE000000)

/** Notes grid wired to [NotesViewModel]. */
@Composable
fun NotesRoute(
    onAddNote: () -> Unit,
    onOpenNote: (Long) -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NotesContent(
        state = state,
        onAddNote = onAddNote,
        onOpenNote = { onOpenNote(it.id) },
        onQueryChange = viewModel::setQuery,
        onSearchActiveChange = viewModel::setSearchActive,
        onColorChange = viewModel::setColor,
        onDelete = viewModel::delete,
    )
}

/** Stateless notes grid. Long press opens color / delete options. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesContent(
    state: NotesUiState,
    onAddNote: () -> Unit,
    onOpenNote: (Note) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onColorChange: (Note, String?) -> Unit,
    onDelete: (Note) -> Unit,
) {
    var optionsFor by remember { mutableStateOf<Note?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (state.isSearchActive) {
                        val focusRequester = remember { FocusRequester() }
                        TextField(
                            value = state.query,
                            onValueChange = onQueryChange,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            colors = transparentFieldColors(),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
                    } else {
                        Text(stringResource(R.string.notes_title))
                    }
                },
                actions = {
                    IconButton(onClick = { onSearchActiveChange(!state.isSearchActive) }) {
                        if (state.isSearchActive) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_close_search))
                        } else {
                            Icon(Icons.Default.Search, stringResource(R.string.action_search))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNote) {
                Icon(Icons.Default.Add, stringResource(R.string.add_note))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notes.isEmpty() -> Text(
                    text = stringResource(R.string.notes_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp),
                )
                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 88.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.notes, key = { it.id }) { note ->
                        NoteCard(note, onClick = { onOpenNote(note) }, onLongClick = { optionsFor = note })
                    }
                }
            }
        }
    }

    optionsFor?.let { note ->
        AlertDialog(
            onDismissRequest = { optionsFor = null },
            title = { Text(stringResource(R.string.note_options)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.note_color), style = MaterialTheme.typography.labelLarge)
                    ColorSwatchRow(
                        colors = NotePalette,
                        selected = note.colorHex,
                        onSelect = { hex ->
                            onColorChange(note, hex)
                            optionsFor = null
                        },
                        allowNone = true,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(note)
                        optionsFor = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { optionsFor = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(note: Note, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bg = note.colorHex.toColorOrNull()
    val colors = if (bg != null) {
        CardDefaults.cardColors(containerColor = bg, contentColor = OnNoteColor)
    } else {
        CardDefaults.cardColors()
    }
    Card(
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (note.title.isNotBlank()) {
                Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (note.content.isNotBlank()) {
                Text(note.content, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            if (note.title.isBlank() && note.content.isBlank()) {
                Text(stringResource(R.string.untitled_note), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun transparentFieldColors(textColor: Color = MaterialTheme.colorScheme.onSurface) = TextFieldDefaults.colors(
    focusedTextColor = textColor,
    unfocusedTextColor = textColor,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

/** Full-screen note editor wired to [NoteEditViewModel]. */
@Composable
fun NoteEditRoute(
    onBack: () -> Unit,
    viewModel: NoteEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.closeEvents.collect { onBack() } }
    NoteEditContent(
        state = state,
        onBack = onBack,
        onTitleChange = viewModel::onTitleChange,
        onContentChange = viewModel::onContentChange,
        onColorChange = viewModel::onColorChange,
        onDelete = viewModel::delete,
    )
}

/** Stateless note editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditContent(
    state: NoteEditUiState,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onColorChange: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    var showPalette by rememberSaveable { mutableStateOf(false) }
    val bg = state.colorHex.toColorOrNull()
    val contentColor = if (bg != null) OnNoteColor else MaterialTheme.colorScheme.onSurface

    Scaffold(
        containerColor = bg ?: MaterialTheme.colorScheme.surface,
        contentColor = contentColor,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    navigationIconContentColor = contentColor,
                    actionIconContentColor = contentColor,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showPalette = !showPalette }) {
                        Icon(Icons.Outlined.Palette, stringResource(R.string.note_color))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete))
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            if (showPalette) {
                ColorSwatchRow(
                    colors = NotePalette,
                    selected = state.colorHex,
                    onSelect = onColorChange,
                    allowNone = true,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val fieldColors = transparentFieldColors(textColor = contentColor)
            TextField(
                value = state.title,
                onValueChange = onTitleChange,
                placeholder = { Text(stringResource(R.string.note_title_hint), style = MaterialTheme.typography.headlineSmall) },
                textStyle = MaterialTheme.typography.headlineSmall,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = state.content,
                onValueChange = onContentChange,
                placeholder = { Text(stringResource(R.string.note_content_hint)) },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NotesPreview() {
    AssistantTheme(dynamicColor = false) {
        NotesContent(
            state = NotesUiState(
                notes = listOf(
                    Note(1, "Shopping", "Milk, bread, eggs", "#FDE68A"),
                    Note(2, "Ideas", "Write a blog post about Compose.\nRecord a video.", null),
                    Note(3, "", "Call mom on Sunday", "#BFDBFE"),
                ),
                isLoading = false,
            ),
            onAddNote = {},
            onOpenNote = {},
            onQueryChange = {},
            onSearchActiveChange = {},
            onColorChange = { _, _ -> },
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NoteEditPreview() {
    AssistantTheme(dynamicColor = false) {
        NoteEditContent(
            state = NoteEditUiState(title = "Shopping", content = "Milk\nBread", colorHex = "#BBF7D0"),
            onBack = {},
            onTitleChange = {},
            onContentChange = {},
            onColorChange = {},
            onDelete = {},
        )
    }
}
