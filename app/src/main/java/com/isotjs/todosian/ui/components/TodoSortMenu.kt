package com.isotjs.todosian.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.isotjs.todosian.R
import com.isotjs.todosian.data.settings.TodoSort

/**
 * Sort button for a task list's top bar. Besides the fixed orders it offers one entry per
 * keyword group (e.g. "Effort", "Energy", "Person"), which sorts by the keyword order in the note.
 */
@Composable
fun TodoSortMenu(
    sort: TodoSort,
    sortGroup: String,
    groupNames: List<String>,
    onSelect: (TodoSort, String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.settings_todo_sort),
                tint = if (sort == TodoSort.FILE_ORDER) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val fixed = listOf(
                TodoSort.FILE_ORDER to R.string.settings_todo_sort_file_order,
                TodoSort.DUE_DATE_EARLIEST_FIRST to R.string.settings_todo_sort_due_earliest,
                TodoSort.REMINDER_EARLIEST_FIRST to R.string.settings_todo_sort_reminder_earliest,
                TodoSort.PRIORITY_HIGH_TO_LOW to R.string.settings_todo_sort_priority_desc,
                TodoSort.CREATED_DATE_NEWEST_FIRST to R.string.settings_todo_sort_created_newest,
                TodoSort.TITLE_A_Z to R.string.settings_todo_sort_title,
            )
            fixed.forEach { (option, label) ->
                SortMenuItem(
                    label = stringResource(label),
                    selected = sort == option,
                    onClick = {
                        expanded = false
                        onSelect(option, null)
                    },
                )
            }
            if (groupNames.isNotEmpty()) {
                HorizontalDivider()
                groupNames.forEach { name ->
                    SortMenuItem(
                        label = stringResource(R.string.settings_todo_sort_group, name),
                        selected = sort == TodoSort.TAG_GROUP && sortGroup.equals(name, ignoreCase = true),
                        onClick = {
                            expanded = false
                            onSelect(TodoSort.TAG_GROUP, name)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
    )
}
