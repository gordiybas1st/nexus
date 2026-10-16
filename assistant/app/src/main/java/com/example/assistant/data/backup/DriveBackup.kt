package com.example.assistant.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.data.remote.AuthRequiredException
import com.example.assistant.data.remote.CalendarApiException
import com.example.assistant.data.remote.GoogleAuthManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backups in the hidden app-data folder of the user's Google Drive (scope `drive.appdata`).
 * One file, `assistant-backup.json`, is created once and then overwritten.
 */
@Singleton
class DriveBackupService @Inject constructor(
    private val auth: GoogleAuthManager,
    private val backup: BackupManager,
    private val preferences: UserPreferences,
) {
    /** Uploads the current data. @throws AuthRequiredException, CalendarApiException, IOException */
    suspend fun backupNow() {
        val token = auth.getDriveAccessToken()
        val json = backup.exportJson()
        val existing = findBackupId(token)
        if (existing == null) {
            val boundary = "assistant-${System.currentTimeMillis()}"
            val meta = JSONObject().put("name", FILE_NAME).put("parents", org.json.JSONArray().put("appDataFolder"))
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n$json\r\n--$boundary--"
            request("POST", "$UPLOAD_URL?uploadType=multipart", token, body, "multipart/related; boundary=$boundary")
        } else {
            // HttpURLConnection has no PATCH; Google APIs accept the override header.
            request("POST", "$UPLOAD_URL/$existing?uploadType=media", token, json, "application/json", methodOverride = "PATCH")
        }
        preferences.setBackupResult(System.currentTimeMillis(), null)
    }

    /** Downloads the backup and imports it. Returns null when there is no backup yet. */
    suspend fun restore(): ImportResult? {
        val token = auth.getDriveAccessToken()
        val id = findBackupId(token) ?: return null
        val json = request("GET", "$FILES_URL/$id?alt=media", token, null, null)
        return backup.importJson(json)
    }

    private suspend fun findBackupId(token: String): String? {
        val q = URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
        val response = JSONObject(request("GET", "$FILES_URL?spaces=appDataFolder&q=$q&fields=files(id)", token, null, null))
        return response.optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
    }

    private suspend fun request(
        method: String,
        url: String,
        token: String,
        body: String?,
        contentType: String?,
        methodOverride: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            methodOverride?.let { conn.setRequestProperty("X-HTTP-Method-Override", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code == 401) {
                auth.invalidateToken(token)
                throw AuthRequiredException("Drive access expired")
            }
            if (code !in 200..299) throw CalendarApiException(code, text)
            text
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val FILE_NAME = "assistant-backup.json"
        const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    }
}

/** Schedules the weekly Drive backup. */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleWeekly() {
        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(7, TimeUnit.DAYS).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_WEEKLY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun backupNow() {
        val request = OneTimeWorkRequestBuilder<DriveBackupWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_WEEKLY)
    }

    private companion object {
        const val WORK_WEEKLY = "drive_backup_weekly"
        const val WORK_NOW = "drive_backup_now"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupEntryPoint {
    fun driveBackup(): DriveBackupService
    fun preferences(): UserPreferences
}

/** Runs [DriveBackupService.backupNow] in the background. */
class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, BackupEntryPoint::class.java)
        return try {
            deps.driveBackup().backupNow()
            Result.success()
        } catch (e: AuthRequiredException) {
            deps.preferences().setBackupResult(null, "Google Drive access is needed again")
            Result.failure()
        } catch (e: CalendarApiException) {
            deps.preferences().setBackupResult(null, "Drive error ${e.code}")
            if (e.code >= 500 || e.isRateLimited) Result.retry() else Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
