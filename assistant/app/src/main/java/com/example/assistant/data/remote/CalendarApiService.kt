package com.example.assistant.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Non-2xx response from the Calendar API. Network failures are plain [IOException]s. */
class CalendarApiException(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(300)}") {
    /** Google's rate-limit 403s are temporary and should be retried. */
    val isRateLimited: Boolean
        get() = code == 429 || (code == 403 && (body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded")))
}

/**
 * Minimal Google Calendar v3 REST client for the `primary` calendar.
 *
 * Uses [HttpURLConnection] + [JSONObject] from the Android SDK instead of the Java API client:
 * no extra dependencies, no META-INF packaging clashes, and the same wire format.
 */
@Singleton
class CalendarApiService @Inject constructor() {

    suspend fun insertEvent(token: String, event: JSONObject): JSONObject =
        JSONObject(request("POST", EVENTS_URL, token, event))

    /** Full replace (PUT) — the app is the source of truth for its events. */
    suspend fun updateEvent(token: String, eventId: String, event: JSONObject): JSONObject =
        JSONObject(request("PUT", "$EVENTS_URL/${encode(eventId)}", token, event))

    suspend fun deleteEvent(token: String, eventId: String) {
        request("DELETE", "$EVENTS_URL/${encode(eventId)}", token, null)
    }

    /**
     * Lists events (single page).
     *
     * @param timeMin / [timeMax] RFC 3339 bounds or null.
     * @param privateProperty filter like `source=personal_assistant`.
     */
    suspend fun listEvents(
        token: String,
        timeMin: String? = null,
        timeMax: String? = null,
        privateProperty: String? = null,
        pageToken: String? = null,
    ): JSONObject {
        val params = buildList {
            add("maxResults=250")
            add("showDeleted=false")
            timeMin?.let { add("timeMin=${encode(it)}") }
            timeMax?.let { add("timeMax=${encode(it)}") }
            privateProperty?.let { add("privateExtendedProperty=${encode(it)}") }
            pageToken?.let { add("pageToken=${encode(it)}") }
        }
        return JSONObject(request("GET", "$EVENTS_URL?${params.joinToString("&")}", token, null))
    }

    private suspend fun request(method: String, url: String, token: String, body: JSONObject?): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw CalendarApiException(code, text)
                text.ifBlank { "{}" }
            } finally {
                conn.disconnect()
            }
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val EVENTS_URL = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
        const val TIMEOUT_MS = 20_000
    }
}
