package com.example.assistant.sync

import com.example.assistant.data.local.dao.SyncConflictDao
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.data.local.entity.SyncAction
import com.example.assistant.data.local.entity.SyncConflictEntity
import com.example.assistant.data.local.entity.toDomain
import com.example.assistant.data.remote.AuthRequiredException
import com.example.assistant.data.remote.CalendarApiException
import com.example.assistant.data.remote.CalendarApiService
import com.example.assistant.data.remote.GoogleAuthManager
import com.example.assistant.domain.model.Task
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** An event read back from Google Calendar. */
data class CalendarEvent(
    val id: String,
    val summary: String,
    val start: Long?,
    val end: Long?,
    val updated: Long?,
    /** Local task id when the event was created by this app. */
    val taskId: Long?,
)

/**
 * Maps tasks to Google Calendar events and talks to the API.
 *
 * All calls go through [withToken], which retries once with a fresh token on HTTP 401
 * and throws [AuthRequiredException] if that fails too.
 */
@Singleton
class CalendarSyncManager @Inject constructor(
    private val auth: GoogleAuthManager,
    private val api: CalendarApiService,
    private val taskDao: TaskDao,
    private val conflictDao: SyncConflictDao,
    private val queueWriter: SyncQueueWriter,
) {
    /** Creates an event for [task]; returns the new event id. */
    suspend fun createEvent(task: Task): String? =
        withToken { api.insertEvent(it, task.toEventJson()) }.optString("id").ifBlank { null }

    /** Overwrites the event linked to [task] (local wins). No-op without an event id. */
    suspend fun updateEvent(task: Task) {
        val eventId = task.googleEventId ?: return
        withToken { api.updateEvent(it, eventId, task.toEventJson()) }
    }

    suspend fun deleteEvent(eventId: String) {
        withToken { api.deleteEvent(it, eventId) }
    }

    /** Events in [[from], [to]] (epoch millis) from the primary calendar. */
    suspend fun fetchEvents(from: Long, to: Long): List<CalendarEvent> =
        listAll(timeMin = rfc3339(from), timeMax = rfc3339(to), privateProperty = null).map { it.toCalendarEvent() }

    /**
     * Reconciles the app's events with local tasks (local always wins) and fills the outbox:
     * - task to sync without event → CREATE
     * - task whose event was deleted in Calendar → log conflict, CREATE again
     * - task changed locally, or event edited in Calendar → UPDATE (edit is logged as conflict)
     * - app event without a local task → log, DELETE
     *
     * The caller then runs [SyncQueueProcessor.processAll].
     */
    suspend fun fullSync() {
        val remote = listAll(timeMin = null, timeMax = null, privateProperty = "$PROP_SOURCE=$PROP_SOURCE_VALUE")
            .associateBy { it.optString("id") }
            .filterKeys { it.isNotBlank() }
        val linkedEventIds = taskDao.getAllWithEvents().mapNotNull { it.googleEventId }.toSet()

        remote.forEach { (eventId, event) ->
            if (eventId !in linkedEventIds) {
                val taskId = event.privateTaskId()
                logConflict(taskId, eventId, "Calendar event has no local task; removing it (local wins)")
                queueWriter.enqueue(SyncAction.DELETE, taskId ?: 0L, eventId, schedule = false)
            }
        }

        taskDao.getSyncCandidates().map { it.toDomain() }.forEach { task ->
            val eventId = task.googleEventId
            val event = eventId?.let { remote[it] }
            when {
                eventId == null -> queueWriter.enqueue(SyncAction.CREATE, task.id, schedule = false)
                event == null -> {
                    logConflict(task.id, eventId, "Event was deleted in Calendar; recreating it (local wins)")
                    taskDao.setSyncInfo(task.id, null, null)
                    queueWriter.enqueue(SyncAction.CREATE, task.id, schedule = false)
                }
                else -> {
                    val syncedAt = task.syncedAt
                    val remoteUpdated = event.optString("updated").toEpochMillisOrNull()
                    val changedLocally = syncedAt == null || task.updatedAt > syncedAt
                    val changedRemotely = syncedAt != null && remoteUpdated != null &&
                        remoteUpdated > syncedAt + CLOCK_TOLERANCE_MS
                    if (changedRemotely) {
                        logConflict(task.id, eventId, "Event was edited in Calendar; local version kept")
                    }
                    if (changedLocally || changedRemotely) {
                        queueWriter.enqueue(SyncAction.UPDATE, task.id, schedule = false)
                    }
                }
            }
        }
        conflictDao.trimOld(keep = 100)
    }

    private suspend fun listAll(timeMin: String?, timeMax: String?, privateProperty: String?): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var pageToken: String? = null
        do {
            val page = withToken { api.listEvents(it, timeMin, timeMax, privateProperty, pageToken) }
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.optJSONObject(i)?.let(result::add)
            pageToken = page.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        return result
    }

    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = auth.getValidAccessToken()
        return try {
            block(token)
        } catch (e: CalendarApiException) {
            if (e.code != 401) throw e
            auth.invalidateToken(token)
            val fresh = auth.getValidAccessToken()
            try {
                block(fresh)
            } catch (retry: CalendarApiException) {
                if (retry.code == 401) throw AuthRequiredException() else throw retry
            }
        }
    }

    private suspend fun logConflict(taskId: Long?, eventId: String?, message: String) {
        conflictDao.insert(SyncConflictEntity(taskId = taskId, googleEventId = eventId, message = message, createdAt = System.currentTimeMillis()))
    }

    companion object {
        /** Private extended property marking events created by this app. */
        const val PROP_SOURCE = "source"
        const val PROP_SOURCE_VALUE = "personal_assistant"
        const val PROP_TASK_ID = "taskId"

        private const val HOUR_MS = 3_600_000L
        private const val CLOCK_TOLERANCE_MS = 60_000L

        /** Builds the Calendar event body for a task. */
        fun Task.toEventJson(zone: ZoneId = ZoneId.systemDefault()): JSONObject {
            val start = Instant.ofEpochMilli(deadline ?: (createdAt + HOUR_MS)).atZone(zone)
            val end = start.plusHours(1)
            val text = buildString {
                description?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (subtasks.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    subtasks.forEach { append(if (it.isDone) "- [x] " else "- [ ] ").append(it.title).append('\n') }
                }
            }.trimEnd()

            return JSONObject().apply {
                put("summary", if (isDone) "✅ $title" else title)
                put("description", text)
                put("start", JSONObject().put("dateTime", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).put("timeZone", zone.id))
                put("end", JSONObject().put("dateTime", end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).put("timeZone", zone.id))
                put(
                    "reminders",
                    JSONObject()
                        .put("useDefault", false)
                        .put("overrides", JSONArray().put(JSONObject().put("method", "popup").put("minutes", 10))),
                )
                if (!rrule.isNullOrBlank()) put("recurrence", JSONArray().put("RRULE:${rrule.removePrefix("RRULE:")}"))
                put(
                    "extendedProperties",
                    JSONObject().put(
                        "private",
                        JSONObject().put(PROP_SOURCE, PROP_SOURCE_VALUE).put(PROP_TASK_ID, id.toString()),
                    ),
                )
            }
        }

        private fun rfc3339(millis: Long): String = Instant.ofEpochMilli(millis).toString()

        private fun String.toEpochMillisOrNull(): Long? =
            takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        private fun JSONObject.privateTaskId(): Long? =
            optJSONObject("extendedProperties")?.optJSONObject("private")?.optString(PROP_TASK_ID)?.toLongOrNull()

        private fun JSONObject.timeOf(key: String): Long? {
            val obj = optJSONObject(key) ?: return null
            val dateTime = obj.optString("dateTime")
            if (dateTime.isNotBlank()) {
                return runCatching { java.time.OffsetDateTime.parse(dateTime).toInstant().toEpochMilli() }.getOrNull()
            }
            val date = obj.optString("date")
            return date.takeIf { it.isNotBlank() }?.let {
                runCatching { java.time.LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            }
        }

        private fun JSONObject.toCalendarEvent() = CalendarEvent(
            id = optString("id"),
            summary = optString("summary"),
            start = timeOf("start"),
            end = timeOf("end"),
            updated = optString("updated").toEpochMillisOrNull(),
            taskId = privateTaskId(),
        )
    }
}
