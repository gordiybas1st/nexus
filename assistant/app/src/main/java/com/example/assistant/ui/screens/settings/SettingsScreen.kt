package com.example.assistant.ui.screens.settings

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.assistant.BuildConfig
import com.example.assistant.R
import com.example.assistant.data.local.dao.SyncConflictDao
import com.example.assistant.data.local.entity.SyncConflictEntity
import com.example.assistant.data.prefs.ProductivitySettings
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.data.prefs.UserSettings
import com.example.assistant.data.remote.CalendarAccessResult
import com.example.assistant.data.remote.GoogleAuthManager
import com.example.assistant.data.remote.SignInResult
import com.example.assistant.domain.model.ThemeMode
import com.example.assistant.notifications.DailyReviewScheduler
import com.example.assistant.notifications.ReminderScheduler
import com.example.assistant.sync.SyncQueueWriter
import com.example.assistant.sync.SyncScheduler
import com.example.assistant.sync.SyncState
import com.example.assistant.sync.SyncStatus
import com.example.assistant.sync.SyncStatusProvider
import com.example.assistant.ui.components.DateText
import com.example.assistant.ui.theme.AssistantTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-off UI effects from [SettingsViewModel]. */
sealed interface SettingsEvent {
    /** Google needs the user to confirm Calendar access. */
    data class LaunchConsent(val pendingIntent: PendingIntent) : SettingsEvent
    data class Message(@StringRes val text: Int, val arg: String? = null) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: UserPreferences,
    private val auth: GoogleAuthManager,
    private val scheduler: SyncScheduler,
    private val queueWriter: SyncQueueWriter,
    private val conflictDao: SyncConflictDao,
    private val reminderScheduler: ReminderScheduler,
    private val dailyReviewScheduler: DailyReviewScheduler,
    syncStatusProvider: SyncStatusProvider,
) : ViewModel() {

    val productivity: StateFlow<ProductivitySettings> =
        preferences.productivity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductivitySettings())

    /** Turns reminders and the daily review on/off and re-creates the alarms. */
    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setNotificationsEnabled(enabled)
            reminderScheduler.rescheduleAll()
            dailyReviewScheduler.schedule()
        }
    }

    fun setDailyReview(enabled: Boolean, hour: Int, minute: Int) {
        viewModelScope.launch {
            preferences.setDailyReview(enabled, hour, minute)
            dailyReviewScheduler.schedule()
        }
    }

    fun setPomodoro(workMinutes: Int, breakMinutes: Int) {
        viewModelScope.launch { preferences.setPomodoroDurations(workMinutes, breakMinutes) }
    }

    val settings: StateFlow<UserSettings> =
        preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val syncStatus: StateFlow<SyncStatus> =
        syncStatusProvider.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus())

    val syncLog: StateFlow<List<SyncConflictEntity>> =
        conflictDao.observeRecent(5).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    private var signedInEmail: String? = null

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferences.setDynamicColor(enabled) }
    }

    /** Sign-in (Credential Manager) followed by Calendar authorization. */
    fun connect(activity: Activity) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = auth.signIn(activity)) {
                    is SignInResult.Success -> signedInEmail = result.email ?: syncStatus.value.email
                    SignInResult.Cancelled -> return@launch
                    SignInResult.NoAccount -> {
                        message(R.string.msg_no_account)
                        return@launch
                    }
                    is SignInResult.Failure -> {
                        message(R.string.msg_sign_in_failed, result.message)
                        return@launch
                    }
                }
                handleAccess(auth.requestCalendarAccess(signedInEmail))
            } finally {
                _busy.value = false
            }
        }
    }

    /** Result of the consent screen launched for [SettingsEvent.LaunchConsent]. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch {
            if (resultCode != Activity.RESULT_OK) {
                message(R.string.msg_access_denied)
            } else {
                handleAccess(auth.handleConsentResult(data))
            }
        }
    }

    fun syncNow() = scheduler.requestFullSync()

    /** Stops calendar sync but keeps the account. Existing events stay in Calendar. */
    fun disconnectCalendar() {
        viewModelScope.launch {
            scheduler.cancelAll()
            queueWriter.clear()
            preferences.setCalendarDisabled()
            message(R.string.msg_disconnected)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            scheduler.cancelAll()
            queueWriter.clear()
            auth.signOut()
            signedInEmail = null
            message(R.string.msg_signed_out)
        }
    }

    fun clearSyncLog() {
        viewModelScope.launch { conflictDao.clear() }
    }

    private suspend fun handleAccess(result: CalendarAccessResult) {
        when (result) {
            is CalendarAccessResult.Granted -> {
                preferences.setConnected(result.email ?: signedInEmail)
                scheduler.schedulePeriodic()
                scheduler.requestFullSync()
                message(R.string.msg_connected)
            }
            is CalendarAccessResult.NeedsConsent -> _events.send(SettingsEvent.LaunchConsent(result.pendingIntent))
            is CalendarAccessResult.Failure -> message(R.string.msg_access_failed, result.message)
        }
    }

    private suspend fun message(@StringRes text: Int, arg: String? = null) {
        _events.send(SettingsEvent.Message(text, arg))
    }
}

