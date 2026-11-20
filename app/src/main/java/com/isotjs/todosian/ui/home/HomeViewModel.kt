package com.isotjs.todosian.ui.home

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.ui.components.awaitMinimumPullRefreshDuration
import com.isotjs.todosian.utils.MarkdownParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val categories: List<Category> = emptyList(),
) {
    fun remainingFor(mode: com.isotjs.todosian.data.settings.DailyFocusMode): Int {
        return when (mode) {
            com.isotjs.todosian.data.settings.DailyFocusMode.TODAY -> categories.sumOf { it.dueTodayCount }
            com.isotjs.todosian.data.settings.DailyFocusMode.OVERDUE -> categories.sumOf { it.overdueCount }
            com.isotjs.todosian.data.settings.DailyFocusMode.TODAY_AND_OVERDUE ->
                categories.sumOf { it.dueTodayCount + it.overdueCount }
        }
    }
}

class HomeViewModel(
    private val fileRepository: FileRepository,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh(showLoading: Boolean = true, showRefreshing: Boolean = false) {
        if (showRefreshing && _uiState.value.isRefreshing) return
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            if (showRefreshing) {
                _uiState.value = _uiState.value.copy(isRefreshing = true)
            }
            try {
                val folderUri = fileRepository.getFolderUri()
                if (folderUri == null) {
                    _events.emit(Event.RequireOnboarding)
                    _uiState.value = HomeUiState(isLoading = false)
                    return@launch
                }

                val shouldShowLoading =
                    !showRefreshing && (showLoading || _uiState.value.categories.isEmpty())
                if (shouldShowLoading) {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                }
                val markers = appSettingsRepository.settings.first().toTaskMarkers()
                val result = fileRepository.getCategories(markers)
                if (result.isFailure) {
                    fileRepository.clearFolderUri()
                    _events.emit(Event.ShowMessage(R.string.error_folder_invalid))
                    _events.emit(Event.RequireOnboarding)
                    _uiState.value = HomeUiState(isLoading = false)
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    categories = result.getOrThrow(),
                )
            } finally {
                if (showRefreshing) {
                    awaitMinimumPullRefreshDuration(startedAt)
                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                }
            }
        }
    }

    /** Ticks a task off (or on) right from the home screen; recurring tasks get their next instance. */
    fun toggleTodo(categoryUri: Uri, todo: com.isotjs.todosian.data.model.Todo) {
        viewModelScope.launch {
            val settings = appSettingsRepository.settings.first()
            val markers = settings.toTaskMarkers()
            val result = fileRepository.updateLines(categoryUri) { lines ->
                val index = if (todo.isDone) {
                    todo.lineIndex.takeIf { MarkdownParser.parse(lines, markers).any { it.lineIndex == todo.lineIndex && it.text == todo.text } }
                } else {
                    MarkdownParser.locateOpenTodo(lines, todo.text, todo.lineIndex, markers)
                } ?: return@updateLines null
                MarkdownParser.tryToggleLine(lines, index, settings.enableTasksPluginSupport, markers = markers)
            }
            when {
                result.isFailure -> _events.emit(Event.ShowMessage(R.string.error_write_failed))
                result.getOrNull() == null -> _events.emit(Event.ShowMessage(R.string.error_todo_changed_externally))
            }
            refresh(showLoading = false)
        }
    }

    fun createCategory(name: String) {
        viewModelScope.launch {
            if (fileRepository.getFolderUri() == null) {
                _events.emit(Event.RequireOnboarding)
                return@launch
            }

            val created = fileRepository.createCategoryByName(name)
            if (created.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                return@launch
            }
            refresh()
        }
    }

    fun renameCategory(categoryUri: Uri, newName: String) {
        viewModelScope.launch {
            val result = fileRepository.renameCategory(categoryUri, newName)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                return@launch
            }
            refresh()
        }
    }

    fun deleteCategory(categoryUri: Uri) {
        viewModelScope.launch {
            val result = fileRepository.deleteCategory(categoryUri)
            if (result.isFailure) {
                _events.emit(Event.ShowMessage(R.string.error_write_failed))
                return@launch
            }
            refresh()
        }
    }

    sealed interface Event {
        data class ShowMessage(@param:StringRes val messageResId: Int) : Event
        data object RequireOnboarding : Event
    }
}
