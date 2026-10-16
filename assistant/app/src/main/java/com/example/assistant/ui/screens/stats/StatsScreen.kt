package com.example.assistant.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.assistant.R
import com.example.assistant.data.repository.StatsRepository
import com.example.assistant.domain.AchievementId
import com.example.assistant.domain.AchievementProgress
import com.example.assistant.domain.DayCount
import com.example.assistant.domain.Stats
import com.example.assistant.domain.TagTime
import com.example.assistant.notifications.NotificationHelper
import com.example.assistant.ui.components.toColorOrNull
import com.example.assistant.ui.theme.AssistantTheme
import com.example.assistant.ui.theme.PriorityColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/** Statistics screen state; null while loading. */
data class StatsUiState(val stats: Stats, val achievements: List<AchievementProgress>)

@HiltViewModel
class StatsViewModel @Inject constructor(repository: StatsRepository) : ViewModel() {
    val state: StateFlow<StatsUiState?> = repository.observe()
        .map { (stats, achievements) -> StatsUiState(stats, achievements) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun StatsRoute(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatsContent(state)
}

/** Stateless statistics screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(state: StatsUiState?) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.stats_title)) }) },
    ) { padding ->
        if (state == null) return@Scaffold
        val stats = state.stats
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberCard(stringResource(R.string.stats_streak), "🔥 ${stats.streak}", Modifier.weight(1f))
                NumberCard(stringResource(R.string.stats_week), stats.doneThisWeek.toString(), Modifier.weight(1f))
                NumberCard(stringResource(R.string.stats_month), stats.doneThisMonth.toString(), Modifier.weight(1f))
            }
            SectionCard(stringResource(R.string.stats_overview)) { Donut(stats) }
            SectionCard(stringResource(R.string.stats_last_7_days)) { WeekBars(stats.last7Days) }
            SectionCard(stringResource(R.string.stats_focus_title, formatDuration(stats.focusThisWeekMs))) {
                FocusByTag(stats.focusByTag, stats.focusThisWeekMs)
            }
            SectionCard(stringResource(R.string.stats_achievements)) {
                state.achievements.forEach { AchievementRow(it) }
            }
        }
    }
}

@Composable
private fun NumberCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Done / active / overdue donut with legend. */
@Composable
private fun Donut(stats: Stats) {
    val slices = listOf(
        Triple(stringResource(R.string.stats_done), stats.done, PriorityColors.Low),
        Triple(stringResource(R.string.stats_active), stats.active, MaterialTheme.colorScheme.primary),
        Triple(stringResource(R.string.stats_overdue), stats.overdue, PriorityColors.High),
    )
    val total = slices.sumOf { it.second }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 18.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                if (total == 0) {
                    drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                } else {
                    var start = -90f
                    slices.forEach { (_, value, color) ->
                        val sweep = 360f * value / total
                        if (sweep > 0f) drawArc(color, start, sweep, false, topLeft, arcSize, style = Stroke(stroke))
                        start += sweep
                    }
                }
            }
            Text(total.toString(), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            slices.forEach { (label, value, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(12.dp)) { drawCircle(color) }
                    Spacer(Modifier.width(8.dp))
                    Text("$label: $value")
                }
            }
        }
    }
}

/** Completed tasks per day for the last 7 days. */
@Composable
private fun WeekBars(days: List<DayCount>) {
    val max = (days.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val today = LocalDate.now()
    Row(
        Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(day.count.toString(), style = MaterialTheme.typography.labelSmall)
                Canvas(Modifier.width(22.dp).height(96.dp)) {
                    val h = size.height * day.count / max
                    drawRoundRect(
                        color = if (day.date == today) barColor else barColor.copy(alpha = 0.55f),
                        topLeft = Offset(0f, size.height - h),
                        size = Size(size.width, h),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FocusByTag(items: List<TagTime>, total: Long) {
    if (items.isEmpty()) {
        Text(stringResource(R.string.stats_focus_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val noTag = stringResource(R.string.stats_no_tag)
    items.forEach { item ->
        val color = item.colorHex.toColorOrNull() ?: MaterialTheme.colorScheme.secondary
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(item.name?.let { "#$it" } ?: noTag, Modifier.weight(1f))
                Text(formatDuration(item.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { if (total > 0) item.durationMs.toFloat() / total else 0f },
                color = color,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AchievementRow(item: AchievementProgress) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (item.unlocked) Icons.Filled.EmojiEvents else Icons.Outlined.EmojiEvents,
            contentDescription = null,
            tint = if (item.unlocked) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(NotificationHelper.achievementTitle(item.id)))
            LinearProgressIndicator(progress = { item.fraction }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (item.unlocked) "✓" else "${item.current}/${item.target}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) {
        stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
    } else {
        stringResource(R.string.minutes_fmt, minutes.toInt())
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun StatsPreview() {
    val today = LocalDate.now()
    AssistantTheme(dynamicColor = false) {
        StatsContent(
            StatsUiState(
                Stats(
                    done = 42, active = 7, overdue = 2, streak = 5, doneThisWeek = 12, doneThisMonth = 30,
                    last7Days = (6 downTo 0).map { DayCount(today.minusDays(it.toLong()), (it * 3) % 5) },
                    focusThisWeekMs = 5 * 3_600_000L,
                    focusByTag = listOf(TagTime("work", "#3B82F6", 3 * 3_600_000L), TagTime(null, null, 2 * 3_600_000L)),
                ),
                AchievementId.entries.map { AchievementProgress(it, minOf(42, it.target), if (it.target <= 42) 1L else null) },
            ),
        )
    }
}
