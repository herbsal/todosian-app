package com.isotjs.todosian.ui.components

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.isotjs.todosian.R
import com.isotjs.todosian.data.model.TasksPriority
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TodoDates
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Width of the names in front of a row of choices ("Dauer", "Fällig"), so the rows line up. */
internal val MetaLabelWidth = 72.dp

/**
 * The Obsidian Tasks fields of a task, compact: the due date always in its own row with
 * "Today" and "Tomorrow", then one chip each for repeat, reminder and priority. Set values are
 * filled chips with ✕; a tap on an empty one opens its picker. Start and scheduled date sit
 * under "More" until set; created and done date show only when set (created is set by itself).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksMetaEditor(
    mode: TodoSheetMode?,
    meta: MarkdownParser.TasksMeta,
    onMetaChange: (MarkdownParser.TasksMeta) -> Unit,
    useEmojisInUi: Boolean,
    modifier: Modifier = Modifier,
) {
    val isAdd = mode is TodoSheetMode.Add
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val timeFormat = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
    var showRecurrence by remember { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val noTime = stringResource(R.string.category_tasks_due_no_time)

    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val dueDay = TodoDates.parseDate(meta.dueDate)
    val reminderAt = TodoDates.parseReminder(meta.reminder)
    // A ⏰ on the due day is the due time; a ⏰ on another day is a reminder of its own.
    val dueTime = reminderAt?.takeIf { dueDay != null && it.toLocalDate() == dueDay }?.toLocalTime()

    fun setDue(day: LocalDate, time: LocalTime?) {
        val reminder = when {
            time != null -> MarkdownParser.formatReminder(day.atTime(time))
            dueTime != null -> null
            else -> meta.reminder
        }
        onMetaChange(meta.copy(dueDate = day.toString(), reminder = reminder))
        if (time != null) askNotificationPermission()
    }

    fun clearDue() {
        onMetaChange(meta.copy(dueDate = null, reminder = if (dueTime != null) null else meta.reminder))
    }

    fun pickDue() {
        val initialDay = dueDay ?: today
        val initialTime = dueTime ?: LocalTime.of(9, 0)
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val day = LocalDate.of(year, month + 1, dayOfMonth)
                TimePickerDialog(
                    context,
                    { _, hour, minute -> setDue(day, LocalTime.of(hour, minute)) },
                    initialTime.hour,
                    initialTime.minute,
                    DateFormat.is24HourFormat(context),
                ).apply {
                    setButton(android.content.DialogInterface.BUTTON_NEUTRAL, noTime) { _, _ -> setDue(day, null) }
                }.show()
            },
            initialDay.year,
            initialDay.monthValue - 1,
            initialDay.dayOfMonth,
        ).show()
    }

    fun pickReminder() {
        val initial = reminderAt ?: LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        showDateTimePicker(context = context, initial = initial) { picked ->
            onMetaChange(meta.copy(reminder = MarkdownParser.formatReminder(picked)))
            askNotificationPermission()
        }
    }

    fun pickDate(value: String?, onPicked: (String) -> Unit) {
        showDatePicker(context = context, initial = TodoDates.parseDate(value) ?: today, onPicked = onPicked)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val dueLabel = stringResource(R.string.category_tasks_due_label)
        MetaRow(label = dueLabel) {
            if (dueDay != null) {
                val day = dayLabel(dueDay, today)
                SetMetaChip(
                    label = dueTime?.let { stringResource(R.string.date_with_time, day, it.format(timeFormat)) } ?: day,
                    emoji = "📅",
                    icon = Icons.Outlined.Event,
                    useEmoji = useEmojisInUi,
                    clearDescription = stringResource(R.string.category_tasks_remove_value, dueLabel),
                    onClick = ::pickDue,
                    onClear = ::clearDue,
                )
            } else {
                AddMetaChip(label = stringResource(R.string.date_today), onClick = { setDue(today, null) })
                AddMetaChip(label = stringResource(R.string.date_tomorrow), onClick = { setDue(today.plusDays(1), null) })
                AddMetaChip(
                    label = stringResource(R.string.action_set),
                    emoji = "📅",
                    icon = Icons.Outlined.Event,
                    useEmoji = useEmojisInUi,
                    onClick = ::pickDue,
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val recurrenceLabel = stringResource(R.string.category_tasks_recurrence_label)
            val recurrence = meta.recurrence
            if (recurrence != null) {
                SetMetaChip(
                    label = recurrenceSummary(recurrence),
                    emoji = "🔁",
                    icon = Icons.Outlined.Repeat,
                    useEmoji = useEmojisInUi,
                    clearDescription = stringResource(R.string.category_tasks_remove_value, recurrenceLabel),
                    onClick = { showRecurrence = true },
                    onClear = { onMetaChange(meta.copy(recurrence = null)) },
                )
            } else {
                AddMetaChip(recurrenceLabel, "🔁", Icons.Outlined.Repeat, useEmojisInUi) { showRecurrence = true }
            }

            // With a due time the ⏰ is that time; there is only one ⏰ per task.
            if (dueTime == null) {
                val reminderLabel = stringResource(R.string.category_tasks_reminder_label)
                if (reminderAt != null) {
                    SetMetaChip(
                        label = stringResource(R.string.date_with_time, dayLabel(reminderAt.toLocalDate(), today), reminderAt.toLocalTime().format(timeFormat)),
                        emoji = "⏰",
                        icon = Icons.Outlined.Alarm,
                        useEmoji = useEmojisInUi,
                        clearDescription = stringResource(R.string.category_tasks_remove_value, reminderLabel),
                        onClick = ::pickReminder,
                        onClear = { onMetaChange(meta.copy(reminder = null)) },
                    )
                } else {
                    AddMetaChip(reminderLabel, "⏰", Icons.Outlined.Alarm, useEmojisInUi, ::pickReminder)
                }
            }

            PriorityMetaChip(
                value = meta.priority,
                useEmojisInUi = useEmojisInUi,
                onChange = { onMetaChange(meta.copy(priority = it)) },
            )

            data class DateField(val label: Int, val emoji: String, val icon: ImageVector, val value: String?, val set: (String?) -> MarkdownParser.TasksMeta)
            val dates = listOfNotNull(
                DateField(R.string.category_tasks_start_label, "🛫", Icons.Outlined.FlightTakeoff, meta.startDate) { meta.copy(startDate = it) },
                DateField(R.string.category_tasks_scheduled_label, "⏳", Icons.Outlined.Schedule, meta.scheduledDate) { meta.copy(scheduledDate = it) },
                // Created is set by itself for a new task; done when checking it off.
                if (isAdd) null else DateField(R.string.category_tasks_created_label, "➕", Icons.Outlined.AddCircle, meta.createdDate) { meta.copy(createdDate = it) },
                if (isAdd) null else DateField(R.string.category_tasks_done_label, "✅", Icons.Outlined.CheckCircle, meta.completionDate) { meta.copy(completionDate = it) },
            )
            dates.filter { it.value != null }.forEach { field ->
                val label = stringResource(field.label)
                val day = TodoDates.parseDate(field.value)?.let { dayLabel(it, today) } ?: field.value.orEmpty()
                SetMetaChip(
                    label = "$label $day",
                    emoji = field.emoji,
                    icon = field.icon,
                    useEmoji = useEmojisInUi,
                    clearDescription = stringResource(R.string.category_tasks_remove_value, label),
                    onClick = { pickDate(field.value) { onMetaChange(field.set(it)) } },
                    onClear = { onMetaChange(field.set(null)) },
                )
            }
            val unset = dates.filter { it.value == null }
            if (unset.isNotEmpty()) {
                if (showMore) {
                    unset.forEach { field ->
                        AddMetaChip(stringResource(field.label), field.emoji, field.icon, useEmojisInUi) {
                            pickDate(null) { onMetaChange(field.set(it)) }
                        }
                    }
                }
                AddMetaChip(
                    label = if (showMore) "" else stringResource(R.string.category_tasks_more),
                    icon = if (showMore) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    onClick = { showMore = !showMore },
                )
            }
        }
    }

    if (showRecurrence) {
        RecurrencePickerDialog(
            initial = meta.recurrence,
            start = dueDay ?: TodoDates.parseDate(meta.scheduledDate) ?: TodoDates.parseDate(meta.startDate) ?: today,
            dueDate = meta.dueDate,
            scheduledDate = meta.scheduledDate,
            startDate = meta.startDate,
            onDismiss = { showRecurrence = false },
            onApply = { rule ->
                showRecurrence = false
                onMetaChange(meta.copy(recurrence = rule))
            },
        )
    }
}

/** A name in front of a row of choices that scrolls sideways when it doesn't fit. */
@Composable
internal fun MetaRow(label: String?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(MetaLabelWidth),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        ) { content() }
    }
}

