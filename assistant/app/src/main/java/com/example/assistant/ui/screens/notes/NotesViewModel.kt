package com.example.assistant.ui.screens.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.assistant.data.repository.NoteRepository
import com.example.assistant.di.ApplicationScope
import com.example.assistant.domain.model.Note
import com.example.assistant.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** State of the notes grid. */
data class NotesUiState(
    val notes: List<Note> = emptyList(),
    val query: String = "",
    val isSearchActive: Boolean = false,
    val isLoading: Boolean = true,
)

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NoteRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val searchActive = MutableStateFlow(false)

    val uiState: StateFlow<NotesUiState> = combine(repository.observeNotes(), query, searchActive) { notes, q, active ->
        val term = q.trim()
        NotesUiState(
            notes = if (term.isEmpty()) {
                notes
            } else {
                notes.filter { it.title.contains(term, ignoreCase = true) || it.content.contains(term, ignoreCase = true) }
            },
            query = q,
            isSearchActive = active,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSearchActive(active: Boolean) {
        searchActive.value = active
        if (!active) query.value = ""
    }

    fun setColor(note: Note, colorHex: String?) {
        viewModelScope.launch { repository.setColor(note.id, colorHex) }
    }

    fun delete(note: Note) {
        viewModelScope.launch { repository.delete(note.id) }
    }
}

/** State of the note editor. */
data class NoteEditUiState(
    val title: String = "",
    val content: String = "",
    val colorHex: String? = null,
    val isLoading: Boolean = false,
)

/**
 * Note editor with autosave: changes are written 500 ms after the user stops typing,
 * and once more when the screen closes. Empty new notes are never stored; a note
 * emptied by the user is deleted on close.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class NoteEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NoteRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private var noteId: Long = (savedStateHandle.get<Long>(Routes.ARG_NOTE_ID) ?: -1L).coerceAtLeast(0L)
    private var createdAt = 0L
    private var lastSaved: Triple<String, String, String?>? = null
    private var deleted = false
    private val mutex = Mutex()

    private val _state = MutableStateFlow(NoteEditUiState(isLoading = noteId != 0L))
    val state: StateFlow<NoteEditUiState> = _state.asStateFlow()

    private val _close = Channel<Unit>(Channel.BUFFERED)

    /** Emits when the note was deleted and the screen should close. */
    val closeEvents: Flow<Unit> = _close.receiveAsFlow()

    init {
        viewModelScope.launch {
            if (noteId != 0L) {
                val note = repository.getNote(noteId)
                if (note != null) {
                    createdAt = note.createdAt
                    lastSaved = Triple(note.title, note.content, note.colorHex)
                    _state.value = NoteEditUiState(note.title, note.content, note.colorHex, isLoading = false)
                } else {
                    noteId = 0L
                    _state.update { it.copy(isLoading = false) }
                }
            }
            _state
                .map { Triple(it.title, it.content, it.colorHex) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(500)
                .collect { persist(final = false) }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value) }

    fun onContentChange(value: String) = _state.update { it.copy(content = value) }

    fun onColorChange(value: String?) = _state.update { it.copy(colorHex = value) }

    fun delete() {
        viewModelScope.launch {
            mutex.withLock {
                deleted = true
                if (noteId != 0L) repository.delete(noteId)
            }
            _close.send(Unit)
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here; finish the last save in the app scope.
        appScope.launch { persist(final = true) }
    }

    private suspend fun persist(final: Boolean) = mutex.withLock {
        val s = _state.value
        if (deleted || s.isLoading) return@withLock
        val snapshot = Triple(s.title, s.content, s.colorHex)
        val blank = s.title.isBlank() && s.content.isBlank()
        when {
            noteId == 0L && blank -> Unit
            noteId != 0L && blank && final -> repository.delete(noteId)
            snapshot == lastSaved -> Unit
            noteId == 0L -> {
                val now = System.currentTimeMillis()
                createdAt = now
                noteId = repository.insert(Note(title = s.title, content = s.content, colorHex = s.colorHex, createdAt = now, updatedAt = now))
                lastSaved = snapshot
            }
            else -> {
                repository.update(Note(noteId, s.title, s.content, s.colorHex, createdAt, System.currentTimeMillis()))
                lastSaved = snapshot
            }
        }
    }
}
