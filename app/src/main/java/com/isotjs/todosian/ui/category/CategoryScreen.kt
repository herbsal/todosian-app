package com.isotjs.todosian.ui.category

import com.isotjs.todosian.utils.TagDisplaySettings
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material.icons.filled.Close
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.platform.LocalConfiguration
import com.isotjs.todosian.utils.NaturalDates
import com.isotjs.todosian.utils.DateMatch
import com.isotjs.todosian.utils.TaskMarkers
import com.isotjs.todosian.utils.REMINDER_MARKER
import androidx.compose.foundation.layout.size
import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.data.settings.NewTodoFilePosition
import com.isotjs.todosian.data.settings.TodoGrouping
import com.isotjs.todosian.ui.components.TagFilterBar
import com.isotjs.todosian.ui.components.TagQuickPicker
import com.isotjs.todosian.utils.TagFilter
import com.isotjs.todosian.ui.components.TasksMetaEditor
import com.isotjs.todosian.ui.components.TodoRow
import com.isotjs.todosian.ui.components.TodoSortMenu
import com.isotjs.todosian.ui.components.groupedRowShape
import com.isotjs.todosian.utils.TodoSorting
import com.isotjs.todosian.ui.components.TodoSheetMode
import com.isotjs.todosian.ui.components.TodosianDimens
import com.isotjs.todosian.ui.components.TodosianEmptyState
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator
import com.isotjs.todosian.ui.components.TodosianPullToRefreshBox
import com.isotjs.todosian.ui.components.TodosianSectionHeader
import com.isotjs.todosian.ui.components.todosianFabShape
import com.isotjs.todosian.utils.MarkdownParser
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CategoryScreen(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    categoryUri: Uri,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoOpenAddTodo: Boolean = false,
    initialAddText: String = "",
) {
    val viewModel: CategoryViewModel = viewModel(
        factory = CategoryViewModelFactory(
            fileRepository = fileRepository,
            categoryUri = categoryUri,
            appSettingsRepository = appSettingsRepository,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The note may have changed while the app was away (widget toggle, Obsidian edit, sync);
    // re-read the file whenever the screen becomes visible again.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshFromDisk(showLoading = false)
        }
    }

    val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = com.isotjs.todosian.data.settings.AppSettings(),
    )

    val tagDisplay = remember(settings.tagDisplay) { TagDisplaySettings.decode(settings.tagDisplay) }

    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is CategoryViewModel.Event.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageResId))
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    val todoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val moveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var sheetMode by remember { mutableStateOf<TodoSheetMode?>(null) }
    var sheetText by remember { mutableStateOf("") }
    var sheetMeta by remember { mutableStateOf(MarkdownParser.TasksMeta()) }
    var sheetParentTodo by remember { mutableStateOf<Todo?>(null) }
    var sheetFirstStep by remember { mutableStateOf<String?>(null) }
    var deleteTodoTarget by remember { mutableStateOf<Todo?>(null) }
    var deleteTodoHasSubtasks by remember { mutableStateOf(false) }
    var moveTodoTarget by remember { mutableStateOf<Todo?>(null) }
    var showCopyOption by remember { mutableStateOf(false) }

    var autoOpenedAddTodo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoOpenAddTodo && !autoOpenedAddTodo) {
            autoOpenedAddTodo = true
            sheetMode = TodoSheetMode.Add
            sheetText = initialAddText
            sheetMeta = if (settings.enableTasksPluginSupport) {
                MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
            } else {
                MarkdownParser.TasksMeta()
            }
        }
    }

    if (sheetMode != null) {
        ModalBottomSheet(
            onDismissRequest = {
                sheetMode = null
                sheetText = ""
                sheetMeta = MarkdownParser.TasksMeta()
                sheetParentTodo = null
                sheetFirstStep = null
            },
            sheetState = todoSheetState,
        ) {
            val scrollState = rememberScrollState()
            val recognizedDate = remember(sheetText) { NaturalDates.find(sheetText, LocalDateTime.now()) }
            var ignoreDateInText by remember(sheetMode) { mutableStateOf(false) }
            val applyDate: (String, MarkdownParser.TasksMeta) -> Pair<String, MarkdownParser.TasksMeta> = { text, meta ->
                val match = if (sheetMode is TodoSheetMode.Edit || ignoreDateInText) null else NaturalDates.find(text, LocalDateTime.now())
                applyRecognizedDate(match, text, meta, settings.enableTasksPluginSupport, settings.toTaskMarkers())
            }
            val todoTextBringIntoView = remember { BringIntoViewRequester() }
            val recurrenceBringIntoView = remember { BringIntoViewRequester() }
            val titleRes = when (sheetMode) {
                TodoSheetMode.Add -> R.string.category_add_todo_title
                is TodoSheetMode.Edit -> R.string.category_edit_todo_title
                TodoSheetMode.AddSubtask -> R.string.category_add_subtask_title
                null -> R.string.category_add_todo_title
            }

            val hintRes = when (sheetMode) {
                TodoSheetMode.Add -> R.string.category_add_todo_hint
                is TodoSheetMode.Edit -> R.string.category_edit_todo_hint
                TodoSheetMode.AddSubtask -> R.string.category_add_subtask_hint
                null -> R.string.category_add_todo_hint
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp)
                    .verticalScroll(scrollState),
            ) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = sheetText,
                    onValueChange = { sheetText = it },
                    singleLine = true,
                    label = { Text(text = stringResource(hintRes)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(todoTextBringIntoView)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                scope.launch { todoTextBringIntoView.bringIntoView() }
                            }
                        },
                )

                // C2: "morgen 17 Uhr ..." becomes ⏰ / 📅 on save; the chip shows it and can turn it off.
                val dateMatch = if (sheetMode is TodoSheetMode.Edit || ignoreDateInText) null else recognizedDate
                if (dateMatch != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    RecognizedDateChip(match = dateMatch, onIgnore = { ignoreDateInText = true })
                }

                // U1: an optional small first step, saved as a subtask with its own ⏰.
                if (sheetMode == TodoSheetMode.Add) {
                    val firstStep = sheetFirstStep
                    if (firstStep == null) {
                        TextButton(onClick = { sheetFirstStep = "" }) {
                            Text(text = stringResource(R.string.category_first_step_add))
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = firstStep,
                            onValueChange = { sheetFirstStep = it },
                            singleLine = true,
                            label = { Text(text = stringResource(R.string.category_first_step)) },
                            placeholder = { Text(text = stringResource(R.string.category_first_step_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val stepMatch = remember(firstStep) { NaturalDates.find(firstStep, LocalDateTime.now()) }
                        if (stepMatch != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            RecognizedDateChip(match = stepMatch, onIgnore = null)
                        }
                    }
                }

                if (!uiState.tagDefinitions.isEmpty) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TagQuickPicker(
                        definitions = uiState.tagDefinitions,
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
                                sheetMode = null
                                sheetText = ""
                                sheetMeta = MarkdownParser.TasksMeta()
                                sheetParentTodo = null
                                sheetFirstStep = null
                sheetFirstStep = null
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            when (val mode = sheetMode) {
                                TodoSheetMode.Add -> {
                                    val (text, meta) = applyDate(sheetText, sheetMeta)
                                    val step = sheetFirstStep?.trim()?.takeIf { it.isNotEmpty() }
                                    val (stepText, stepMeta) = step?.let { raw ->
                                        applyRecognizedDate(
                                            NaturalDates.find(raw, LocalDateTime.now()),
                                            raw,
                                            MarkdownParser.TasksMeta(createdDate = meta.createdDate),
                                            settings.enableTasksPluginSupport,
                                            settings.toTaskMarkers(),
                                        )
                                    } ?: (null to null)
                                    viewModel.addTodo(
                                        text = text,
                                        meta = if (settings.enableTasksPluginSupport) meta else null,
                                        enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                        addAtStart = settings.newTodoFilePosition == NewTodoFilePosition.TOP,
                                        firstStep = stepText,
                                        firstStepMeta = if (settings.enableTasksPluginSupport) stepMeta else null,
                                    )
                                }

                                is TodoSheetMode.Edit -> viewModel.editTodo(
                                    todo = mode.todo,
                                    newText = sheetText,
                                    meta = if (settings.enableTasksPluginSupport) sheetMeta else null,
                                    enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                )

                                TodoSheetMode.AddSubtask -> {
                                    val parent = sheetParentTodo
                                    if (parent != null) {
                                        val (text, meta) = applyDate(sheetText, sheetMeta)
                                        viewModel.addSubTodo(
                                            parent = parent,
                                            text = text,
                                            meta = if (settings.enableTasksPluginSupport) meta else null,
                                            enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                        )
                                    }
                                }

                                null -> Unit
                            }
                            scope.launch {
                                todoSheetState.hide()
                                sheetMode = null
                                sheetText = ""
                                sheetMeta = MarkdownParser.TasksMeta()
                                sheetParentTodo = null
                                sheetFirstStep = null
                sheetFirstStep = null
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
        val deleteBodyRes = if (deleteTodoHasSubtasks) {
            R.string.category_delete_todo_body_with_subtasks
        } else {
            R.string.category_delete_todo_body
        }
        AlertDialog(
            onDismissRequest = { deleteTodoTarget = null },
            title = { Text(text = stringResource(R.string.category_delete_todo_title)) },
            text = { Text(text = stringResource(deleteBodyRes)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTodoTarget
                        if (target != null) {
                            viewModel.deleteTodo(target)
                        }
                        deleteTodoTarget = null
                        deleteTodoHasSubtasks = false
                    },
                ) {
                    Text(text = stringResource(R.string.category_delete_todo_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteTodoTarget = null
                    deleteTodoHasSubtasks = false
                }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (moveTodoTarget != null) {
        LaunchedEffect(moveTodoTarget) {
            viewModel.loadMoveTargets()
        }
        ModalBottomSheet(
            onDismissRequest = {
                moveTodoTarget = null
                showCopyOption = false
            },
            sheetState = moveSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.category_move_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.category_move_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                val targets = uiState.moveTargets
                when {
                    targets == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TodosianLoadingIndicator()
                        }
                    }

                    targets.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.category_move_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        targets.forEach { target ->
                            ListItem(
                                headlineContent = { Text(text = target.title) },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    val todo = moveTodoTarget
                                    if (todo != null) {
                                        viewModel.moveTodo(todo, target.uri)
                                    }
                                    moveTodoTarget = null
                                    showCopyOption = false
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { showCopyOption = !showCopyOption }) {
                            Text(
                                text = if (showCopyOption) {
                                    stringResource(R.string.category_copy_hide)
                                } else {
                                    stringResource(R.string.category_copy_show)
                                },
                            )
                        }

                        if (showCopyOption) {
                            Spacer(modifier = Modifier.height(8.dp))
                            targets.forEach { target ->
                                ListItem(
                                    headlineContent = { Text(text = target.title) },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCopy,
                                            contentDescription = null,
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        val todo = moveTodoTarget
                                        if (todo != null) {
                                            viewModel.copyTodo(todo, target.uri)
                                        }
                                        moveTodoTarget = null
                                        showCopyOption = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                        )
                    }
                },
                actions = {
                    TodoSortMenu(
                        sort = settings.todoSort,
                        sortGroup = settings.todoSortGroup,
                        groupNames = uiState.tagDefinitions.groups.mapNotNull { group -> group.name.takeIf { group.terms.isNotEmpty() } },
                        onSelect = { sort, group ->
                            if (group != null) appSettingsRepository.setTodoSortGroup(group)
                            appSettingsRepository.setTodoSort(sort)
                        },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    sheetMode = TodoSheetMode.Add
                    sheetText = ""
                    sheetMeta = if (settings.enableTasksPluginSupport) {
                        MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                    } else {
                        MarkdownParser.TasksMeta()
                    }
                },
                shape = todosianFabShape(),
                // See HomeScreen: concave shadow tessellation freezes the RenderThread.
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_add_todo),
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        val anyTodos = uiState.activeTodos.isNotEmpty() || uiState.completedTodos.isNotEmpty()
        var tagFilterKeys by rememberSaveable { mutableStateOf("") }
        val selectedTagKeys = remember(tagFilterKeys) { tagFilterKeys.split(TAG_FILTER_SEPARATOR).filter { it.isNotEmpty() }.toSet() }
        val filterTerms = remember(uiState.activeTodos, uiState.completedTodos, uiState.tagDefinitions) {
            TagFilter.termsFor((uiState.activeTodos + uiState.completedTodos).map { it.text to uiState.tagDefinitions })
        }
        val selectedTerms = remember(filterTerms, selectedTagKeys) { filterTerms.filter { it.key in selectedTagKeys } }
        val filteredActiveTodos = remember(uiState.activeTodos, selectedTerms) {
            uiState.activeTodos.filter { TagFilter.matches(it.text, selectedTerms) }
        }
        val filteredCompletedTodos = remember(uiState.completedTodos, selectedTerms) {
            uiState.completedTodos.filter { TagFilter.matches(it.text, selectedTerms) }
        }
        TodosianPullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refreshFromDisk(pullRefresh = true) },
            enabled = !uiState.isLoading,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TodosianLoadingIndicator()
                }
            } else if (!anyTodos) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        TodosianEmptyState(
                            icon = Icons.Outlined.Checklist,
                            title = stringResource(R.string.category_empty_title),
                            subtitle = stringResource(R.string.category_empty_subtitle),
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                }
            } else {
                val sortKey = Triple(settings.todoSort, settings.todoSortGroup, uiState.tagDefinitions)
                val sortedActiveTodos = remember(filteredActiveTodos, sortKey) {
                    TodoSorting.sort(filteredActiveTodos, settings.todoSort, settings.todoSortGroup, uiState.tagDefinitions)
                }
                val sortedCompletedTodos = remember(filteredCompletedTodos, sortKey) {
                    TodoSorting.sort(filteredCompletedTodos, settings.todoSort, settings.todoSortGroup, uiState.tagDefinitions)
                }
                val sortedAllTodos = remember(filteredActiveTodos, filteredCompletedTodos, sortKey) {
                    TodoSorting.sort(
                        filteredActiveTodos + filteredCompletedTodos,
                        settings.todoSort,
                        settings.todoSortGroup,
                        uiState.tagDefinitions,
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                    if (selectedTerms.isNotEmpty() && sortedAllTodos.isEmpty()) {
                        item(key = "tag_filter_empty") {
                            Text(
                                text = stringResource(R.string.tag_filter_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                    if (settings.todoGrouping == TodoGrouping.FILE_ORDER) {
                        itemsIndexed(
                            items = sortedAllTodos,
                            key = { _, todo -> todo.id },
                        ) { index, todo ->
                            TodoRow(
                                todo = todo,
                                enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                                showSubtaskButton = settings.enableSubtasks,
                                tagDefinitions = uiState.tagDefinitions,
                                tagDisplay = tagDisplay,
                                shape = groupedRowShape(index, sortedAllTodos.size),
                                showDivider = index > 0,
                                onToggle = { viewModel.toggleTodo(todo, settings.enableTasksPluginSupport) },
                                onEdit = {
                                    sheetMode = TodoSheetMode.Edit(todo)
                                    sheetText = todo.text
                                    sheetMeta = MarkdownParser.TasksMeta(
                                        dueDate = todo.dueDate,
                                        startDate = todo.startDate,
                                        scheduledDate = todo.scheduledDate,
                                        completionDate = todo.completionDate,
                                        createdDate = todo.createdDate,
                                        priority = todo.priority,
                                        recurrence = todo.recurrence,
                                        onCompletion = todo.onCompletion,
                                        reminder = todo.reminder,
                                    )
                                },
                                onAddSubtask = {
                                    sheetMode = TodoSheetMode.AddSubtask
                                    sheetParentTodo = todo
                                    sheetText = ""
                                    sheetMeta = if (settings.enableTasksPluginSupport) {
                                        MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                                    } else {
                                        MarkdownParser.TasksMeta()
                                    }
                                },
                                onRequestDelete = {
                                    deleteTodoTarget = todo
                                    deleteTodoHasSubtasks = MarkdownParser.hasSubtasks(uiState.lines, todo.lineIndex)
                                },
                                onRequestMove = { moveTodoTarget = todo },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(durationMillis = 180),
                                    placementSpec = spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                    fadeOutSpec = tween(durationMillis = 160),
                                ),
                            )
                        }
                        item { Spacer(modifier = Modifier.height(12.dp)) }
                        item { Spacer(modifier = Modifier.height(96.dp)) }
                        return@LazyColumn
                    }

                    if (sortedActiveTodos.isNotEmpty()) {
                        item { TodosianSectionHeader(text = stringResource(R.string.category_section_count, stringResource(R.string.category_active), sortedActiveTodos.size)) }
                        itemsIndexed(
                            items = sortedActiveTodos,
                            key = { _, todo -> todo.id },
                        ) { index, todo ->
                            TodoRow(
                                todo = todo,
                                enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                                showSubtaskButton = settings.enableSubtasks,
                                tagDefinitions = uiState.tagDefinitions,
                                tagDisplay = tagDisplay,
                                shape = groupedRowShape(index, sortedActiveTodos.size),
                                showDivider = index > 0,
                                onToggle = { viewModel.toggleTodo(todo, settings.enableTasksPluginSupport) },
                                onEdit = {
                                    sheetMode = TodoSheetMode.Edit(todo)
                                    sheetText = todo.text
                                    sheetMeta = MarkdownParser.TasksMeta(
                                        dueDate = todo.dueDate,
                                        startDate = todo.startDate,
                                        scheduledDate = todo.scheduledDate,
                                        completionDate = todo.completionDate,
                                        createdDate = todo.createdDate,
                                        priority = todo.priority,
                                        recurrence = todo.recurrence,
                                        onCompletion = todo.onCompletion,
                                        reminder = todo.reminder,
                                    )
                                },
                                onAddSubtask = {
                                    sheetMode = TodoSheetMode.AddSubtask
                                    sheetParentTodo = todo
                                    sheetText = ""
                                    sheetMeta = if (settings.enableTasksPluginSupport) {
                                        MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                                    } else {
                                        MarkdownParser.TasksMeta()
                                    }
                                },
                                onRequestDelete = {
                                    deleteTodoTarget = todo
                                    deleteTodoHasSubtasks = MarkdownParser.hasSubtasks(uiState.lines, todo.lineIndex)
                                },
                                onRequestMove = { moveTodoTarget = todo },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(durationMillis = 180),
                                    placementSpec = spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                    fadeOutSpec = tween(durationMillis = 160),
                                ),
                            )
                        }
                        item { Spacer(modifier = Modifier.height(12.dp)) }
                    }

                    if (sortedCompletedTodos.isNotEmpty()) {
                        item { TodosianSectionHeader(text = stringResource(R.string.category_section_count, stringResource(R.string.category_completed), sortedCompletedTodos.size)) }
                        itemsIndexed(
                            items = sortedCompletedTodos,
                            key = { _, todo -> todo.id },
                        ) { index, todo ->
                            TodoRow(
                                todo = todo,
                                enableTasksPluginSupport = settings.enableTasksPluginSupport,
                                useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                                showSubtaskButton = settings.enableSubtasks,
                                tagDefinitions = uiState.tagDefinitions,
                                tagDisplay = tagDisplay,
                                shape = groupedRowShape(index, sortedCompletedTodos.size),
                                showDivider = index > 0,
                                onToggle = { viewModel.toggleTodo(todo, settings.enableTasksPluginSupport) },
                                onEdit = {
                                    sheetMode = TodoSheetMode.Edit(todo)
                                    sheetText = todo.text
                                    sheetMeta = MarkdownParser.TasksMeta(
                                        dueDate = todo.dueDate,
                                        startDate = todo.startDate,
                                        scheduledDate = todo.scheduledDate,
                                        completionDate = todo.completionDate,
                                        createdDate = todo.createdDate,
                                        priority = todo.priority,
                                        recurrence = todo.recurrence,
                                        onCompletion = todo.onCompletion,
                                        reminder = todo.reminder,
                                    )
                                },
                                onAddSubtask = {
                                    sheetMode = TodoSheetMode.AddSubtask
                                    sheetParentTodo = todo
                                    sheetText = ""
                                    sheetMeta = if (settings.enableTasksPluginSupport) {
                                        MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString())
                                    } else {
                                        MarkdownParser.TasksMeta()
                                    }
                                },
                                onRequestDelete = {
                                    deleteTodoTarget = todo
                                    deleteTodoHasSubtasks = MarkdownParser.hasSubtasks(uiState.lines, todo.lineIndex)
                                },
                                onRequestMove = { moveTodoTarget = todo },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(durationMillis = 180),
                                    placementSpec = spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                    fadeOutSpec = tween(durationMillis = 160),
                                ),
                            )
                        }
                        item { Spacer(modifier = Modifier.height(12.dp)) }
                    }

                    item { Spacer(modifier = Modifier.height(96.dp)) }
                }
            }
        }
    }
}

private class CategoryViewModelFactory(
    private val fileRepository: FileRepository,
    private val categoryUri: Uri,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CategoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CategoryViewModel(fileRepository, categoryUri, appSettingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

private const val TAG_FILTER_SEPARATOR = "\u001F"

/**
 * Moves a date found in the text into the task: with a time as ⏰ reminder, without as 📅 due
 * date. Without Obsidian Tasks support the markers are written into the text itself.
 */
private fun applyRecognizedDate(
    match: DateMatch?,
    text: String,
    meta: MarkdownParser.TasksMeta,
    tasksPlugin: Boolean,
    markers: TaskMarkers,
): Pair<String, MarkdownParser.TasksMeta> {
    if (match == null) return text to meta
    val reminder = match.dateTime?.let { MarkdownParser.formatReminder(it) }
    return if (tasksPlugin) {
        match.text to if (reminder != null) meta.copy(reminder = reminder) else meta.copy(dueDate = match.date.toString())
    } else {
        val suffix = if (reminder != null) "$REMINDER_MARKER $reminder" else "${markers.due} ${match.date}"
        "${match.text} $suffix" to meta
    }
}

@Composable
private fun RecognizedDateChip(match: DateMatch, onIgnore: (() -> Unit)?) {
    val locale = LocalConfiguration.current.locales[0]
    val dateText = match.date.format(DateTimeFormatter.ofPattern("EEE d. MMM", locale))
    val label = match.time?.let { time ->
        stringResource(R.string.category_recognized_reminder, dateText, time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)))
    } ?: stringResource(R.string.category_recognized_due, dateText)
    InputChip(
        selected = true,
        onClick = { onIgnore?.invoke() },
        enabled = true,
        label = { Text(text = label) },
        trailingIcon = if (onIgnore == null) null else {
            {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.category_recognized_ignore),
                    modifier = Modifier.size(InputChipDefaults.IconSize),
                )
            }
        },
    )
}
