package com.example.assistant.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.assistant.data.local.dao.TaskDao
import com.example.assistant.di.AppEntryPoint
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import com.google.android.gms.location.Priority as LocationPriority

/**
 * Location reminders ("remind me to buy bread near the store") via the Geofencing API.
 *
 * Needs fine location and, on Android 10+, background location — Android only reports
 * geofence events to apps allowed to use location all the time. Geofences are lost on
 * reboot, so app start and [BootReceiver] call [registerAll].
 */
@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: TaskDao,
) {
    private val client get() = LocationServices.getGeofencingClient(context)

    fun hasForegroundPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackgroundPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Current position as (latitude, longitude), or null without permission / fix. */
    @SuppressLint("MissingPermission") // checked by hasForegroundPermission()
    suspend fun currentLocation(): Pair<Double, Double>? {
        if (!hasForegroundPermission()) return null
        return runCatching {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(LocationPriority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .awaitOrNull()
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    /** Adds or removes the geofence of a task to match its state. */
    suspend fun update(taskId: Long) {
        val task = taskDao.getWithRelations(taskId)?.task
        val lat = task?.locationLat
        val lng = task?.locationLng
        if (task == null || task.isDone || lat == null || lng == null) {
            remove(taskId)
        } else {
            add(taskId, lat, lng, task.locationRadius ?: DEFAULT_RADIUS_M)
        }
    }

    fun remove(taskId: Long) {
        runCatching { client.removeGeofences(listOf(taskId.toString())) }
    }

    /** Re-registers every open task with a location (after boot or app start). */
    suspend fun registerAll() {
        taskDao.getWithLocation().forEach { task ->
            val lat = task.locationLat ?: return@forEach
            val lng = task.locationLng ?: return@forEach
            add(task.id, lat, lng, task.locationRadius ?: DEFAULT_RADIUS_M)
        }
    }

    @SuppressLint("MissingPermission") // checked below
    private fun add(taskId: Long, lat: Double, lng: Double, radius: Float) {
        if (!hasForegroundPermission() || !hasBackgroundPermission()) return
        val geofence = Geofence.Builder()
            .setRequestId(taskId.toString())
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        // No initial trigger: saving a reminder while standing at the place should not fire it.
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(geofence).build()
        runCatching { client.addGeofences(request, pendingIntent) }
    }

    private val pendingIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        PendingIntent.getBroadcast(context, 0, Intent(context, GeofenceReceiver::class.java), flags)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val DEFAULT_RADIUS_M = 150f
    }
}

/** Shows a reminder when the user enters a task's geofence. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        // Errors (e.g. location turned off) just mean no reminder this time.
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val ids = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.toLongOrNull() }
        if (ids.isEmpty()) return

        val pending = goAsync()
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, AppEntryPoint::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ids.forEach { id ->
                    val task = deps.taskRepository().getTask(id) ?: return@forEach
                    if (!task.isDone) deps.notificationHelper().showLocationReminder(task)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resume(null) }
    addOnCanceledListener { cont.resume(null) }
}
