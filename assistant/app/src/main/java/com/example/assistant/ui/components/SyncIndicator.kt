package com.example.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import com.example.assistant.R
import com.example.assistant.sync.SyncState
import com.example.assistant.sync.SyncStatus

/** Cloud icon in the top bar showing calendar sync state. Hidden when sync is off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncIndicator(status: SyncStatus, onClick: () -> Unit) {
    if (status.state == SyncState.DISABLED) return

    val description = stringResource(
        when (status.state) {
            SyncState.SYNCED -> R.string.sync_state_synced
            SyncState.PENDING -> R.string.sync_state_pending
            SyncState.SYNCING -> R.string.sync_state_syncing
            SyncState.ERROR -> R.string.sync_state_error
            SyncState.AUTH_REQUIRED -> R.string.sync_state_auth
            SyncState.DISABLED -> R.string.sync_state_disabled
        },
    )
    IconButton(onClick = onClick) {
        when (status.state) {
            SyncState.SYNCING -> {
                val transition = rememberInfiniteTransition(label = "sync")
                val angle by transition.animateFloat(
                    initialValue = 360f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart),
                    label = "syncRotation",
                )
                Icon(Icons.Outlined.Sync, description, Modifier.rotate(angle))
            }
            SyncState.PENDING -> BadgedBox(badge = { Badge { Text(status.pendingCount.coerceAtMost(99).toString()) } }) {
                Icon(Icons.Outlined.CloudQueue, description)
            }
            SyncState.SYNCED -> Icon(Icons.Outlined.CloudDone, description)
            SyncState.ERROR -> Icon(Icons.Outlined.SyncProblem, description, tint = MaterialTheme.colorScheme.error)
            SyncState.AUTH_REQUIRED -> Icon(Icons.Outlined.CloudOff, description, tint = MaterialTheme.colorScheme.error)
            SyncState.DISABLED -> Unit
        }
    }
}
