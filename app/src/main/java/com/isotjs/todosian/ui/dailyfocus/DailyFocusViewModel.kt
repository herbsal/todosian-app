package com.isotjs.todosian.ui.dailyfocus

import com.isotjs.todosian.utils.DailyFocusFilter
import android.net.Uri
import androidx.core.net.toUri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isotjs.todosian.data.undo.UndoKind
import com.isotjs.todosian.data.undo.recordUndo
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.model.priorityRank
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.data.settings.DailyFocusMode
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TaskMarkers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class DailyFocusTask(
    val todo: Todo,
    val categoryUri: Uri,
    val categoryName: String,
)

data class DailyFocusUiState(
    val isLoading: Boolean = true,
    val tasks: List<DailyFocusTask> = emptyList(),

    internal val lineCache: Map<String, List<String>> = emptyMap(),
    internal val categoryNames: Map<String, String> = emptyMap(),
)

class DailyFocusViewModel(
    private val fileRepository: FileRepository,
    val mode: DailyFocusMode,
    val enableTasksPluginSupport: Boolean,
    private val appSettingsRepository: AppSettingsRepository,
    /** For a saved view: every open task, not only the ones due. */
    private val showAllOpen: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyFocusUiState(isLoading = true))
    val uiState: StateFlow<DailyFocusUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    private val inFlightWrites = AtomicInteger(0)
    private val pendingRefreshFromObserver = AtomicBoolean(false)

    init {
        observeExternalChanges()
        refreshFromDisk(showLoading = true)
    }

    private fun observeExternalChanges() {
        if (fileRepository.getFolderUri() == null) return
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            fileRepository.observeAllMarkdownFilesChanges()
                .debounce(350)
                .catch { /* best-effort */ }
                .collectLatest {
                    if (inFlightWrites.get() > 0) {
                        pendingRefreshFromObserver.set(true)
                        return@collectLatest
                    }
                    refreshFromDisk(showLoading = false)
                }
        }
    }

    fun refreshFromDisk(showLoading: Boolean = true) {
        viewModelScope.launch {
            val markers = currentMarkers()
            if (showLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            val today = LocalDate.now().toString()
            val categoriesResult = fileRepository.getCategories(markers)
            if (categoriesResult.isFailure) {
                _uiState.value = DailyFocusUiState(isLoading = false)
                return@launch
            }

            val categories = categoriesResult.getOrThrow()
            val tasks = mutableListOf<DailyFocusTask>()
            val lineCache = mutableMapOf<String, List<String>>()
            val categoryNames = mutableMapOf<String, String>()

            for (category in categories) {
                val linesResult = fileRepository.readLines(category.uri)
                if (linesResult.isFailure) continue

                val lines = linesResult.getOrThrow()
                val uriKey = category.uri.toString()
                lineCache[uriKey] = lines
                categoryNames[uriKey] = category.displayName

                MarkdownParser.parse(lines, markers)
                    .filter { !it.isDone }
                    .filter { todo -> showAllOpen || DailyFocusFilter.matches(todo, mode, today) }
                    .forEach { todo ->
                        tasks.add(
                            DailyFocusTask(
                                todo = todo,
                                categoryUri = category.uri,
                                categoryName = category.displayName,
                            ),
                        )
                    }
            }

            _uiState.value = DailyFocusUiState(
                isLoading = false,
                tasks = sortTasks(tasks),
                lineCache = lineCache,
                categoryNames = categoryNames,
            )
        }
    }

    fun toggleTodo(task: DailyFocusTask) {
        editTaskLine(task, (if (task.todo.isDone) UndoKind.UNCHECK else UndoKind.CHECK) to task.todo.text) { lines, index, markers ->
            MarkdownParser.tryToggleLine(
                lines = lines,
                lineIndex = index,
                enableTasksPlugin = enableTasksPluginSupport,
                markers = markers,
            )
        }
    }

    fun editTodo(
        task: DailyFocusTask,
        newText: String,
        meta: MarkdownParser.TasksMeta?,
    ) {
        editTaskLine(task, UndoKind.EDIT to newText) { lines, index, markers ->
            if (enableTasksPluginSupport) {
                MarkdownParser.editTodo(
                    lines = lines,
                    lineIndex = index,
                    newText = newText,
                    meta = meta,
                    enableTasksPlugin = true,
                    markers = markers,
                )
            } else {
                MarkdownParser.tryEditTodoText(lines, index, newText, markers)
            }
        }
    }

    fun deleteTodo(task: DailyFocusTask) {
        editTaskLine(task, UndoKind.DELETE to task.todo.text) { lines, index, _ ->
            MarkdownParser.tryDeleteTodoWithSubtasks(lines, index)
        }
    }

    /**
     * Applies an edit of the line [task] was parsed from, first to the cached lines for an instant
     * UI update, then to the file as it is right before writing. The line is looked up again in the
     * fresh content, so a newer version written by another app is never overwritten.
     */
    private fun editTaskLine(
        task: DailyFocusTask,
        undo: Pair<UndoKind, String>,
        edit: (lines: List<String>, lineIndex: Int, markers: TaskMarkers) -> List<String>?,
    ) {
        viewModelScope.launch {
            val markers = currentMarkers()
            val previousLines = currentLinesFor(task)
            val expectedLine = previousLines.getOrNull(task.todo.lineIndex)

            fun editOf(lines: List<String>): List<String>? {
                if (expectedLine == null) return null
                val index = MarkdownParser.locateTodoLine(lines, task.todo.lineIndex, expectedLine) ?: return null
                return edit(lines, index, markers)
            }

            val optimisticLines = editOf(previousLines)
            if (optimisticLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }
            if (optimisticLines == previousLines) return@launch

            applyLines(task.categoryUri, optimisticLines, markers)

            inFlightWrites.incrementAndGet()
            try {
                val result = recordUndo(undo.first, undo.second) {
                    fileRepository.updateLines(task.categoryUri) { freshLines -> editOf(freshLines) }
                }
                result.onSuccess { writtenLines ->
                    when {
                        writtenLines == null -> {
                            _events.emit(Event.ShowMessage(R.string.error_todo_changed_externally))
                            pendingRefreshFromObserver.set(true)
                        }
                        // Show what is on disk now, unless a later edit is still being saved.
                        inFlightWrites.get() == 1 -> applyLines(task.categoryUri, writtenLines, markers)
                        else -> pendingRefreshFromObserver.set(true)
                    }
                }.onFailure {
                    if (currentLinesFor(task) == optimisticLines) {
                        applyLines(task.categoryUri, previousLines, markers)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
    }

    private fun currentLinesFor(task: DailyFocusTask): List<String> =
        _uiState.value.lineCache[task.categoryUri.toString()] ?: emptyList()

    private suspend fun currentMarkers(): TaskMarkers =
        appSettingsRepository.settings.first().toTaskMarkers()

    // Replicates the logic from refreshFromDisk but only for a single file, used for optimistic updates after mutations.
    private fun applyLines(categoryUri: Uri, newLines: List<String>, markers: TaskMarkers) {
        val today = LocalDate.now().toString()
        val current = _uiState.value
        val uriKey = categoryUri.toString()

        // Rebuild the cache with the updated file's lines.
        val newCache = current.lineCache.toMutableMap()
        newCache[uriKey] = newLines

        val tasks = mutableListOf<DailyFocusTask>()
        for ((uriString, lines) in newCache) {
            val uri = uriString.toUri()
            val categoryName = current.categoryNames[uriString] ?: continue

            MarkdownParser.parse(lines, markers)
                .filter { !it.isDone }
                .filter { todo -> showAllOpen || DailyFocusFilter.matches(todo, mode, today) }
                .forEach { todo ->
                    tasks.add(
                        DailyFocusTask(
                            todo = todo,
                            categoryUri = uri,
                            categoryName = categoryName,
                        ),
                    )
                }
        }

        _uiState.value = current.copy(
            tasks = sortTasks(tasks),
            lineCache = newCache,
        )
    }

    private fun onWriteFinishedMaybeRefresh() {
        val remaining = inFlightWrites.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0 && pendingRefreshFromObserver.getAndSet(false)) {
            refreshFromDisk(showLoading = false)
        }
    }
}

// Sort: due date earliest -> highest priority -> category name -> line index.
private fun sortTasks(tasks: List<DailyFocusTask>): List<DailyFocusTask> {
    return tasks.sortedWith(
        compareBy<DailyFocusTask> { it.todo.dueDate == null }
            .thenBy { it.todo.dueDate ?: "" }
            .thenByDescending { it.todo.priority.priorityRank() }
            .thenBy { it.categoryName.lowercase() }
            .thenBy { it.todo.lineIndex },
    )
}
