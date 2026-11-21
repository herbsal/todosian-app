package com.isotjs.todosian.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.AppSettings
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagDisplaySettings

/** A task with the list it belongs to, for views across lists (lists tab, agenda, daily focus). */
data class TaskItem(val todo: Todo, val listUri: Uri, val listName: String)

/** The heading of a section of tasks: a list, a day or an agenda bucket. */
@Composable
fun TaskSectionHeader(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One task of a section, drawn the same in every view: the rows of a section form one rounded
 * block. With [showListName] the list's name is written below, for sections that mix lists.
 * [onDelete] null: swiping does nothing.
 */
@Composable
fun GroupedTaskRow(
    item: TaskItem,
    index: Int,
    count: Int,
    showListName: Boolean,
    settings: AppSettings,
    tagDefinitions: TagDefinitions,
    tagDisplay: TagDisplaySettings,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(groupedRowShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        TodoRow(
            todo = item.todo,
            enableTasksPluginSupport = settings.enableTasksPluginSupport,
            useEmojisInUi = settings.tasksPluginUseEmojisInUi,
            onToggle = onToggle,
            onEdit = onEdit,
            onAddSubtask = {},
            onRequestDelete = onDelete ?: {},
            onRequestMove = null,
            allowMove = false,
            showSubtaskButton = false,
            tagDefinitions = tagDefinitions,
            tagDisplay = tagDisplay,
            shape = RectangleShape,
            showDivider = index > 0,
        )
        if (showListName) {
            Text(
                text = item.listName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp, bottom = 6.dp),
            )
        }
    }
}

/** The rows of one section; [section] keeps the keys apart when a task shows in two sections. */
fun LazyListScope.groupedTaskRows(
    section: String,
    items: List<TaskItem>,
    showListName: Boolean,
    settings: AppSettings,
    tagDefinitionsOf: (Uri) -> TagDefinitions,
    tagDisplay: TagDisplaySettings,
    onToggle: (TaskItem) -> Unit,
    onEdit: (TaskItem) -> Unit,
    onDelete: ((TaskItem) -> Unit)? = null,
    extraRows: Int = 0,
) {
    itemsIndexed(items, key = { _, t -> "$section:${t.listUri}:${t.todo.lineIndex}:${t.todo.text}" }) { index, item ->
        GroupedTaskRow(
            item = item,
            index = index,
            count = items.size + extraRows,
            showListName = showListName,
            settings = settings,
            tagDefinitions = tagDefinitionsOf(item.listUri),
            tagDisplay = tagDisplay,
            onToggle = { onToggle(item) },
            onEdit = { onEdit(item) },
            onDelete = onDelete?.let { delete -> { delete(item) } },
            modifier = Modifier.animateItem(),
        )
    }
}
