package com.example.assistant.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.assistant.data.local.AppDatabase
import com.example.assistant.data.local.dao.NoteDao
import com.example.assistant.data.local.dao.SubtaskDao
import com.example.assistant.data.local.dao.TagDao
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.local.entity.NoteEntity
import com.example.assistant.data.local.entity.SubtaskEntity
import com.example.assistant.data.local.entity.TagEntity
import com.example.assistant.data.local.entity.TaskEntity
import com.example.assistant.domain.model.Priority
import com.example.assistant.notifications.GeofenceManager
import com.example.assistant.notifications.ReminderScheduler
import com.example.assistant.widget.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Counts of imported items. */
data class ImportResult(val tasks: Int, val notes: Int)

/**
 * JSON export / import of tasks (with subtasks and tags) and notes.
 *
 * Import adds data: tasks and notes become new rows (calendar links are dropped, so a later
 * sync creates fresh events); tasks already present with the same title and creation time are skipped.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val taskDao: TaskDao,
    private val subtaskDao: SubtaskDao,
    private val tagDao: TagDao,
    private val noteDao: NoteDao,
    private val reminders: ReminderScheduler,
    private val geofences: GeofenceManager,
    private val widget: WidgetRefresher,
) {
    suspend fun exportJson(): String {
        val tags = tagDao.getAll().associateBy { it.id }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("tags", JSONArray().apply { tags.values.forEach { put(JSONObject().put("name", it.name).put("color", it.colorHex)) } })
            .put("tasks", JSONArray().apply { taskDao.getAllWithRelations().forEach { put(taskJson(it.task, it.subtasks, it.task.tagId?.let(tags::get))) } })
            .put("notes", JSONArray().apply { noteDao.getAll().forEach { put(noteJson(it)) } })
        return root.toString(2)
    }

    suspend fun importJson(json: String): ImportResult {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "Not a Personal Assistant backup" }

        val result = db.withTransaction {
            val tagIds = mutableMapOf<String, Long>()
            tagDao.getAll().forEach { tagIds[it.name.lowercase()] = it.id }
            fun tagId(name: String?, color: String?): Long? {
                if (name.isNullOrBlank()) return null
                return tagIds[name.lowercase()]
            }
            root.optJSONArray("tags")?.forEachObject { t ->
                val name = t.optString("name").trim()
                if (name.isNotEmpty() && tagIds[name.lowercase()] == null) {
                    val id = tagDao.insert(TagEntity(name = name, colorHex = t.optString("color", "#64748B")))
                    if (id > 0) tagIds[name.lowercase()] = id
                }
            }

            val existing = taskDao.getAllWithRelations().map { it.task.title to it.task.createdAt }.toSet()
            var taskCount = 0
            root.optJSONArray("tasks")?.forEachObject { t ->
                val title = t.optString("title").trim()
                val createdAt = t.optLong("createdAt", System.currentTimeMillis())
                if (title.isEmpty() || (title to createdAt) in existing) return@forEachObject
                val id = taskDao.insert(
                    TaskEntity(
                        title = title,
                        description = t.optStringOrNull("description"),
                        priority = runCatching { Priority.valueOf(t.optString("priority")) }.getOrDefault(Priority.MEDIUM),
                        deadline = t.optLongOrNull("deadline"),
                        reminderAt = t.optLongOrNull("reminderAt"),
                        tagId = tagId(t.optStringOrNull("tag"), null),
                        isDone = t.optBoolean("isDone"),
                        createdAt = createdAt,
                        updatedAt = t.optLong("updatedAt", createdAt),
                        completedAt = t.optLongOrNull("completedAt"),
                        googleEventId = null,
                        syncedAt = null,
                        rrule = t.optStringOrNull("rrule"),
                        syncToCalendar = t.optBoolean("syncToCalendar"),
                        locationLat = t.optDoubleOrNull("locationLat"),
                        locationLng = t.optDoubleOrNull("locationLng"),
                        locationRadius = t.optDoubleOrNull("locationRadius")?.toFloat(),
                    ),
                )
                val subtasks = mutableListOf<SubtaskEntity>()
                t.optJSONArray("subtasks")?.forEachObject { s ->
                    subtasks += SubtaskEntity(taskId = id, title = s.optString("title"), isDone = s.optBoolean("isDone"), sortOrder = subtasks.size)
                }
                if (subtasks.isNotEmpty()) subtaskDao.insertAll(subtasks)
                taskCount++
            }

            var noteCount = 0
            root.optJSONArray("notes")?.forEachObject { n ->
                val title = n.optString("title")
                val content = n.optString("content")
                if (title.isBlank() && content.isBlank()) return@forEachObject
                val createdAt = n.optLong("createdAt", System.currentTimeMillis())
                noteDao.insert(NoteEntity(title = title, content = content, colorHex = n.optStringOrNull("color"), createdAt = createdAt, updatedAt = n.optLong("updatedAt", createdAt)))
                noteCount++
            }
            ImportResult(taskCount, noteCount)
        }
        reminders.rescheduleAll()
        geofences.registerAll()
        widget.refresh()
        return result
    }

    /** Writes [exportJson] to a document picked with the system file picker. */
    suspend fun exportTo(uri: Uri) {
        val json = exportJson()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("Cannot open file")
        }
    }

    suspend fun importFrom(uri: Uri): ImportResult {
        val json = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Cannot open file")
        }
        return importJson(json)
    }

    private fun taskJson(task: TaskEntity, subtasks: List<SubtaskEntity>, tag: TagEntity?) = JSONObject().apply {
        put("title", task.title)
        putOpt("description", task.description)
        put("priority", task.priority.name)
        putOpt("deadline", task.deadline)
        putOpt("reminderAt", task.reminderAt)
        putOpt("tag", tag?.name)
        put("isDone", task.isDone)
        put("createdAt", task.createdAt)
        put("updatedAt", task.updatedAt)
        putOpt("completedAt", task.completedAt)
        putOpt("rrule", task.rrule)
        put("syncToCalendar", task.syncToCalendar)
        putOpt("locationLat", task.locationLat)
        putOpt("locationLng", task.locationLng)
        putOpt("locationRadius", task.locationRadius?.toDouble())
        put(
            "subtasks",
            JSONArray().apply {
                subtasks.sortedBy { it.sortOrder }.forEach { put(JSONObject().put("title", it.title).put("isDone", it.isDone)) }
            },
        )
    }

    private fun noteJson(note: NoteEntity) = JSONObject().apply {
        put("title", note.title)
        put("content", note.content)
        putOpt("color", note.colorHex)
        put("createdAt", note.createdAt)
        put("updatedAt", note.updatedAt)
    }

    private companion object {
        const val FORMAT = "personal-assistant-backup"
        const val VERSION = 1
    }
}

private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(action)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
