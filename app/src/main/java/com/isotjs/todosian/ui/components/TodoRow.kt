package com.isotjs.todosian.ui.components

import com.isotjs.todosian.utils.TagDisplaySettings
import com.isotjs.todosian.utils.TagDisplay
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagBadge
import androidx.compose.runtime.remember
import androidx.compose.material3.Surface
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.isotjs.todosian.utils.DateUrgency
import com.isotjs.todosian.utils.RelativeDay
import com.isotjs.todosian.utils.TodoDates
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.isotjs.todosian.R
import com.isotjs.todosian.data.model.TasksPriority
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.utils.MarkdownParser
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION") // dismissState this little fella causes problems with the new Material3 API, so we have to use the deprecated one for now
@Composable
fun TodoRow(
    todo: Todo,
    enableTasksPluginSupport: Boolean,
    useEmojisInUi: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onAddSubtask: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestMove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    allowMove: Boolean = onRequestMove != null,
    showSubtaskButton: Boolean = true,
    tagDefinitions: TagDefinitions = TagDefinitions.EMPTY,
    tagDisplay: TagDisplaySettings = TagDisplaySettings(),
    shape: Shape = MaterialTheme.shapes.large,
    showDivider: Boolean = false,
) {
    // Keywords appear below the title instead of #tags in the text; people as initials on the right.
    val display = remember(todo.text, tagDefinitions, tagDisplay) { TagDisplay.split(todo.text, tagDefinitions, tagDisplay) }
    val people = remember(display) { display.badges.filter { it.isPerson } }
    val otherBadges = remember(display) { display.badges.filterNot { it.isPerson } }
    val indentPadding = (todo.indentLevel * 16).coerceAtMost(48).dp
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> onRequestDelete()
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (todo.indentLevel == 0) {
                        onRequestMove?.invoke()
                    }
                }
                else -> Unit
            }
            false
        },
    )

    SwipeToDismissBox(
        modifier = modifier,
        state = dismissState,
        enableDismissFromStartToEnd = allowMove && todo.indentLevel == 0,
        backgroundContent = {
            TodoSwipeBackground(
                dismissState = dismissState,
                shape = shape,
            )
        },
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape)
                    .clip(shape),
            ) {
                if (showDivider) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(start = 56.dp + indentPadding),
                    )
                }
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEdit)
                        .padding(start = 6.dp + indentPadding, end = 8.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    RoundCheckbox(
                        checked = todo.isDone,
                        onToggle = onToggle,
                        accent = priorityColorFor(todo.priority),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
                    ) {
                        val targetColor = if (todo.isDone) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        val textColor by animateColorAsState(
                            targetValue = targetColor,
                            animationSpec = tween(300),
                            label = "todo-text-color",
                        )
                        Text(
                            text = display.title,
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (todo.isDone) TextDecoration.LineThrough else TextDecoration.None,
                            color = textColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val metaItems = buildMetaItems(
                            todo = todo,
                            enableTasksPluginSupport = enableTasksPluginSupport,
                            useEmojisInUi = useEmojisInUi,
                            badges = otherBadges,
                        )
                        if (metaItems.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            TodoMetaLine(items = metaItems, muted = todo.isDone)
                        }
                    }
                    if (people.isNotEmpty()) {
                        PersonAvatars(
                            people = people,
                            modifier = Modifier.padding(start = 8.dp, top = 9.dp),
                        )
                    }
                    if (showSubtaskButton && todo.indentLevel < 2) {
                        IconButton(
                            onClick = onAddSubtask,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SubdirectoryArrowRight,
                                contentDescription = stringResource(R.string.cd_add_subtask),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Shape of a row inside one rounded list surface: only the first and last row round their outer corners. */
fun groupedRowShape(index: Int, count: Int, radius: Dp = 16.dp): Shape = when {
    count <= 1 -> RoundedCornerShape(radius)
    index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
    index == count - 1 -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    else -> RectangleShape
}

/** A round check circle like in most todo apps; its ring takes the priority colour. */
@Composable
private fun RoundCheckbox(
    checked: Boolean,
    onToggle: () -> Unit,
    accent: Color?,
) {
    val ringColor = accent ?: MaterialTheme.colorScheme.outline
    val fillColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(200),
        label = "check-fill",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .background(color = fillColor, shape = CircleShape)
                .border(
                    width = 2.dp,
                    color = if (checked) MaterialTheme.colorScheme.primary else ringColor,
                    shape = CircleShape,
                ),
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

internal enum class MetaTone { NORMAL, URGENT, SOON }

/** One small item of the line below the title, e.g. a calendar icon with "Morgen". */
internal data class TodoMetaItem(
    val text: String,
    val icon: ImageVector? = null,
    val emoji: String? = null,
    val tone: MetaTone = MetaTone.NORMAL,
    val contentDescription: String? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodoMetaLine(items: List<TodoMetaItem>, muted: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            val color = when {
                muted -> MaterialTheme.colorScheme.onSurfaceVariant
                item.tone == MetaTone.URGENT -> MaterialTheme.colorScheme.error
                item.tone == MetaTone.SOON -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                when {
                    item.icon != null -> Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDescription,
                        tint = color,
                        modifier = Modifier.size(14.dp),
                    )
                    item.emoji != null -> Text(
                        text = item.emoji,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (item.text.isNotEmpty()) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** People of a task as overlapping initials; more than three show as "+n". */
@Composable
private fun PersonAvatars(people: List<TagBadge>, modifier: Modifier = Modifier) {
    val names = people.joinToString(", ") { it.label }
    val description = stringResource(R.string.cd_assigned_people, names)
    val palette = listOf(
        MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary,
        MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary,
        MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary,
    )
    val shown = if (people.size > 3) people.take(2) else people
    val extra = people.size - shown.size
    Row(
        horizontalArrangement = Arrangement.spacedBy((-6).dp),
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        shown.forEach { person ->
            val (container, content) = palette[Math.floorMod(person.label.lowercase().hashCode(), palette.size)]
            AvatarCircle(text = initialsOf(person.label), container = container, content = content)
        }
        if (extra > 0) {
            AvatarCircle(
                text = "+$extra",
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AvatarCircle(text: String, container: Color, content: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .border(width = 1.5.dp, color = MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape)
            .padding(1.dp)
            .background(color = container, shape = CircleShape),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
        )
    }
}

/** "Michael" → "M", "Anna Lena" → "AL". */
internal fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("""[\s_\-/]+""")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    return words.take(2).joinToString("") { word -> word.first().uppercaseChar().toString() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoSwipeBackground(
    dismissState: SwipeToDismissBoxState,
    shape: Shape,
) {
    val direction = dismissState.dismissDirection
    val targetBackgroundColor = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val backgroundColor by animateColorAsState(
        targetValue = targetBackgroundColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "swipe-bg-color",
    )
    val icon = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Delete
        SwipeToDismissBoxValue.StartToEnd -> Icons.AutoMirrored.Outlined.ArrowForward
        else -> Icons.Filled.Delete
    }
    val targetContentColor = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "swipe-content-color",
    )
    val alignment = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        else -> Alignment.CenterEnd
    }
    val isThresholdReached = dismissState.targetValue != SwipeToDismissBoxValue.Settled
    val iconScale by animateFloatAsState(
        targetValue = if (isThresholdReached) 1.25f else 0.85f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "swipe-icon-scale",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = backgroundColor,
                shape = shape,
            )
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                stringResource(R.string.category_move_title)
            } else {
                stringResource(R.string.cd_delete)
            },
            tint = contentColor,
            modifier = Modifier.graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
            },
        )
    }
}

sealed interface TodoSheetMode {
    data object Add : TodoSheetMode
    data class Edit(val todo: Todo) : TodoSheetMode
    data object AddSubtask : TodoSheetMode
}

internal fun showDatePicker(
    context: android.content.Context,
    initial: LocalDate,
    onPicked: (String) -> Unit,
) {
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val picked = LocalDate.of(year, month + 1, dayOfMonth)
            onPicked(picked.toString())
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

internal fun showDateTimePicker(
    context: android.content.Context,
    initial: LocalDateTime,
    onPicked: (LocalDateTime) -> Unit,
) {
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onPicked(LocalDateTime.of(year, month + 1, dayOfMonth, hour, minute))
                },
                initial.hour,
                initial.minute,
                DateFormat.is24HourFormat(context),
            ).show()
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

@Composable
internal fun priorityColorFor(priority: TasksPriority?): Color? {
    return when (priority) {
        TasksPriority.LOWEST -> colorResource(R.color.priority_lowest)
        TasksPriority.LOW -> colorResource(R.color.priority_low)
        TasksPriority.MEDIUM -> colorResource(R.color.priority_medium)
        TasksPriority.HIGH -> colorResource(R.color.priority_high)
        TasksPriority.HIGHEST -> colorResource(R.color.priority_highest)
        TasksPriority.NONE, null -> null
    }
}

@Composable
internal fun dayLabel(date: LocalDate, today: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (TodoDates.relativeDay(date, today)) {
        RelativeDay.TODAY -> stringResource(R.string.date_today)
        RelativeDay.TOMORROW -> stringResource(R.string.date_tomorrow)
        RelativeDay.YESTERDAY -> stringResource(R.string.date_yesterday)
        RelativeDay.OTHER -> {
            val skeleton = if (date.year == today.year) "EEEMMMd" else "MMMdyyyy"
            val pattern = remember(locale, skeleton) { DateFormat.getBestDateTimePattern(locale, skeleton) }
            date.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
    }
}

private fun toneFor(date: LocalDate, today: LocalDate): MetaTone = when (TodoDates.urgency(date, today)) {
    DateUrgency.OVERDUE, DateUrgency.TODAY -> MetaTone.URGENT
    DateUrgency.SOON -> MetaTone.SOON
    DateUrgency.LATER -> MetaTone.NORMAL
}

/**
 * The compact line below a task title: due date, reminder, scheduled and start date as short
 * relative dates, recurrence as an icon, then the keywords. Priority shows on the check circle.
 */
@Composable
internal fun buildMetaItems(
    todo: Todo,
    enableTasksPluginSupport: Boolean,
    useEmojisInUi: Boolean,
    badges: List<TagBadge>,
): List<TodoMetaItem> {
    val items = ArrayList<TodoMetaItem>(6)
    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]

    if (enableTasksPluginSupport) {
        val reminderAt = TodoDates.parseReminder(todo.reminder)
        val dueDay = TodoDates.parseDate(todo.dueDate)
        // A ⏰ on the due day is the due time: one item "Today, 14:00" instead of two.
        val dueTime = reminderAt?.takeIf { it.toLocalDate() == dueDay }?.toLocalTime()
            ?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        dueDay?.let { date ->
            items.add(
                TodoMetaItem(
                    text = if (dueTime != null) stringResource(R.string.date_with_time, dayLabel(date, today), dueTime) else dayLabel(date, today),
                    icon = if (useEmojisInUi) null else Icons.Outlined.Event,
                    emoji = if (useEmojisInUi) "📅" else null,
                    tone = toneFor(date, today),
                    contentDescription = stringResource(R.string.category_tasks_due_label),
                ),
            )
        } ?: todo.dueDate?.let { raw -> items.add(TodoMetaItem(text = raw, icon = Icons.Outlined.Event)) }

        reminderAt?.takeIf { dueTime == null }?.let { at ->
            val time = at.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
            items.add(
                TodoMetaItem(
                    text = stringResource(R.string.date_with_time, dayLabel(at.toLocalDate(), today), time),
                    icon = if (useEmojisInUi) null else Icons.Outlined.Alarm,
                    emoji = if (useEmojisInUi) "⏰" else null,
                    tone = toneFor(at.toLocalDate(), today),
                    contentDescription = stringResource(R.string.category_tasks_reminder_label),
                ),
            )
        }

        TodoDates.parseDate(todo.scheduledDate)?.let { date ->
            items.add(
                TodoMetaItem(
                    text = dayLabel(date, today),
                    icon = if (useEmojisInUi) null else Icons.Outlined.Schedule,
                    emoji = if (useEmojisInUi) "⏳" else null,
                    contentDescription = stringResource(R.string.category_tasks_scheduled_label),
                ),
            )
        }

        TodoDates.parseDate(todo.startDate)?.takeIf { it.isAfter(today) }?.let { date ->
            items.add(
                TodoMetaItem(
                    text = dayLabel(date, today),
                    icon = if (useEmojisInUi) null else Icons.Outlined.FlightTakeoff,
                    emoji = if (useEmojisInUi) "🛫" else null,
                    contentDescription = stringResource(R.string.category_tasks_start_label),
                ),
            )
        }

        if (!todo.recurrence.isNullOrBlank()) {
            items.add(
                TodoMetaItem(
                    text = "",
                    icon = if (useEmojisInUi) null else Icons.Outlined.Repeat,
                    emoji = if (useEmojisInUi) "🔁" else null,
                    contentDescription = stringResource(R.string.category_tasks_chip_recurrence_label, todo.recurrence),
                ),
            )
        }
    }

    badges.forEach { badge -> items.add(TodoMetaItem(text = badge.label, emoji = badge.icon)) }
    return items
}