@Composable
private fun MetaSymbol(emoji: String?, icon: ImageVector?, useEmoji: Boolean) {
    when {
        useEmoji && emoji != null -> Text(text = emoji, style = MaterialTheme.typography.bodyMedium)
        icon != null -> Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize))
        emoji != null -> Text(text = emoji, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A set value: filled, a tap changes it, ✕ removes it. */
@Composable
private fun SetMetaChip(
    label: String,
    emoji: String?,
    icon: ImageVector?,
    useEmoji: Boolean,
    clearDescription: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    InputChip(
        selected = true,
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { MetaSymbol(emoji, icon, useEmoji) },
        trailingIcon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(role = Role.Button, onClickLabel = clearDescription, onClick = onClear),
            ) {
                Icon(Icons.Filled.Close, contentDescription = clearDescription, modifier = Modifier.size(InputChipDefaults.IconSize))
            }
        },
    )
}

/** A value not set yet: an outlined chip that opens its picker. */
@Composable
private fun AddMetaChip(
    label: String,
    emoji: String? = null,
    icon: ImageVector? = null,
    useEmoji: Boolean = true,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { if (label.isNotEmpty()) Text(text = label, maxLines = 1) },
        leadingIcon = if (emoji == null && icon == null) null else {
            { MetaSymbol(emoji, icon, useEmoji) }
        },
    )
}

