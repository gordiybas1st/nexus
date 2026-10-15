package com.example.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.assistant.R
import com.example.assistant.domain.model.Priority
import com.example.assistant.domain.model.Subtask
import com.example.assistant.domain.model.Tag
import com.example.assistant.domain.model.Task
import com.example.assistant.ui.theme.AssistantTheme

/**
 * One row in the task list.
 *
 * @param onToggleDone called with the new checked state (after haptic feedback).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCard(
    task: Task,
    onToggleDone: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = task.isDone,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleDone(it)
                },
            )
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(task.priority.color())
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                        color = if (task.isDone) muted else Color.Unspecified,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!task.description.isNullOrBlank()) {
                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                TaskMeta(task)
            }
        }
    }
}

@Composable
private fun TaskMeta(task: Task) {
    val hasDeadline = task.deadline != null
    val hasMeta = hasDeadline || task.tag != null || task.subtasks.isNotEmpty() || task.rrule != null
    if (!hasMeta) return

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        task.deadline?.let { deadline ->
            val overdue = task.isOverdue()
            val color = if (overdue) MaterialTheme.colorScheme.error else muted
            MetaItem(icon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp), tint = color) }) {
                Text(
                    text = if (overdue) stringResource(R.string.deadline_overdue) else deadlineLabel(deadline),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = if (overdue) FontWeight.Bold else null,
                )
            }
        }
        if (task.rrule != null) {
            Icon(Icons.Outlined.Repeat, contentDescription = stringResource(R.string.section_repeat), Modifier.size(14.dp), tint = muted)
        }
        if (task.subtasks.isNotEmpty()) {
            MetaItem(icon = { Icon(Icons.Outlined.Checklist, null, Modifier.size(14.dp), tint = muted) }) {
                Text(
                    "${task.completedSubtasks}/${task.subtasks.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                )
            }
        }
        task.tag?.let { tag -> TagChip(tag) }
    }
}

@Composable
private fun MetaItem(icon: @Composable () -> Unit, text: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        icon()
        text()
    }
}

/** Compact colored tag label. */
@Composable
fun TagChip(tag: Tag, modifier: Modifier = Modifier) {
    val color = tag.colorHex.toColorOrNull() ?: MaterialTheme.colorScheme.secondary
    Text(
        text = tag.name,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Preview(showBackground = true)
@Composable
private fun TaskCardPreview() {
    AssistantTheme(dynamicColor = false) {
        TaskCard(
            task = Task(
                id = 1,
                title = "Submit grant application",
                description = "Upload PDF and recommendation letters",
                priority = Priority.HIGH,
                deadline = System.currentTimeMillis() + 3_600_000,
                tag = Tag(1, "work", "#3B82F6"),
                subtasks = listOf(Subtask(title = "CV", isDone = true), Subtask(title = "Letter")),
            ),
            onToggleDone = {},
            onClick = {},
            modifier = Modifier.padding(8.dp),
        )
    }
}
