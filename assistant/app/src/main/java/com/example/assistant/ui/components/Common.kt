package com.example.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.assistant.R
import com.example.assistant.domain.model.Priority
import com.example.assistant.ui.theme.PriorityColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Colors offered for tags. */
val TagPalette = listOf("#EF4444", "#F59E0B", "#10B981", "#3B82F6", "#8B5CF6", "#EC4899", "#64748B")

/** Pastel colors offered for notes (text on them is always dark). */
val NotePalette = listOf("#FDE68A", "#BBF7D0", "#BFDBFE", "#FBCFE8", "#DDD6FE", "#FED7AA", "#E5E7EB")

/** Parses `#RRGGBB` / `#AARRGGBB`; returns null for invalid input. */
fun String?.toColorOrNull(): Color? =
    this?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/** Color for a priority badge. */
fun Priority.color(): Color = when (this) {
    Priority.LOW -> PriorityColors.Low
    Priority.MEDIUM -> PriorityColors.Medium
    Priority.HIGH -> PriorityColors.High
}

/** Localized label for a priority. */
@Composable
fun Priority.label(): String = stringResource(
    when (this) {
        Priority.LOW -> R.string.priority_low
        Priority.MEDIUM -> R.string.priority_medium
        Priority.HIGH -> R.string.priority_high
    },
)

/** Small filled circle. */
@Composable
fun ColorDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/**
 * Row of selectable color circles.
 *
 * @param allowNone when true, shows a leading "no color" option that reports `null`.
 */
@Composable
fun ColorSwatchRow(
    colors: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = false,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (allowNone) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onSelect(null) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (selected == null) Icons.Default.Check else Icons.Default.FormatColorReset,
                    contentDescription = stringResource(R.string.color_none),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        colors.forEach { hex ->
            val color = hex.toColorOrNull() ?: return@forEach
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onSelect(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (hex.equals(selected, ignoreCase = true)) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** Formatting helpers for deadlines. */
object DateText {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun time(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(timeFormat)

    fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun full(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone)
        .format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.getDefault()))

    fun short(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone)
        .format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()))
}

/** Human label for a deadline: "Today 18:00", "Tomorrow 09:00", "12 Oct, 10:00". */
@Composable
fun deadlineLabel(deadline: Long): String {
    val today = LocalDate.now()
    val date = DateText.localDate(deadline)
    val time = DateText.time(deadline)
    return when (date) {
        today -> stringResource(R.string.deadline_today, time)
        today.plusDays(1) -> stringResource(R.string.deadline_tomorrow, time)
        else -> DateText.short(deadline)
    }
}
