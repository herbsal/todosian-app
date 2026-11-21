package com.isotjs.todosian.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.undo.UndoEntry
import com.isotjs.todosian.data.undo.UndoHistory
import com.isotjs.todosian.data.undo.UndoResult
import com.isotjs.todosian.ui.components.shortLabel
import com.isotjs.todosian.ui.components.undoMessage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The last actions on tasks, newest first, each with "Undo"; actions taken back with "Redo".
 * An action is only written back while its notes are still as it left them.
 */
@Composable
internal fun UndoHistoryDialog(fileRepository: FileRepository, onDismiss: () -> Unit) {
    val entries by UndoHistory.entries.collectAsStateWithLifecycle()
    val (undo, redo) = entries
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<Int?>(null) }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    fun apply(entry: UndoEntry, forward: Boolean) {
        scope.launch {
            when (fileRepository.applyUndo(entry, forward)) {
                UndoResult.DONE -> {
                    message = null
                    if (forward) UndoHistory.redone(entry) else UndoHistory.undone(entry)
                }
                UndoResult.CHANGED -> message = R.string.undo_changed
                UndoResult.FAILED -> message = R.string.undo_failed
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.undo_history_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                message?.let {
                    Text(text = stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (undo.isEmpty() && redo.isEmpty()) {
                    Text(text = stringResource(R.string.undo_history_empty))
                }
                undo.forEach { entry ->
                    ListItem(
                        headlineContent = { Text(text = stringResource(undoMessage(entry.kind), shortLabel(entry.label))) },
                        supportingContent = { Text(text = timeFormat.format(Date(entry.at))) },
                        trailingContent = {
                            TextButton(onClick = { apply(entry, forward = false) }) { Text(text = stringResource(R.string.undo_action)) }
                        },
                    )
                }
                redo.forEach { entry ->
                    ListItem(
                        headlineContent = { Text(text = stringResource(undoMessage(entry.kind), shortLabel(entry.label))) },
                        supportingContent = { Text(text = stringResource(R.string.undo_history_undone)) },
                        trailingContent = {
                            TextButton(onClick = { apply(entry, forward = true) }) { Text(text = stringResource(R.string.redo_action)) }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_ok)) }
        },
    )
}