/** Settings wired to [SettingsViewModel]; launches Google's consent screen when needed. */
@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncLog by viewModel.syncLog.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val productivity by viewModel.productivity.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(result.resultCode, result.data)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.LaunchConsent ->
                    consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent.intentSender).build())
                is SettingsEvent.Message -> snackbar.showSnackbar(
                    if (event.arg == null) context.getString(event.text) else context.getString(event.text, event.arg),
                )
            }
        }
    }

    SettingsContent(
        settings = settings,
        syncStatus = syncStatus,
        syncLog = syncLog,
        busy = busy,
        productivity = productivity,
        snackbarHostState = snackbar,
        actions = SettingsActions(
            onNotificationsChange = viewModel::setNotificationsEnabled,
            onDailyReviewChange = viewModel::setDailyReview,
            onPomodoroChange = viewModel::setPomodoro,
            onThemeModeChange = viewModel::setThemeMode,
            onDynamicColorChange = viewModel::setDynamicColor,
            onConnect = { context.findActivity()?.let(viewModel::connect) },
            onSyncNow = viewModel::syncNow,
            onDisconnect = viewModel::disconnectCalendar,
            onSignOut = viewModel::signOut,
            onClearLog = viewModel::clearSyncLog,
        ),
    )
}

/** Callbacks of the settings screen. */
data class SettingsActions(
    val onNotificationsChange: (Boolean) -> Unit = {},
    val onDailyReviewChange: (enabled: Boolean, hour: Int, minute: Int) -> Unit = { _, _, _ -> },
    val onPomodoroChange: (workMinutes: Int, breakMinutes: Int) -> Unit = { _, _ -> },
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
    val onConnect: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onClearLog: () -> Unit = {},
)