@Composable
private fun PriorityMetaChip(value: TasksPriority?, useEmojisInUi: Boolean, onChange: (TasksPriority?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choices = listOf(
        TasksPriority.HIGHEST to (R.string.category_tasks_priority_highest to R.string.category_tasks_priority_highest_label),
        TasksPriority.HIGH to (R.string.category_tasks_priority_high to R.string.category_tasks_priority_high_label),
        TasksPriority.MEDIUM to (R.string.category_tasks_priority_medium to R.string.category_tasks_priority_medium_label),
        TasksPriority.LOW to (R.string.category_tasks_priority_low to R.string.category_tasks_priority_low_label),
        TasksPriority.LOWEST to (R.string.category_tasks_priority_lowest to R.string.category_tasks_priority_lowest_label),
    )
    // The emoji strings carry the Obsidian Tasks symbol ("⏫ Hoch"); the others get a colored flag.
    fun label(pair: Pair<Int, Int>) = if (useEmojisInUi) pair.first else pair.second
    Box {
        val chosen = choices.firstOrNull { it.first == value }
        val name = stringResource(R.string.category_tasks_priority)
        if (chosen != null) {
            SetMetaChip(
                label = stringResource(label(chosen.second)),
                emoji = null,
                icon = if (useEmojisInUi) null else Icons.Outlined.Flag,
                useEmoji = useEmojisInUi,
                clearDescription = stringResource(R.string.category_tasks_remove_value, name),
                onClick = { open = true },
                onClear = { onChange(null) },
            )
        } else {
            AddMetaChip(label = name, icon = Icons.Outlined.Flag, useEmoji = false, onClick = { open = true })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (priority, labels) ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(label(labels))) },
                    leadingIcon = if (useEmojisInUi) null else {
                        { Icon(Icons.Outlined.Flag, contentDescription = null, tint = priorityColorFor(priority) ?: MaterialTheme.colorScheme.onSurface) }
                    },
                    onClick = {
                        open = false
                        onChange(priority)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.category_tasks_priority_none)) },
                onClick = {
                    open = false
                    onChange(null)
                },
            )
        }
    }
}
