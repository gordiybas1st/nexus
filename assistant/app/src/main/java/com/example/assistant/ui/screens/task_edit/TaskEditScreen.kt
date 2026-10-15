package com.example.assistant.ui.screens.task_edit

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.assistant.R
import com.example.assistant.domain.model.Priority
import com.example.assistant.domain.model.RepeatOption
import com.example.assistant.domain.model.Tag
import com.example.assistant.ui.components.ColorDot
import com.example.assistant.ui.components.ColorSwatchRow
import com.example.assistant.ui.components.DateText
import com.example.assistant.ui.components.TagPalette
import com.example.assistant.ui.components.label
import com.example.assistant.ui.components.toColorOrNull
import com.example.assistant.ui.theme.AssistantTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Task editor wired to [TaskEditViewModel]. Closes itself after save/delete. */
@Composable
fun TaskEditRoute(
    onBack: () -> Unit,
    viewModel: TaskEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { onBack() }
    }
    TaskEditContent(
        state = state,
        actions = TaskEditActions(
            onBack = onBack,
            onTitleChange = viewModel::onTitleChange,
            onDescriptionChange = viewModel::onDescriptionChange,
            onPriorityChange = viewModel::onPriorityChange,
            onDeadlineChange = viewModel::onDeadlineChange,
            onRepeatChange = viewModel::onRepeatChange,
            onCustomRruleChange = viewModel::onCustomRruleChange,
            onTagSelect = viewModel::onTagSelect,
            onAddTag = viewModel::addTag,
            onAddSubtask = viewModel::addSubtask,
            onRenameSubtask = viewModel::renameSubtask,
            onToggleSubtask = viewModel::toggleSubtask,
            onRemoveSubtask = viewModel::removeSubtask,
            onSyncToggle = viewModel::onSyncToggle,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
        ),
    )
}