/** Stateless settings screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: UserSettings,
    syncStatus: SyncStatus,
    syncLog: List<SyncConflictEntity>,
    busy: Boolean,
    productivity: ProductivitySettings,
    snackbarHostState: SnackbarHostState,
    actions: SettingsActions,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_account))
            AccountSection(syncStatus, busy, actions)

            if (syncLog.isNotEmpty()) {
                SectionHeader(stringResource(R.string.settings_sync_log))
                syncLog.forEach { entry ->
                    ListItem(
                        headlineContent = { Text(entry.message, style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text(DateText.short(entry.createdAt)) },
                    )
                }
                TextButton(onClick = actions.onClearLog, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.action_clear_log))
                }
            }

            SectionHeader(stringResource(R.string.settings_productivity))
            ProductivitySection(productivity, actions)

            SectionHeader(stringResource(R.string.settings_appearance))
            ThemeSection(settings, actions)

            SectionHeader(stringResource(R.string.settings_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.version_fmt, BuildConfig.VERSION_NAME)) },
                leadingContent = { Icon(Icons.Outlined.Info, null) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountSection(status: SyncStatus, busy: Boolean, actions: SettingsActions) {
    val connected = status.state != SyncState.DISABLED

    ListItem(
        headlineContent = { Text(status.email ?: stringResource(R.string.calendar_title)) },
        supportingContent = { Text(syncStatusText(status)) },
        leadingContent = {
            Icon(if (status.email != null) Icons.Outlined.AccountCircle else Icons.Outlined.Event, null)
        },
        trailingContent = if (busy) {
            { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else {
            null
        },
    )

    if (status.state == SyncState.AUTH_REQUIRED) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.sync_auth_required)) },
            leadingContent = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        )
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        when {
            !connected -> Button(onClick = actions.onConnect, enabled = !busy) {
                Text(stringResource(R.string.action_connect_calendar))
            }
            status.state == SyncState.AUTH_REQUIRED -> Button(onClick = actions.onConnect, enabled = !busy) {
                Text(stringResource(R.string.action_grant_access))
            }
            else -> OutlinedButton(onClick = actions.onSyncNow) {
                Text(stringResource(R.string.action_sync_now))
            }
        }
        if (connected) {
            TextButton(onClick = actions.onDisconnect) { Text(stringResource(R.string.action_disconnect_calendar)) }
        }
        if (status.email != null) {
            TextButton(onClick = actions.onSignOut) { Text(stringResource(R.string.action_sign_out)) }
        }
    }
}

@Composable
private fun syncStatusText(status: SyncStatus): String = when (status.state) {
    SyncState.DISABLED -> stringResource(
        if (status.email == null) R.string.calendar_not_connected else R.string.calendar_sync_off,
    )
    SyncState.SYNCED -> status.lastSyncAt?.let { stringResource(R.string.sync_synced_at, DateText.short(it)) }
        ?: stringResource(R.string.sync_state_synced)
    SyncState.PENDING -> stringResource(R.string.sync_pending_count, status.pendingCount)
    SyncState.SYNCING -> stringResource(R.string.sync_state_syncing)
    SyncState.ERROR -> status.lastError ?: stringResource(R.string.sync_state_error)
    SyncState.AUTH_REQUIRED -> stringResource(R.string.sync_state_auth)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductivitySection(settings: ProductivitySettings, actions: SettingsActions) {
    val context = LocalContext.current
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_notifications)) },
        supportingContent = { Text(stringResource(R.string.settings_notifications_hint)) },
        leadingContent = { Icon(Icons.Outlined.Notifications, null) },
        trailingContent = { Switch(checked = settings.notificationsEnabled, onCheckedChange = actions.onNotificationsChange) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_daily_review)) },
        supportingContent = {
            Text(stringResource(R.string.settings_daily_review_hint, "%02d:%02d".format(settings.dailyReviewHour, settings.dailyReviewMinute)))
        },
        leadingContent = { Icon(Icons.Outlined.NightsStay, null) },
        trailingContent = {
            Switch(
                checked = settings.dailyReviewEnabled,
                enabled = settings.notificationsEnabled,
                onCheckedChange = { actions.onDailyReviewChange(it, settings.dailyReviewHour, settings.dailyReviewMinute) },
            )
        },
        modifier = Modifier.clickable(enabled = settings.notificationsEnabled && settings.dailyReviewEnabled) { showTimePicker = true },
    )
    MinutesStepper(
        label = stringResource(R.string.settings_pomodoro_work),
        value = settings.pomodoroWorkMinutes,
        range = 5..90,
        step = 5,
        onChange = { actions.onPomodoroChange(it, settings.pomodoroBreakMinutes) },
    )
    MinutesStepper(
        label = stringResource(R.string.settings_pomodoro_break),
        value = settings.pomodoroBreakMinutes,
        range = 1..30,
        step = 1,
        onChange = { actions.onPomodoroChange(settings.pomodoroWorkMinutes, it) },
    )

    // Android 12 only: exact alarms need user approval (13+ uses USE_EXACT_ALARM).
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S || Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_exact_alarms)) },
                supportingContent = { Text(stringResource(R.string.settings_exact_alarms_hint)) },
                leadingContent = { Icon(Icons.Outlined.Alarm, null) },
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)),
                        )
                    }
                },
            )
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = settings.dailyReviewHour,
            initialMinute = settings.dailyReviewMinute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showTimePicker = false
                    actions.onDailyReviewChange(true, timeState.hour, timeState.minute)
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            text = { TimePicker(state = timeState) },
        )
    }
}

@Composable
private fun MinutesStepper(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(stringResource(R.string.minutes_fmt, value)) },
        leadingContent = { Icon(Icons.Outlined.Timer, null) },
        trailingContent = {
            Row {
                IconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) {
                    Icon(Icons.Default.Remove, stringResource(R.string.action_decrease))
                }
                IconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) {
                    Icon(Icons.Default.Add, stringResource(R.string.action_increase))
                }
            }
        },
    )
}

@Composable
private fun ThemeSection(settings: UserSettings, actions: SettingsActions) {
    Column(Modifier.selectableGroup()) {
        val modes = listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark,
        )
        modes.forEach { (mode, label) ->
            val selected = settings.themeMode == mode
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                leadingContent = { RadioButton(selected = selected, onClick = null) },
                modifier = Modifier.selectable(
                    selected = selected,
                    onClick = { actions.onThemeModeChange(mode) },
                    role = Role.RadioButton,
                ),
            )
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.dynamic_color)) },
            supportingContent = { Text(stringResource(R.string.dynamic_color_hint)) },
            leadingContent = { Icon(Icons.Outlined.Palette, null) },
            trailingContent = {
                Switch(checked = settings.dynamicColor, onCheckedChange = actions.onDynamicColorChange)
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Unwraps a Compose [Context] to its hosting Activity. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    AssistantTheme(dynamicColor = false) {
        SettingsContent(
            settings = UserSettings(),
            syncStatus = SyncStatus(state = SyncState.PENDING, pendingCount = 2, email = "me@gmail.com"),
            syncLog = listOf(SyncConflictEntity(1, 3, "abc", "Event was edited in Calendar; local version kept", System.currentTimeMillis())),
            busy = false,
            productivity = ProductivitySettings(),
            snackbarHostState = remember { SnackbarHostState() },
            actions = SettingsActions(),
        )
    }
}
