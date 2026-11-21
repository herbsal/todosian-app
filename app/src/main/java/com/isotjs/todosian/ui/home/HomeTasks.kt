package com.isotjs.todosian.ui.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.isotjs.todosian.utils.SavedView
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.AppSettings
import com.isotjs.todosian.ui.components.TodoRow
import com.isotjs.todosian.ui.components.groupedRowShape
import com.isotjs.todosian.utils.AgendaBucket
import com.isotjs.todosian.utils.CalendarAgenda
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagDisplaySettings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** A task with the list it belongs to. */
internal data class HomeTask(val category: Category, val todo: Todo)

private fun Category.shortName(): String = displayName.substringAfterLast('/')

/** The first open tasks of a list: dated ones first (earliest first), then the rest in file order. */
internal fun previewTasks(category: Category, count: Int): List<Todo> {
    val topLevel = category.openTodos.filter { it.indentLevel == 0 }
    val (dated, undated) = topLevel.partition { CalendarAgenda.dateOf(it) != null }
    return (dated.sortedBy { CalendarAgenda.dateOf(it) } + undated).take(count)
}

/**
 * Tab "Lists" with tasks instead of tiles: per list a header with the number of open tasks,
 * the first [AppSettings.homeTasksPerList] open tasks and "+ n more", which opens the list.
 * Tapping the header folds the list.
 */
