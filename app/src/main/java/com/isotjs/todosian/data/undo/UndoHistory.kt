package com.isotjs.todosian.data.undo

import com.isotjs.todosian.utils.MarkdownParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** What an action did to a task, for the "undo" message and the history. */
enum class UndoKind { CHECK, UNCHECK, EDIT, DELETE, MOVE, SNOOZE }

/** One note before and after an action. Paths are the notes' URIs. */
data class FileChange(val uri: String, val before: List<String>, val after: List<String>)

/** An action that can be taken back: its kind, the task's text and the notes it changed. */
data class UndoEntry(val id: Long, val kind: UndoKind, val label: String, val at: Long, val changes: List<FileChange>)

/** Outcome of taking an action back or doing it again. */
enum class UndoResult {
    DONE,

    /** A note was changed since (in the app, in Obsidian, by sync): nothing is written. */
    CHANGED,
    FAILED,
}

/**
 * The last actions, newest first, and the ones taken back, for "redo". A new action clears
 * the redo list, as in every editor. Only [max] actions are kept.
 */
class UndoStack(private val max: Int = MAX) {
    var undo: List<UndoEntry> = emptyList()
        private set
    var redo: List<UndoEntry> = emptyList()
        private set

    fun push(entry: UndoEntry) {
        undo = (listOf(entry) + undo).take(max)
        redo = emptyList()
    }

    /** [entry] was taken back: it moves to the redo list, with the notes as they are now. */
    fun undone(entry: UndoEntry) {
        if (undo.none { it.id == entry.id }) return
        undo = undo.filterNot { it.id == entry.id }
        redo = (listOf(entry) + redo).take(max)
    }

    /** [entry] was done again: back to the undo list. */
    fun redone(entry: UndoEntry) {
        if (redo.none { it.id == entry.id }) return
        redo = redo.filterNot { it.id == entry.id }
        undo = (listOf(entry) + undo).take(max)
    }

    companion object {
        const val MAX = 20
    }
}

/** The app's undo history; it lives as long as the app runs. */
object UndoHistory {
    private val stack = UndoStack()
    private val _entries = MutableStateFlow(Pair(emptyList<UndoEntry>(), emptyList<UndoEntry>()))
    private val _recorded = MutableSharedFlow<UndoEntry>(extraBufferCapacity = 8)
    private var nextId = 1L

    /** Undo list and redo list, newest first. */
    val entries: StateFlow<Pair<List<UndoEntry>, List<UndoEntry>>> = _entries.asStateFlow()

    /** Each new action, for the snackbar with "Undo". */
    val recorded: SharedFlow<UndoEntry> = _recorded.asSharedFlow()

    @Synchronized
    fun record(kind: UndoKind, label: String, changes: List<FileChange>, now: Long = System.currentTimeMillis()): UndoEntry? {
        val merged = UndoChanges.merge(changes).filterNot { UndoChanges.same(it.before, it.after) }
        if (merged.isEmpty()) return null
        val entry = UndoEntry(nextId++, kind, label, now, merged)
        stack.push(entry)
        publish()
        _recorded.tryEmit(entry)
        return entry
    }

    @Synchronized
    fun undone(entry: UndoEntry) {
        stack.undone(entry)
        publish()
    }

    @Synchronized
    fun redone(entry: UndoEntry) {
        stack.redone(entry)
        publish()
    }

    private fun publish() {
        _entries.value = stack.undo to stack.redo
    }
}

object UndoChanges {
    /** Several writes of one note in one action count as one change: first before, last after. */
    fun merge(changes: List<FileChange>): List<FileChange> =
        changes.groupBy { it.uri }.map { (uri, list) -> FileChange(uri, list.first().before, list.last().after) }

    /** Equal notes, ignoring 🆔 task IDs the app adds when it writes. */
    fun same(a: List<String>, b: List<String>): Boolean =
        a.size == b.size && a.indices.all { MarkdownParser.withoutTaskId(a[it]) == MarkdownParser.withoutTaskId(b[it]) }
}

/** Collects the notes written while an action runs, see [recordUndo]. */
class UndoRecorder : AbstractCoroutineContextElement(UndoRecorder) {
    private val changes = mutableListOf<FileChange>()

    @Synchronized
    fun add(uri: String, before: List<String>, after: List<String>) {
        changes += FileChange(uri, before, after)
    }

    @Synchronized
    fun changes(): List<FileChange> = changes.toList()

    companion object Key : CoroutineContext.Key<UndoRecorder> {
        /** The recorder of the action running in this coroutine, if any. */
        suspend fun current(): UndoRecorder? = currentCoroutineContext()[UndoRecorder]
    }
}

/** Runs [block] and records the notes it writes as one action that can be taken back. */
suspend fun <T> recordUndo(kind: UndoKind, label: String, block: suspend () -> T): T {
    val recorder = UndoRecorder()
    val result = withContext(recorder) { block() }
    UndoHistory.record(kind, label, recorder.changes())
    return result
}
