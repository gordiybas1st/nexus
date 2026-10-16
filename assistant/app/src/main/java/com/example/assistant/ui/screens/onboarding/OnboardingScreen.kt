package com.example.assistant.ui.screens.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.assistant.R
import com.example.assistant.data.prefs.UserPreferences
import com.example.assistant.data.remote.ConnectOutcome
import com.example.assistant.data.remote.ConnectTarget
import com.example.assistant.data.remote.GoogleConnectFlow
import com.example.assistant.data.repository.TaskRepository
import com.example.assistant.domain.model.Task
import com.example.assistant.ui.theme.AssistantTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: UserPreferences,
    private val connectFlow: GoogleConnectFlow,
    private val tasks: TaskRepository,
) : ViewModel() {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _consent = Channel<ConnectOutcome.NeedsConsent>(Channel.BUFFERED)
    val consent: Flow<ConnectOutcome.NeedsConsent> = _consent.receiveAsFlow()

    fun connect(activity: Activity) {
        viewModelScope.launch {
            _busy.value = true
            handle(connectFlow.connectCalendar(activity))
            _busy.value = false
        }
    }

    fun onConsentResult(resultCode: Int, data: android.content.Intent?) {
        viewModelScope.launch { handle(connectFlow.finishConsent(ConnectTarget.CALENDAR, resultCode, data)) }
    }

    /** Creates a demo task (only for an empty list) and marks onboarding as done. */
    fun finish(demoTitle: String) {
        viewModelScope.launch {
            if (tasks.observeTasks().first().isEmpty()) {
                val zone = ZoneId.systemDefault()
                val evening = LocalDate.now(zone).atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
                tasks.saveTask(Task(title = demoTitle, deadline = evening))
            }
            preferences.setOnboardingDone()
        }
    }

    private suspend fun handle(outcome: ConnectOutcome) {
        when (outcome) {
            is ConnectOutcome.Connected -> _connected.value = true
            is ConnectOutcome.NeedsConsent -> _consent.send(outcome)
            else -> Unit
        }
    }
}

/** First-launch flow: welcome → Google → permissions → demo task. */
@Composable
fun OnboardingRoute(viewModel: OnboardingViewModel = hiltViewModel()) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onConsentResult(it.resultCode, it.data)
    }
    LaunchedEffect(viewModel) {
        viewModel.consent.collect { consentLauncher.launch(IntentSenderRequest.Builder(it.pendingIntent.intentSender).build()) }
    }
    val demoTitle = stringResource(R.string.onboarding_demo_task)
    OnboardingContent(
        connected = connected,
        busy = busy,
        onConnect = { context.findActivity()?.let(viewModel::connect) },
        onFinish = { viewModel.finish(demoTitle) },
    )
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingContent(connected: Boolean, busy: Boolean, onConnect: () -> Unit, onFinish: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
        ) {
            AnimatedContent(targetState = step, label = "onboardingStep", modifier = Modifier.weight(1f)) { current ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                    when (current) {
                        0 -> WelcomeStep()
                        1 -> GoogleStep(connected, busy, onConnect)
                        2 -> PermissionsStep()
                        else -> DemoStep()
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step < LAST_STEP) {
                    TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
                } else if (step > 0) {
                    TextButton(onClick = { step-- }) { Text(stringResource(R.string.action_back)) }
                }
                Spacer(Modifier.weight(1f))
                Text("${step + 1} / ${LAST_STEP + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (step < LAST_STEP) step++ else onFinish() }) {
                    Text(stringResource(if (step < LAST_STEP) R.string.onboarding_next else R.string.onboarding_start))
                }
            }
        }
    }
}

private const val LAST_STEP = 3

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun WelcomeStep() {
    StepTitle(stringResource(R.string.onboarding_welcome_title))
    listOf(R.string.onboarding_bullet_1, R.string.onboarding_bullet_2, R.string.onboarding_bullet_3).forEach {
        Text("• " + stringResource(it), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GoogleStep(connected: Boolean, busy: Boolean, onConnect: () -> Unit) {
    StepTitle(stringResource(R.string.onboarding_google_title))
    Text(stringResource(R.string.onboarding_google_text), style = MaterialTheme.typography.bodyLarge)
    when {
        connected -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.msg_connected), Modifier.padding(start = 8.dp))
        }
        busy -> CircularProgressIndicator()
        else -> Button(onClick = onConnect) {
            Icon(Icons.Outlined.Event, null)
            Text(stringResource(R.string.action_connect_calendar), Modifier.padding(start = 8.dp))
        }
    }
    Text(stringResource(R.string.onboarding_google_later), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PermissionsStep() {
    StepTitle(stringResource(R.string.onboarding_permissions_title))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionRow(Icons.Outlined.Notifications, R.string.onboarding_perm_notifications, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    PermissionRow(Icons.Outlined.Mic, R.string.onboarding_perm_mic, arrayOf(Manifest.permission.RECORD_AUDIO))
    PermissionRow(
        Icons.Outlined.LocationOn,
        R.string.onboarding_perm_location,
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    )
    Text(stringResource(R.string.onboarding_permissions_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PermissionRow(icon: ImageVector, label: Int, permissions: Array<String>) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(permissions.any { context.isGranted(it) }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it }
    }
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(icon, null) },
        trailingContent = {
            if (granted) {
                Icon(Icons.Filled.CheckCircle, stringResource(R.string.onboarding_granted), tint = MaterialTheme.colorScheme.primary)
            } else {
                OutlinedButton(onClick = { launcher.launch(permissions) }) { Text(stringResource(R.string.onboarding_allow)) }
            }
        },
    )
}

@Composable
private fun DemoStep() {
    StepTitle(stringResource(R.string.onboarding_demo_title))
    Text(stringResource(R.string.onboarding_demo_text), style = MaterialTheme.typography.bodyLarge)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Icon(Icons.Filled.CheckCircle, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.onboarding_demo_hint), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
    }
}

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
private fun OnboardingPreview() {
    AssistantTheme(dynamicColor = false) {
        OnboardingContent(connected = false, busy = false, onConnect = {}, onFinish = {})
    }
}
