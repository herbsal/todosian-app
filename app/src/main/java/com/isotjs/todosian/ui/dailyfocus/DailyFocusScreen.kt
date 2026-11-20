package com.isotjs.todosian.ui.dailyfocus

import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.settings.AppSettings
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.data.settings.DailyFocusMode
import com.isotjs.todosian.ui.components.TagFilterBar
import com.isotjs.todosian.ui.components.TagQuickPicker
import com.isotjs.todosian.utils.SavedView
import com.isotjs.todosian.utils.TagFilter
import com.isotjs.todosian.ui.components.TasksMetaEditor
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.ui.components.TodoRow
import com.isotjs.todosian.ui.components.TodoSheetMode
import com.isotjs.todosian.ui.components.TodosianDimens
import com.isotjs.todosian.ui.components.TodosianEmptyState
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator
import com.isotjs.todosian.ui.components.TodosianSectionHeader
import com.isotjs.todosian.utils.MarkdownParser
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DailyFocusScreen(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewFolder: String? = null,
    viewName: String? = null,
) {
    val isView = viewName != null
    val savedView by produceState<SavedView?>(null, viewFolder, viewName) {
        value = viewName?.let { name -> fileRepository.getSavedViews().firstOrNull { it.folder == viewFolder && it.name == name } }
    }
    // Collect settings once to seed the ViewModel factory
    val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings(),
    )

    val viewModel: DailyFocusViewModel = viewModel(
        key = "daily_focus_${settings.dailyFocusMode.name}_${settings.enableTasksPluginSupport}_$isView",
        factory = DailyFocusViewModelFactory(
            fileRepository = fileRepository,
            mode = settings.dailyFocusMode,
            enableTasksPluginSupport = settings.enableTasksPluginSupport,
            appSettingsRepository = appSettingsRepository,
            showAllOpen = isView,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is DailyFocusViewModel.Event.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageResId))
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val todoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var sheetMode by remember { mutableStateOf<TodoSheetMode?>(null) }
    var sheetText by remember { mutableStateOf("") }
    var sheetMeta by remember { mutableStateOf(MarkdownParser.TasksMeta()) }
    var sheetTask by remember { mutableStateOf<DailyFocusTask?>(null) }
    val sheetTagDefinitions by produceState(TagDefinitions.EMPTY, sheetTask?.categoryUri) {
        value = sheetTask?.categoryUri?.let { fileRepository.getTagDefinitions(it) } ?: TagDefinitions.EMPTY
    }
    var deleteTodoTarget by remember { mutableStateOf<DailyFocusTask?>(null) }
    var tagFilterKeys by rememberSaveable { mutableStateOf("") }
    val selectedTagKeys = remember(tagFilterKeys) { tagFilterKeys.split(TAG_FILTER_SEPARATOR).filter { it.isNotEmpty() }.toSet() }
    val categoryUris = remember(uiState.tasks) { uiState.tasks.map { it.categoryUri }.distinct() }
    // Keyword notes of the folders the tasks come from, so the chips use their labels and synonyms.
    val tagDefinitionsByCategory by produceState(emptyMap<Uri, TagDefinitions>(), categoryUris) {
        value = categoryUris.associateWith { fileRepository.getTagDefinitions(it) }
    }
    // A saved view shows only its keywords; until it is loaded, nothing.
    val viewTasks = remember(uiState.tasks, isView, savedView) {
        val view = savedView
        when {
            !isView -> uiState.tasks
            view == null -> emptyList()
            else -> uiState.tasks.filter { TagFilter.matches(it.todo.text, view.terms) }
        }
    }
    val filterTerms = remember(viewTasks, tagDefinitionsByCategory) {
        TagFilter.termsFor(viewTasks.map { it.todo.text to (tagDefinitionsByCategory[it.categoryUri] ?: TagDefinitions.EMPTY) })
    }
    val selectedTerms = remember(filterTerms, selectedTagKeys) { filterTerms.filter { it.key in selectedTagKeys } }

    fun dismissSheet() {
        sheetMode = null
        sheetText = ""
        sheetMeta = MarkdownParser.TasksMeta()
        sheetTask = null
    }

    if (sheetMode != null) {
        ModalBottomSheet(
            onDismissRequest = { dismissSheet() },
            sheetState = todoSheetState,
        ) {
            val scrollState = rememberScrollState()
            val todoTextBringIntoView = remember { BringIntoViewRequester() }
            val recurrenceBringIntoView = remember { BringIntoViewRequester() }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp)
                    .verticalScroll(scrollState),
            ) {
                Text(
                    text = stringResource(R.string.category_edit_todo_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = sheetText,
                    onValueChange = { sheetText = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.category_edit_todo_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(todoTextBringIntoView)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                scope.launch { todoTextBringIntoView.bringIntoView() }
                            }
                        },
                )

                if (!sheetTagDefinitions.isEmpty) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TagQuickPicker(
                        definitions = sheetTagDefinitions,
                        text = sheetText,
                        onTextChange = { sheetText = it },
                    )
                }

                if (settings.enableTasksPluginSupport) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TasksMetaEditor(
                        mode = sheetMode,
                        meta = sheetMeta,
                        onMetaChange = { sheetMeta = it },
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        modifier = Modifier
                            .bringIntoViewRequester(recurrenceBringIntoView)
                            .onFocusEvent { focusState ->
                                if (focusState.isFocused) {
                                    scope.launch { recurrenceBringIntoView.bringIntoView() }
                                }
                            },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                todoSheetState.hide()
                                dismissSheet()
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val task = sheetTask
                            if (task != null) {
                                viewModel.editTodo(
                                    task = task,
                                    newText = sheetText,
                                    meta = if (settings.enableTasksPluginSupport) sheetMeta else null,
                                )
                            }
                            scope.launch {
                                todoSheetState.hide()
                                dismissSheet()
                            }
                        },
                        enabled = sheetText.trim().isNotEmpty(),
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (deleteTodoTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTodoTarget = null },
            title = { Text(text = stringResource(R.string.category_delete_todo_title)) },
            text = { Text(text = stringResource(R.string.category_delete_todo_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTodoTarget
                        if (target != null) viewModel.deleteTodo(target)
                        deleteTodoTarget = null
                    },
                ) {
                    Text(text = stringResource(R.string.category_delete_todo_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTodoTarget = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = viewName ?: stringResource(R.string.daily_focus_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                TodosianLoadingIndicator()
            }
            return@Scaffold
        }

        if (uiState.tasks.isEmpty()) {
            TodosianEmptyState(
                icon = Icons.Outlined.Spa,
                title = stringResource(R.string.daily_focus_empty_title),
                subtitle = stringResource(R.string.daily_focus_empty_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
            )
            return@Scaffold
        }

        // Group tasks by category name
        val grouped = remember(viewTasks, selectedTerms) {
            viewTasks.filter { TagFilter.matches(it.todo.text, selectedTerms) }.groupBy { it.categoryName }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
        ) {
            if (filterTerms.isNotEmpty()) {
                item(key = "tag_filter") {
                    TagFilterBar(
                        terms = filterTerms,
                        selectedKeys = selectedTagKeys,
                        onToggle = { term ->
                            val keys = if (term.key in selectedTagKeys) selectedTagKeys - term.key else selectedTagKeys + term.key
                            tagFilterKeys = keys.joinToString(TAG_FILTER_SEPARATOR)
                        },
                        onClear = { tagFilterKeys = "" },
                    )
                }
            }
            if (grouped.isEmpty()) {
                item(key = "tag_filter_empty") {
                    Text(
                        text = stringResource(R.string.tag_filter_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            grouped.forEach { (categoryName, tasksInGroup) ->
                stickyHeader(key = "header_$categoryName") {
                    TodosianSectionHeader(text = categoryName)
                }
                items(
                    items = tasksInGroup,
                    key = { "${it.categoryUri}:${it.todo.id}" },
                ) { task ->
                    TodoRow(
                        todo = task.todo,
                        enableTasksPluginSupport = settings.enableTasksPluginSupport,
                        useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                        onToggle = { viewModel.toggleTodo(task) },
                        onEdit = {
                            sheetMode = TodoSheetMode.Edit(task.todo)
                            sheetText = task.todo.text
                            sheetMeta = MarkdownParser.TasksMeta(
                                dueDate = task.todo.dueDate,
                                startDate = task.todo.startDate,
                                scheduledDate = task.todo.scheduledDate,
                                completionDate = task.todo.completionDate,
                                createdDate = task.todo.createdDate,
                                priority = task.todo.priority,
                                recurrence = task.todo.recurrence,
                                onCompletion = task.todo.onCompletion,
                                reminder = task.todo.reminder,
                            )
                            sheetTask = task
                        },
                        onAddSubtask = {},
                        onRequestDelete = { deleteTodoTarget = task },
                        onRequestMove = null,
                        allowMove = false,
                        showSubtaskButton = false,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(durationMillis = 180),
                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            fadeOutSpec = tween(durationMillis = 160),
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

private class DailyFocusViewModelFactory(
    private val fileRepository: FileRepository,
    private val mode: DailyFocusMode,
    private val enableTasksPluginSupport: Boolean,
    private val appSettingsRepository: AppSettingsRepository,
    private val showAllOpen: Boolean = false,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DailyFocusViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DailyFocusViewModel(
                fileRepository = fileRepository,
                mode = mode,
                enableTasksPluginSupport = enableTasksPluginSupport,
                appSettingsRepository = appSettingsRepository,
                showAllOpen = showAllOpen,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

private const val TAG_FILTER_SEPARATOR = "\u001F"