@Composable
internal fun HomeTaskSections(
    categories: List<Category>,
    settings: AppSettings,
    tagDefinitions: Map<Uri, TagDefinitions>,
    tagDisplay: TagDisplaySettings,
    onToggleCollapse: (Category) -> Unit,
    onOpenCategory: (Uri) -> Unit,
    onToggleTodo: (Category, Todo) -> Unit,
    header: LazyListScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        header()
        categories.forEach { category ->
            val key = category.uri.toString()
            val collapsed = key in settings.homeCollapsed
            val open = category.todoCount - category.doneCount
            item(key = "header:$key") {
                ListHeader(
                    name = category.shortName(),
                    open = open,
                    overdue = category.overdueCount,
                    collapsed = collapsed,
                    onToggle = { onToggleCollapse(category) },
                    onOpen = { onOpenCategory(category.uri) },
                )
            }
            if (!collapsed) {
                val preview = previewTasks(category, settings.homeTasksPerList)
                val more = category.openTodos.count { it.indentLevel == 0 } - preview.size
                val rows = preview.size + if (more > 0) 1 else 0
                if (preview.isEmpty()) {
                    item(key = "empty:$key") {
                        Text(
                            text = stringResource(R.string.home_list_all_done),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                itemsIndexed(preview, key = { _, todo -> "todo:$key:${todo.lineIndex}:${todo.text}" }) { index, todo ->
                    HomeTodoRow(
                        todo = todo,
                        settings = settings,
                        tagDefinitions = tagDefinitions[category.uri] ?: TagDefinitions.EMPTY,
                        tagDisplay = tagDisplay,
                        index = index,
                        count = rows,
                        onToggle = { onToggleTodo(category, todo) },
                        onOpen = { onOpenCategory(category.uri) },
                    )
                }
                if (more > 0) {
                    item(key = "more:$key") {
                        MoreRow(more = more, onClick = { onOpenCategory(category.uri) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ListHeader(name: String, open: Int, overdue: Int, collapsed: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onOpen),
        )
        Text(
            text = " · $open",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (overdue > 0) {
            Text(
                text = "  " + pluralStringResource(R.plurals.home_list_overdue, overdue, overdue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = stringResource(if (collapsed) R.string.home_list_expand else R.string.home_list_collapse),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeTodoRow(
    todo: Todo,
    settings: AppSettings,
    tagDefinitions: TagDefinitions,
    tagDisplay: TagDisplaySettings,
    index: Int,
    count: Int,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    TodoRow(
        todo = todo,
        enableTasksPluginSupport = settings.enableTasksPluginSupport,
        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
        onToggle = onToggle,
        onEdit = onOpen,
        onAddSubtask = {},
        onRequestDelete = {},
        onRequestMove = null,
        showSubtaskButton = false,
        tagDefinitions = tagDefinitions,
        tagDisplay = tagDisplay,
        shape = groupedRowShape(index, count),
        showDivider = index > 0,
    )
}

@Composable
private fun MoreRow(more: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .clickable(onClick = onClick)
            .padding(start = 48.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Text(
            text = pluralStringResource(R.plurals.home_list_more, more, more),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private enum class CalendarMode { MONTH, AGENDA }

/** Tab "Calendar": a month with dots on days with tasks, or an agenda across all lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarTab(
    categories: List<Category>,
    settings: AppSettings,
    tagDefinitions: Map<Uri, TagDefinitions>,
    tagDisplay: TagDisplaySettings,
    onOpenCategory: (Uri) -> Unit,
    onToggleTodo: (Category, Todo) -> Unit,
    modifier: Modifier = Modifier,
    savedViews: List<SavedView> = emptyList(),
) {
    var mode by rememberSaveable { mutableStateOf(CalendarMode.MONTH) }
    val today = LocalDate.now()
    var month by rememberSaveable { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    // The filter chips of the lists tab filter the calendar in place: one view at a time.
    var viewKey by rememberSaveable { mutableStateOf<String?>(null) }
    val activeView = savedViews.firstOrNull { viewKeyOf(it) == viewKey }
    val tasks = remember(categories, activeView) {
        categories.flatMap { c -> c.openTodos.map { HomeTask(c, it) } }
            .filter { task -> activeView == null || activeView.matches(task.todo.text) }
    }
    val byDate = remember(tasks) {
        tasks.mapNotNull { task -> CalendarAgenda.dateOf(task.todo)?.let { it to task } }.groupBy({ it.first }, { it.second })
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (savedViews.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                items(savedViews, key = { viewKeyOf(it) }) { view ->
                    val isActive = viewKeyOf(view) == viewKey
                    FilterChip(
                        selected = isActive,
                        onClick = { viewKey = if (isActive) null else viewKeyOf(view) },
                        label = { Text(text = view.name) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isActive) Icons.Filled.Check else Icons.Filled.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            CalendarMode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = mode == entry,
                    onClick = { mode = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, CalendarMode.entries.size),
                ) {
                    Text(text = stringResource(if (entry == CalendarMode.MONTH) R.string.calendar_month else R.string.calendar_agenda))
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
            if (mode == CalendarMode.MONTH) {
                val shownMonth = LocalDate.parse(month)
                val selectedDay = LocalDate.parse(selected)
                item(key = "month") {
                    MonthGrid(
                        month = shownMonth,
                        today = today,
                        selected = selectedDay,
                        byDate = byDate,
                        onSelect = { selected = it.toString() },
                        onMonthChange = { month = it.toString() },
                    )
                }
                val dayTasks = byDate[selectedDay].orEmpty()
                item(key = "day-header") {
                    CalendarHeader(text = dayTitle(selectedDay, today) + " · ${dayTasks.size}", color = MaterialTheme.colorScheme.primary)
                }
                if (dayTasks.isEmpty()) {
                    item(key = "day-empty") {
                        Text(
                            text = stringResource(R.string.calendar_day_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                taskRows("day", dayTasks, settings, tagDefinitions, tagDisplay, onOpenCategory, onToggleTodo)
                val overdue = byDate.filterKeys { it < today }.toSortedMap().values.flatten()
                if (selectedDay == today && overdue.isNotEmpty()) {
                    item(key = "overdue-header") {
                        CalendarHeader(text = stringResource(R.string.calendar_overdue) + " · ${overdue.size}", color = MaterialTheme.colorScheme.error)
                    }
                    taskRows("overdue", overdue, settings, tagDefinitions, tagDisplay, onOpenCategory, onToggleTodo)
                }
            } else {
                val agenda = CalendarAgenda.agenda(tasks, today) { it.todo }
                if (agenda.isEmpty()) {
                    item(key = "agenda-empty") {
                        Text(
                            text = stringResource(R.string.calendar_agenda_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                agenda.forEach { (bucket, items) ->
                    item(key = "bucket:$bucket") {
                        CalendarHeader(
                            text = stringResource(bucketLabel(bucket)) + " · ${items.size}",
                            color = if (bucket == AgendaBucket.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    taskRows(bucket.name, items, settings, tagDefinitions, tagDisplay, onOpenCategory, onToggleTodo)
                }
            }
        }
    }
}

private fun bucketLabel(bucket: AgendaBucket): Int = when (bucket) {
    AgendaBucket.OVERDUE -> R.string.calendar_overdue
    AgendaBucket.TODAY -> R.string.calendar_today
    AgendaBucket.TOMORROW -> R.string.calendar_tomorrow
    AgendaBucket.THIS_WEEK -> R.string.calendar_this_week
    AgendaBucket.LATER -> R.string.calendar_later
}

private fun LazyListScope.taskRows(
    section: String,
    tasks: List<HomeTask>,
    settings: AppSettings,
    tagDefinitions: Map<Uri, TagDefinitions>,
    tagDisplay: TagDisplaySettings,
    onOpenCategory: (Uri) -> Unit,
    onToggleTodo: (Category, Todo) -> Unit,
) {
    itemsIndexed(tasks, key = { _, t -> "$section:${t.category.uri}:${t.todo.lineIndex}:${t.todo.text}" }) { index, task ->
        val shape = groupedRowShape(index, tasks.size)
        // One surface per task with the list name below, so tasks from different lists can be told apart.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            TodoRow(
                todo = task.todo,
                enableTasksPluginSupport = settings.enableTasksPluginSupport,
                useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                onToggle = { onToggleTodo(task.category, task.todo) },
                onEdit = { onOpenCategory(task.category.uri) },
                onAddSubtask = {},
                onRequestDelete = {},
                onRequestMove = null,
                showSubtaskButton = false,
                tagDefinitions = tagDefinitions[task.category.uri] ?: TagDefinitions.EMPTY,
                tagDisplay = tagDisplay,
                shape = RectangleShape,
                showDivider = index > 0,
            )
            Text(
                text = task.category.shortName(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun CalendarHeader(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun dayTitle(day: LocalDate, today: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val date = day.format(DateTimeFormatter.ofPattern("EEE d. MMM", locale))
    return when (day) {
        today -> stringResource(R.string.calendar_today_with_date, date)
        today.plusDays(1) -> stringResource(R.string.calendar_tomorrow_with_date, date)
        else -> date
    }
}

@Composable
private fun MonthGrid(
    month: LocalDate,
    today: LocalDate,
    selected: LocalDate,
    byDate: Map<LocalDate, List<HomeTask>>,
    onSelect: (LocalDate) -> Unit,
    onMonthChange: (LocalDate) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val days = remember(month) { CalendarAgenda.monthGrid(month) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.calendar_previous_month))
            }
            Text(
                text = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onMonthChange(today.withDayOfMonth(1)); onSelect(today) },
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.calendar_next_month))
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { dow ->
                Text(
                    text = dow.getDisplayName(TextStyle.SHORT_STANDALONE, locale).take(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = day.month == month.month,
                        isToday = day == today,
                        isSelected = day == selected,
                        tasks = byDate[day].orEmpty(),
                        overdue = day < today,
                        onClick = { onSelect(day) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    tasks: List<HomeTask>,
    overdue: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        isToday -> colors.primary
        isSelected -> colors.secondaryContainer
        else -> Color.Transparent
    }
    val textColor = when {
        isToday -> colors.onPrimary
        !inMonth -> colors.onSurface.copy(alpha = 0.35f)
        else -> colors.onSurface
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(top = 6.dp),
    ) {
        Text(text = day.dayOfMonth.toString(), style = MaterialTheme.typography.bodySmall, color = textColor)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 3.dp)) {
            tasks.take(3).forEach { _ ->
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(
                            color = when {
                                isToday -> colors.onPrimary
                                overdue -> colors.error
                                else -> colors.primary
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

private fun viewKeyOf(view: SavedView): String = "${view.folder}\u001F${view.name}"
