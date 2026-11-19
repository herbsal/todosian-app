package com.isotjs.todosian.ui.category

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.TodoLineChangedException
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.ui.components.awaitMinimumPullRefreshDuration
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TaskMarkers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class CategoryUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val title: String = "",
    val activeTodos: List<Todo> = emptyList(),
    val completedTodos: List<Todo> = emptyList(),
    val lines: List<String> = emptyList(),
    val moveTargets: List<MoveTarget>? = null,
    val isLoadingMoveTargets: Boolean = false,
)

data class MoveTarget(
    val title: String,
    val uri: Uri,
)

class CategoryViewModel(
    private val fileRepository: FileRepository,
    private val categoryUri: Uri,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryUiState(isLoading = true))
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    private val inFlightWrites = AtomicInteger(0)
    private val pendingRefreshFromObserver = AtomicBoolean(false)

    init {
        observeExternalChanges()
        refreshFromDisk(showLoading = true)
    }

    private fun observeExternalChanges() {        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            fileRepository.observeDocumentChanges(categoryUri)
                .debounce(250)
                .catch {
                    // TODO: Pull to refresh.
                }
                .collectLatest {
                    if (inFlightWrites.get() > 0) {
                        pendingRefreshFromObserver.set(true)
                        return@collectLatest
                    }
                    refreshFromDisk(showLoading = false)
                }
        }
    }

    fun refreshFromDisk(showLoading: Boolean = false, pullRefresh: Boolean = false) {
        if (pullRefresh && _uiState.value.isRefreshing) return
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            if (pullRefresh) {
                _uiState.value = _uiState.value.copy(isRefreshing = true)
            }
            try {
                val markers = currentMarkers()
                if (showLoading) {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                }

                val nameResult = fileRepository.getDisplayName(categoryUri)
                val title = nameResult.getOrNull()?.removeSuffix(".md").orEmpty()

                val linesResult = fileRepository.readLines(categoryUri)
                if (linesResult.isFailure) {
                    _events.emit(Event.ShowMessage(R.string.error_read_failed))
                    _uiState.value = _uiState.value.copy(isLoading = false, title = title)
                    return@launch
                }

                val lines = linesResult.getOrThrow()
                if (!showLoading && lines == _uiState.value.lines) return@launch

                val todos = MarkdownParser.parse(lines, markers)
                val (completed, active) = todos.partition { it.isDone }

                _uiState.value = CategoryUiState(
                    isLoading = false,
                    isRefreshing = _uiState.value.isRefreshing,
                    title = title,
                    activeTodos = active.sortedBy { it.lineIndex },
                    completedTodos = completed.sortedBy { it.lineIndex },
                    lines = lines,
                    moveTargets = _uiState.value.moveTargets,
                )
            } finally {
                if (pullRefresh) {
                    awaitMinimumPullRefreshDuration(startedAt)
                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                }
            }
        }
    }

    fun loadMoveTargets() {
        val state = _uiState.value
        if (state.moveTargets != null || state.isLoadingMoveTargets) return

        _uiState.value = state.copy(isLoadingMoveTargets = true)
        viewModelScope.launch {
            val targets = fileRepository.getCategories()
                .getOrElse { emptyList() }
                .filterNot { it.uri == categoryUri }
                .map { MoveTarget(title = it.displayName, uri = it.uri) }
                .sortedBy { it.title.lowercase() }

            _uiState.value = _uiState.value.copy(
                moveTargets = targets,
                isLoadingMoveTargets = false,
            )
        }
    }

    fun toggleTodo(todo: Todo, enableTasksPluginSupport: Boolean) {
        editTodoLine(todo) { lines, index, markers ->
            MarkdownParser.tryToggleLine(
                lines = lines,
                lineIndex = index,
                enableTasksPlugin = enableTasksPluginSupport,
                markers = markers,
            )
        }
    }

    fun addTodo(
        text: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
        addAtStart: Boolean = false,
    ) {
        editLines { lines, markers ->
            MarkdownParser.addTodo(
                lines = lines,
                text = text,
                meta = meta,
                enableTasksPlugin = enableTasksPluginSupport,
                addAtStart = addAtStart,
                markers = markers,
            )
        }
    }

    fun addSubTodo(
        parent: Todo,
        text: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
    ) {
        editTodoLine(parent) { lines, index, markers ->
            MarkdownParser.addSubTodo(
                lines = lines,
                parentLineIndex = index,
                text = text,
                meta = meta,
                enableTasksPlugin = enableTasksPluginSupport,
                markers = markers,
            )
        }
    }

    fun editTodo(
        todo: Todo,
        newText: String,
        meta: MarkdownParser.TasksMeta?,
        enableTasksPluginSupport: Boolean,
    ) {
        editTodoLine(todo) { lines, index, markers ->
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

    fun deleteTodo(todo: Todo) {
        editTodoLine(todo) { lines, index, _ ->
            MarkdownParser.tryDeleteTodoWithSubtasks(lines, index)
        }
    }

    fun moveTodo(todo: Todo, targetUri: Uri) {
        viewModelScope.launch {
            if (todo.indentLevel > 0) {
                _events.emit(Event.ShowMessage(R.string.category_move_subtask_disabled))
                return@launch
            }
            val markers = currentMarkers()
            val previousLines = _uiState.value.lines
            val expectedLine = previousLines.getOrNull(todo.lineIndex)
            val newLines = MarkdownParser.tryDeleteTodoWithSubtasks(previousLines, todo.lineIndex)
            if (expectedLine == null || newLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }

            applyLines(newLines, markers)

            inFlightWrites.incrementAndGet()
            try {
                val result = fileRepository.moveTodoLine(categoryUri, targetUri, todo.lineIndex, expectedLine)
                if (result.isFailure) {
                    if (_uiState.value.lines == newLines) {
                        applyLines(previousLines, markers)
                    }
                    reportFailure(result.exceptionOrNull())
                } else {
                    _events.emit(Event.ShowMessage(R.string.category_move_success))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    fun copyTodo(todo: Todo, targetUri: Uri) {
        viewModelScope.launch {
            val expectedLine = _uiState.value.lines.getOrNull(todo.lineIndex)
            if (expectedLine == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }
            inFlightWrites.incrementAndGet()
            try {
                val result = fileRepository.copyTodoLine(categoryUri, targetUri, todo.lineIndex, expectedLine)
                if (result.isFailure) {
                    reportFailure(result.exceptionOrNull())
                } else {
                    _events.emit(Event.ShowMessage(R.string.category_copy_success))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    /**
     * Applies an edit of the line [todo] was parsed from. The edit runs twice: on the lines shown
     * (for an instant UI update) and on the file as it is right before writing, where the line is
     * looked up again so a newer version written by another app is never overwritten.
     */
    private fun editTodoLine(
        todo: Todo,
        edit: (lines: List<String>, lineIndex: Int, markers: TaskMarkers) -> List<String>?,
    ) {
        val expectedLine = _uiState.value.lines.getOrNull(todo.lineIndex)
        editLines { lines, markers ->
            if (expectedLine == null) return@editLines null
            val index = MarkdownParser.locateTodoLine(lines, todo.lineIndex, expectedLine)
                ?: return@editLines null
            edit(lines, index, markers)
        }
    }

    /** [edit] returns null when it cannot be applied to the given lines. */
    private fun editLines(edit: (lines: List<String>, markers: TaskMarkers) -> List<String>?) {
        viewModelScope.launch {
            val markers = currentMarkers()
            val previousLines = _uiState.value.lines
            val optimisticLines = edit(previousLines, markers)
            if (optimisticLines == null) {
                _events.emit(Event.ShowMessage(R.string.error_read_failed))
                refreshFromDisk(showLoading = false)
                return@launch
            }
            if (optimisticLines == previousLines) return@launch

            applyLines(optimisticLines, markers)

            inFlightWrites.incrementAndGet()
            try {
                val result = fileRepository.updateLines(categoryUri) { freshLines -> edit(freshLines, markers) }
                result.onSuccess { writtenLines ->
                    when {
                        writtenLines == null -> {
                            _events.emit(Event.ShowMessage(R.string.error_todo_changed_externally))
                            pendingRefreshFromObserver.set(true)
                        }
                        // Show what is on disk now, unless a later edit is still being saved.
                        inFlightWrites.get() == 1 -> applyLines(writtenLines, markers)
                        else -> pendingRefreshFromObserver.set(true)
                    }
                }.onFailure {
                    if (_uiState.value.lines == optimisticLines) {
                        applyLines(previousLines, markers)
                    }
                    _events.emit(Event.ShowMessage(R.string.error_write_failed))
                }
            } finally {
                onWriteFinishedMaybeRefresh()
            }
        }
    }

    private suspend fun reportFailure(error: Throwable?) {
        if (error is TodoLineChangedException) {
            _events.emit(Event.ShowMessage(R.string.error_todo_changed_externally))
            pendingRefreshFromObserver.set(true)
        } else {
            _events.emit(Event.ShowMessage(R.string.error_write_failed))
        }
    }

    private fun onWriteFinishedMaybeRefresh() {
        val remaining = inFlightWrites.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0 && pendingRefreshFromObserver.getAndSet(false)) {
            refreshFromDisk(showLoading = false)
        }
    }

    private fun applyLines(lines: List<String>, markers: TaskMarkers) {
        val todos = MarkdownParser.parse(lines, markers)
        val (completed, active) = todos.partition { it.isDone }
        _uiState.value = _uiState.value.copy(
            activeTodos = active.sortedBy { it.lineIndex },
            completedTodos = completed.sortedBy { it.lineIndex },
            lines = lines,
            moveTargets = _uiState.value.moveTargets,
        )
    }

    private suspend fun currentMarkers(): TaskMarkers =
        appSettingsRepository.settings.first().toTaskMarkers()

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
    }
}