/** Callbacks of the task editor, grouped to keep signatures readable. */
data class TaskEditActions(
    val onBack: () -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onDescriptionChange: (String) -> Unit = {},
    val onPriorityChange: (Priority) -> Unit = {},
    val onDeadlineChange: (Long?) -> Unit = {},
    val onRepeatChange: (RepeatOption) -> Unit = {},
    val onCustomRruleChange: (String) -> Unit = {},
    val onTagSelect: (Long?) -> Unit = {},
    val onAddTag: (String, String) -> Unit = { _, _ -> },
    val onAddSubtask: (String) -> Unit = {},
    val onRenameSubtask: (Long, String) -> Unit = { _, _ -> },
    val onToggleSubtask: (Long) -> Unit = {},
    val onRemoveSubtask: (Long) -> Unit = {},
    val onSyncToggle: (Boolean) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/** Stateless task editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditContent(state: TaskEditUiState, actions: TaskEditActions) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.task_new else R.string.task_edit)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete))
                        }
                    }
                    TextButton(onClick = actions.onSave) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TitleField(state, actions.onTitleChange)
            OutlinedTextField(
                value = state.description,
                onValueChange = actions.onDescriptionChange,
                label = { Text(stringResource(R.string.field_description)) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Section(stringResource(R.string.section_priority)) {
                PrioritySelector(state.priority, actions.onPriorityChange)
            }
            Section(stringResource(R.string.section_deadline)) {
                DeadlinePicker(state.deadline, actions.onDeadlineChange)
            }
            Section(stringResource(R.string.section_repeat)) {
                RepeatSelector(state.repeat, state.customRrule, actions.onRepeatChange, actions.onCustomRruleChange)
            }
            Section(stringResource(R.string.section_tag)) {
                TagSelector(state.tags, state.tagId, actions.onTagSelect, actions.onAddTag)
            }
            Section(stringResource(R.string.section_subtasks)) {
                SubtaskEditor(state.subtasks, actions)
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_calendar)) },
                supportingContent = { Text(stringResource(R.string.sync_calendar_hint)) },
                leadingContent = { Icon(Icons.Outlined.Event, null) },
                trailingContent = { Switch(checked = state.syncToCalendar, onCheckedChange = actions.onSyncToggle) },
            )
            Button(onClick = actions.onSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_save))
            }
            if (!state.isNew) {
                TextButton(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_task_title)) },
            text = { Text(stringResource(R.string.delete_task_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    actions.onDelete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun TitleField(state: TaskEditUiState, onChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    OutlinedTextField(
        value = state.title,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.field_title)) },
        isError = state.titleError,
        supportingText = if (state.titleError) {
            { Text(stringResource(R.string.error_title_required)) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
    LaunchedEffect(state.isNew) {
        if (state.isNew) runCatching { focusRequester.requestFocus() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrioritySelector(selected: Priority, onSelect: (Priority) -> Unit) {
    val options = Priority.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, priority ->
            SegmentedButton(
                selected = priority == selected,
                onClick = { onSelect(priority) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(priority.label())
            }
        }
    }
}

/** Date then time picker; stores local date-time as epoch millis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeadlinePicker(deadline: Long?, onChange: (Long?) -> Unit) {
    val context = LocalContext.current
    var showDate by rememberSaveable { mutableStateOf(false) }
    var pickedDate by rememberSaveable { mutableStateOf<Long?>(null) } // UTC midnight from DatePicker

    OutlinedCard(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, null)
            Text(
                text = deadline?.let { DateText.full(it) } ?: stringResource(R.string.deadline_none),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
            )
            if (deadline != null) {
                IconButton(onClick = { onChange(null) }) {
                    Icon(Icons.Default.Close, stringResource(R.string.deadline_clear))
                }
            }
        }
    }

    if (showDate) {
        val zone = ZoneId.systemDefault()
        val initialUtc = (deadline?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    showDate = false
                    pickedDate = dateState.selectedDateMillis
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    pickedDate?.let { dateUtc ->
        val zone = ZoneId.systemDefault()
        val initialTime = deadline?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(18, 0)
        val timeState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            confirmButton = {
                TextButton(onClick = {
                    val date = Instant.ofEpochMilli(dateUtc).atZone(ZoneOffset.UTC).toLocalDate()
                    val millis = date.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant().toEpochMilli()
                    pickedDate = null
                    onChange(millis)
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickedDate = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            text = { TimePicker(state = timeState) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatSelector(
    selected: RepeatOption,
    customRrule: String,
    onSelect: (RepeatOption) -> Unit,
    onCustomChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        RepeatOption.NEVER to stringResource(R.string.repeat_never),
        RepeatOption.DAILY to stringResource(R.string.repeat_daily),
        RepeatOption.WEEKLY to stringResource(R.string.repeat_weekly),
        RepeatOption.MONTHLY to stringResource(R.string.repeat_monthly),
        RepeatOption.CUSTOM to stringResource(R.string.repeat_custom),
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labels.getValue(selected),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RepeatOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labels.getValue(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
    if (selected == RepeatOption.CUSTOM) {
        OutlinedTextField(
            value = customRrule,
            onValueChange = onCustomChange,
            label = { Text(stringResource(R.string.field_rrule)) },
            placeholder = { Text("FREQ=WEEKLY;BYDAY=MO,WE") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TagSelector(
    tags: List<Tag>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onAddTag: (String, String) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            val selected = tag.id == selectedId
            FilterChip(
                selected = selected,
                onClick = { onSelect(if (selected) null else tag.id) },
                label = { Text(tag.name) },
                leadingIcon = { ColorDot(tag.colorHex.toColorOrNull() ?: MaterialTheme.colorScheme.secondary) },
            )
        }
        AssistChip(
            onClick = { showDialog = true },
            label = { Text(stringResource(R.string.tag_add)) },
            leadingIcon = { Icon(Icons.Default.Add, null) },
        )
    }
    if (showDialog) {
        NewTagDialog(
            onDismiss = { showDialog = false },
            onConfirm = { name, color ->
                showDialog = false
                onAddTag(name, color)
            },
        )
    }
}

@Composable
private fun NewTagDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(TagPalette.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tag_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text(stringResource(R.string.tag_name)) },
                    singleLine = true,
                )
                ColorSwatchRow(TagPalette, color, onSelect = { if (it != null) color = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, color) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SubtaskEditor(subtasks: List<SubtaskDraft>, actions: TaskEditActions) {
    var newTitle by rememberSaveable { mutableStateOf("") }
    val add = {
        actions.onAddSubtask(newTitle)
        newTitle = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        subtasks.forEach { sub ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = sub.isDone, onCheckedChange = { actions.onToggleSubtask(sub.key) })
                OutlinedTextField(
                    value = sub.title,
                    onValueChange = { actions.onRenameSubtask(sub.key, it) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { actions.onRemoveSubtask(sub.key) }) {
                    Icon(Icons.Default.Close, stringResource(R.string.subtask_remove))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                placeholder = { Text(stringResource(R.string.subtask_add_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = add, enabled = newTitle.isNotBlank()) {
                Icon(Icons.Default.Add, stringResource(R.string.subtask_add))
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun TaskEditPreview() {
    AssistantTheme(dynamicColor = false) {
        TaskEditContent(
            state = TaskEditUiState(
                isNew = false,
                title = "Submit grant application",
                description = "Upload all documents",
                priority = Priority.HIGH,
                deadline = System.currentTimeMillis() + 86_400_000,
                repeat = RepeatOption.WEEKLY,
                tags = listOf(Tag(1, "work", "#3B82F6"), Tag(2, "home", "#10B981")),
                tagId = 1,
                subtasks = listOf(SubtaskDraft(1, "CV", true), SubtaskDraft(2, "Motivation letter", false)),
            ),
            actions = TaskEditActions(),
        )
    }
}
