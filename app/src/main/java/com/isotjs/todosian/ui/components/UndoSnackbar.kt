package com.isotjs.todosian.ui.components

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.undo.UndoEntry
import com.isotjs.todosian.data.undo.UndoHistory
import com.isotjs.todosian.data.undo.UndoKind
import com.isotjs.todosian.data.undo.UndoResult
import kotlinx.coroutines.flow.collectLatest

/**
 * After each action on a task (check, edit, delete, move) a message with "Undo" for about ten
 * seconds; after undoing, one with "Redo". Only one screen at a time shows it: the one whose
 * snackbar host is passed. [onApplied] runs after the notes were written back.
 */
@Composable
fun UndoSnackbarEffect(snackbarHostState: SnackbarHostState, fileRepository: FileRepository, onApplied: () -> Unit) {
    val resources = LocalResources.current
    val currentOnApplied by rememberUpdatedState(onApplied)
    LaunchedEffect(snackbarHostState, fileRepository) {
        // A newer action replaces the message of the one before.
        UndoHistory.recorded.collectLatest { entry ->
            offerTaskUndo(entry, snackbarHostState, fileRepository, resources) { currentOnApplied() }
        }
    }
}

/** Shows "Undo" for [entry], and "Redo" once it was taken back. */
private suspend fun offerTaskUndo(
    entry: UndoEntry,
    snackbarHostState: SnackbarHostState,
    fileRepository: FileRepository,
    resources: Resources,
    onApplied: () -> Unit,
) {
    val label = shortLabel(entry.label)
    val undo = snackbarHostState.showSnackbar(
        message = resources.getString(undoMessage(entry.kind), label),
        actionLabel = resources.getString(R.string.undo_action),
        duration = SnackbarDuration.Long,
    )
    if (undo != SnackbarResult.ActionPerformed) return
    if (!report(fileRepository.applyUndo(entry, forward = false), snackbarHostState, resources)) return
    UndoHistory.undone(entry)
    onApplied()
    val redo = snackbarHostState.showSnackbar(
        message = resources.getString(R.string.undo_done, label),
        actionLabel = resources.getString(R.string.redo_action),
        duration = SnackbarDuration.Long,
    )
    if (redo != SnackbarResult.ActionPerformed) return
    if (!report(fileRepository.applyUndo(entry, forward = true), snackbarHostState, resources)) return
    UndoHistory.redone(entry)
    onApplied()
}

/** True when it worked; otherwise says why nothing was written. */
private suspend fun report(result: UndoResult, snackbarHostState: SnackbarHostState, resources: Resources): Boolean {
    if (result == UndoResult.DONE) return true
    snackbarHostState.showSnackbar(
        resources.getString(if (result == UndoResult.CHANGED) R.string.undo_changed else R.string.undo_failed),
    )
    return false
}

fun undoMessage(kind: UndoKind): Int = when (kind) {
    UndoKind.CHECK -> R.string.undo_checked
    UndoKind.UNCHECK -> R.string.undo_unchecked
    UndoKind.EDIT -> R.string.undo_edited
    UndoKind.DELETE -> R.string.undo_deleted
    UndoKind.MOVE -> R.string.undo_moved
    UndoKind.SNOOZE -> R.string.undo_snoozed
}

/** The task's text without tags, short enough for one line. */
fun shortLabel(text: String): String {
    val plain = text.replace(Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_/\-]+"""), "").replace(Regex("""\s+"""), " ").trim()
        .ifEmpty { text.trim() }
    return if (plain.length > 40) plain.take(39).trimEnd() + "…" else plain
}
