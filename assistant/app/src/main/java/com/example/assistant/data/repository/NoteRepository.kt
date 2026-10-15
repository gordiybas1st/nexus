package com.example.assistant.data.repository

import com.example.assistant.data.local.dao.NoteDao
import com.example.assistant.data.local.entity.toDomain
import com.example.assistant.data.local.entity.toEntity
import com.example.assistant.domain.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for notes. */
@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
) {
    /** All notes, most recently edited first. */
    fun observeNotes(): Flow<List<Note>> = noteDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun getNote(id: Long): Note? = noteDao.getById(id)?.toDomain()

    /** Inserts a new note and returns its id. */
    suspend fun insert(note: Note): Long = noteDao.insert(note.copy(id = 0).toEntity())

    suspend fun update(note: Note) = noteDao.update(note.toEntity())

    suspend fun setColor(id: Long, colorHex: String?) = noteDao.setColor(id, colorHex)

    suspend fun delete(id: Long) = noteDao.deleteById(id)
}
