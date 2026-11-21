package com.isotjs.todosian.ui.home

import android.net.Uri

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.size
import com.isotjs.todosian.utils.SavedView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.isotjs.todosian.BuildConfig
import com.isotjs.todosian.R
import com.isotjs.todosian.data.VaultSync
import com.isotjs.todosian.ui.preview.NotePreviewSheet
import com.isotjs.todosian.utils.ObsidianLinks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.isotjs.todosian.ui.dailyfocus.DailyFocusScreen
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.saveable.rememberSaveable
import com.isotjs.todosian.data.settings.HomeLayout
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagDisplaySettings
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.data.settings.CategorySort
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.ui.components.ChangelogBottomSheet
import com.isotjs.todosian.ui.components.TodosianDimens
import com.isotjs.todosian.ui.components.TodosianEmptyState
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator
import com.isotjs.todosian.ui.components.TodosianLinearProgress
import com.isotjs.todosian.ui.components.TodosianPullToRefreshBox
import com.isotjs.todosian.ui.components.todosianFabShape
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import com.isotjs.todosian.data.NoteConflict
import com.isotjs.todosian.data.conflict.ConflictResolver
import com.isotjs.todosian.data.conflict.MergeOutcome
import com.isotjs.todosian.data.settings.ConflictMode
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.annotation.StringRes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    preferencesManager: PreferencesManager,
    onOpenCategory: (android.net.Uri) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenView: (SavedView) -> Unit,
    refreshSignal: Long,
    onRequireOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(fileRepository, appSettingsRepository),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncProgress by VaultSync.progress.collectAsStateWithLifecycle()
    val conflicts by VaultSync.conflicts.collectAsStateWithLifecycle()
    // Saved views from the views notes; read again whenever the home data refreshes.
    val savedViews by produceState(emptyList<SavedView>(), uiState, refreshSignal) {
        value = fileRepository.getSavedViews()
    }

    var showChangelogSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val folderUri = preferencesManager.getFolderUri()
        val lastSeenVersion = preferencesManager.getLastSeenVersionCode()
        if (folderUri != null && lastSeenVersion < BuildConfig.VERSION_CODE) {
            showChangelogSheet = true
            preferencesManager.saveLastSeenVersionCode(BuildConfig.VERSION_CODE)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Re-scan on every resume so tasks checked in the widget, edits made in Obsidian,
            // or sync changes that happened while the app was away are picked up.
            viewModel.refresh(showLoading = false)
        }
    }

    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is HomeViewModel.Event.ShowMessage -> {
                    snackbarHostState.showSnackbar(resources.getString(event.messageResId))
                }
                HomeViewModel.Event.RequireOnboarding -> onRequireOnboarding()
            }
        }
    }

    val settings by appSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = com.isotjs.todosian.data.settings.AppSettings(),
    )

    LaunchedEffect(refreshSignal) {
        if (refreshSignal > 0L) viewModel.refresh()
    }

    var tab by rememberSaveable { mutableStateOf(HomeTab.LISTS) }
    val shownTab = if (tab == HomeTab.FOCUS && !settings.showDailyFocus) HomeTab.LISTS else tab
    val tagDisplay = remember(settings.tagDisplay) { TagDisplaySettings.decode(settings.tagDisplay) }
    // Tags per list (from the Tags note of its folder), for the symbols in the task rows.
    val tagDefinitionsByList by produceState(
        uiState.categories.associate { it.uri to (fileRepository.cachedTagDefinitions(it.uri) ?: TagDefinitions.EMPTY) },
        uiState.categories,
    ) {
        // The tags last read first (symbols at once), then fresh from the tag note if they changed.
        val cached = uiState.categories.associate { it.uri to (fileRepository.cachedTagDefinitions(it.uri) ?: TagDefinitions.EMPTY) }
        if (cached != value) value = cached
        val fresh = uiState.categories.associate { category -> category.uri to fileRepository.getTagDefinitions(category.uri) }
        if (fresh != value) value = fresh
    }

    var showNewCategorySheet by remember { mutableStateOf(false) }
    var showQuickAdd by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var conflictToMerge by remember { mutableStateOf<NoteConflict?>(null) }
    val localContext = androidx.compose.ui.platform.LocalContext.current
    var newCategoryName by remember { mutableStateOf("") }

    var renameTarget by remember { mutableStateOf<Category?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Category?>(null) }

    val scope = rememberCoroutineScope()
    if (showQuickAdd) {
        QuickAddSheet(
            categories = uiState.categories,
            settings = settings,
            fileRepository = fileRepository,
            appSettingsRepository = appSettingsRepository,
            preferencesManager = preferencesManager,
            onDismiss = { showQuickAdd = false },
            onSaved = { listName ->
                showQuickAdd = false
                viewModel.refresh(showLoading = false)
                scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.quick_add_saved, listName)) }
            },
            onFailed = {
                scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.quick_add_failed)) }
            },
        )
    }
    val newCategorySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val renameCategorySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (showNewCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showNewCategorySheet = false
                newCategoryName = ""
            },
            sheetState = newCategorySheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_new_category_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.home_new_category_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                newCategorySheetState.hide()
                                showNewCategorySheet = false
                                newCategoryName = ""
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            viewModel.createCategory(newCategoryName)
                            scope.launch {
                                newCategorySheetState.hide()
                                showNewCategorySheet = false
                                newCategoryName = ""
                            }
                        },
                        enabled = newCategoryName.trim().isNotEmpty(),
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (renameTarget != null) {
        val validation = remember(renameValue) { validateCategoryNameForRename(renameValue) }
        val errorText = validation.errorMessageResId?.let { stringResource(it) }
        ModalBottomSheet(
            onDismissRequest = {
                renameTarget = null
                renameValue = ""
            },
            sheetState = renameCategorySheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_rename_category_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.home_rename_category_hint)) },
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
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
                                renameCategorySheetState.hide()
                                renameTarget = null
                                renameValue = ""
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val target = renameTarget
                            val normalized = validation.normalizedFileName
                            if (target != null && normalized != null) {
                                viewModel.renameCategory(target.uri, normalized)
                            }
                            scope.launch {
                                renameCategorySheetState.hide()
                                renameTarget = null
                                renameValue = ""
                            }
                        },
                        enabled = validation.normalizedFileName != null,
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(text = stringResource(R.string.home_delete_category_title)) },
            text = {
                Text(
                    text = stringResource(R.string.home_delete_category_body),
                    textAlign = TextAlign.Start,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = deleteTarget
                        if (target != null) viewModel.deleteCategory(target.uri)
                        deleteTarget = null
                    },
                ) {
                    Text(text = stringResource(R.string.home_delete_category_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showSearch) {
        SearchSheet(
            categories = uiState.categories,
            onOpenCategory = { uri ->
                showSearch = false
                onOpenCategory(uri)
            },
            onPreview = { previewUri = it },
            onSearchInObsidian = { query ->
                val folder = fileRepository.getFolderUri()
                if (folder != null) {
                    val vault = ObsidianLinks.vaultName(localContext, folder, folder, settings.obsidianVaultName)
                    if (!ObsidianLinks.launch(localContext, ObsidianLinks.searchUri(vault, query))) {
                        scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.preview_obsidian_missing)) }
                    }
                }
            },
            onDismiss = { showSearch = false },
        )
    }

    previewUri?.let { uri ->
        NotePreviewSheet(
            noteUri = uri,
            mainFolder = fileRepository.getFolderUri(),
            vaultNameSetting = settings.obsidianVaultName,
            onDismiss = { previewUri = null },
            onObsidianMissing = {
                scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.preview_obsidian_missing)) }
            },
        )
    }

    val conflictResolver = remember { ConflictResolver(localContext.applicationContext, fileRepository) }
    // Ten seconds "Undo" after a merge; it puts back note and copy from the backup.
    fun offerUndo(text: String, backupId: String) {
        scope.launch {
            val answer = withTimeoutOrNull(UNDO_MS) {
                snackbarHostState.showSnackbar(
                    message = text,
                    actionLabel = resources.getString(R.string.conflict_undo),
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (answer == SnackbarResult.ActionPerformed) {
                val restored = conflictResolver.restore(backupId)
                viewModel.refresh(showLoading = false)
                snackbarHostState.showSnackbar(
                    resources.getString(if (restored) R.string.conflict_undone else R.string.settings_merged_restore_failed),
                )
            }
        }
    }

    conflictToMerge?.let { conflict ->
        ConflictSheet(
            conflict = conflict,
            resolver = conflictResolver,
            markers = settings.toTaskMarkers(),
            onDismiss = { conflictToMerge = null },
            onMerged = { backupId ->
                conflictToMerge = null
                viewModel.refresh(showLoading = false)
                offerUndo(resources.getString(R.string.conflict_merged, conflict.relPath.substringAfterLast('/')), backupId)
            },
            onOpenInObsidian = {
                val folder = Uri.parse(conflict.folder)
                val vault = ObsidianLinks.vaultName(localContext, folder, fileRepository.getFolderUri(), settings.obsidianVaultName)
                if (!ObsidianLinks.launch(localContext, ObsidianLinks.openNoteUri(vault, conflict.relPath))) {
                    scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.preview_obsidian_missing)) }
                }
            },
        )
    }

    // "Merge clear cases automatically": only with a known base and nothing to ask, once per copy.
    val autoTried = remember { mutableSetOf<String>() }
    LaunchedEffect(conflicts, settings.conflictMode) {
        if (settings.conflictMode != ConflictMode.AUTO) return@LaunchedEffect
        val markers = settings.toTaskMarkers()
        for (conflict in conflicts.distinctBy { it.folder to it.relPath }) {
            if (!autoTried.add(conflict.folder + "/" + conflict.copyPath)) continue
            val prepared = conflictResolver.prepare(conflict, markers) ?: continue
            if (!prepared.result.isClean) continue
            val outcome = conflictResolver.apply(prepared, emptyMap(), markers)
            if (outcome is MergeOutcome.Done) {
                viewModel.refresh(showLoading = false)
                offerUndo(resources.getString(R.string.conflict_merged_auto, conflict.relPath.substringAfterLast('/')), outcome.backupId)
            }
        }
    }

    if (showChangelogSheet) {
        ChangelogBottomSheet(
            onDismissRequest = {
                showChangelogSheet = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.home_title)) },
                actions = {
                    // Shown only while changed notes are read for more than half a second.
                    if (!(syncProgress?.firstBuild == true && uiState.categories.isEmpty())) SyncIndicator(syncProgress)
                    IconButton(onClick = { showSearch = true }) {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar {
                // Daily focus on the left, as its own tab; hidden when switched off in the settings.
                if (settings.showDailyFocus) {
                    val remaining = uiState.remainingFor(settings.dailyFocusMode)
                    NavigationBarItem(
                        selected = shownTab == HomeTab.FOCUS,
                        onClick = { tab = HomeTab.FOCUS },
                        icon = {
                            BadgedBox(badge = { if (remaining > 0) Badge { Text(text = remaining.toString()) } }) {
                                Icon(Icons.Filled.Star, contentDescription = null)
                            }
                        },
                        label = { Text(text = stringResource(R.string.home_daily_focus_title)) },
                    )
                }
                NavigationBarItem(
                    selected = shownTab == HomeTab.LISTS,
                    onClick = { tab = HomeTab.LISTS },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.home_tab_lists)) },
                )
                NavigationBarItem(
                    selected = shownTab == HomeTab.CALENDAR,
                    onClick = { tab = HomeTab.CALENDAR },
                    icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.home_tab_calendar)) },
                )
            }
        },
        floatingActionButton = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            // ⚡ Quick capture, next to "new list": the list comes from the words, else the Inbox.
            SmallFloatingActionButton(
                onClick = { showQuickAdd = true },
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = stringResource(R.string.cd_quick_add),
                )
            }
            if (shownTab == HomeTab.LISTS) {
            Spacer(modifier = Modifier.width(12.dp))
            FloatingActionButton(
                onClick = { showNewCategorySheet = true },
                shape = todosianFabShape(),
                // The cookie shape is concave; Skia's spot-shadow tessellation for concave
                // outlines is pathologically slow and freezes the RenderThread during
                // navigation animations. Keep depth via tonal colors instead of a shadow.
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_add_category),
                )
            }
            }
          }
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        if (shownTab == HomeTab.FOCUS) {
            DailyFocusScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                onBack = {},
                embedded = true,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        val categories = remember(uiState.categories, settings.categorySort) {
            when (settings.categorySort) {
                CategorySort.A_Z -> uiState.categories.sortedBy { it.displayName.lowercase() }
                CategorySort.MOST_REMAINING -> uiState.categories.sortedByDescending { it.todoCount - it.doneCount }
            }
        }

        // Notes without tasks are left out of the lists (switchable); a list made here stays.
        val listedCategories = remember(categories, settings.hideEmptyNotes, uiState.createdHere) {
            if (settings.hideEmptyNotes) categories.filter { it.todoCount > 0 || it.uri in uiState.createdHere } else categories
        }

        val contentState = remember(uiState.isLoading, categories.isEmpty()) {
            when {
                uiState.isLoading -> HomeContentState.LOADING
                categories.isEmpty() -> HomeContentState.EMPTY
                else -> HomeContentState.DATA
            }
        }

        TodosianPullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh(showLoading = false, showRefreshing = true) },
            enabled = !uiState.isLoading,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = TodosianDimens.ScreenHorizontalPadding),
        ) {
            AnimatedContent(
                targetState = contentState,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith
                        fadeOut(animationSpec = tween(160))
                },
                label = "home-content",
                modifier = Modifier.fillMaxSize(),
            ) { state ->
                when (state) {
                    HomeContentState.LOADING -> {
                        val firstBuild = syncProgress?.takeIf { it.firstBuild }
                        if (firstBuild != null) {
                            FirstBuildProgress(progress = firstBuild, modifier = Modifier.fillMaxSize())
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                TodosianLoadingIndicator()
                            }
                        }
                    }

                    HomeContentState.EMPTY -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                val viewportHeight = maxHeight
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        EmptyHomeState(
                                            onCreateCategory = { showNewCategorySheet = true },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(viewportHeight),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HomeContentState.DATA -> if (shownTab == HomeTab.CALENDAR) {
                        CalendarTab(
                            savedViews = savedViews,
                            categories = categories,
                            settings = settings,
                            tagDefinitions = tagDefinitionsByList,
                            tagDisplay = tagDisplay,
                            onOpenCategory = onOpenCategory,
                            onToggleTodo = { category, todo -> viewModel.toggleTodo(category.uri, todo) },
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {

                            if (savedViews.isNotEmpty()) {
                                SavedViewsRow(views = savedViews, onOpen = onOpenView)
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            // Syncthing conflicts: the copy's tasks are not shown, so say it here.
                            // Several copies of one note are merged one after the other, the oldest first.
                            conflicts.distinctBy { it.folder to it.relPath }.forEach { conflict ->
                                ConflictCard(conflict = conflict, onClick = { conflictToMerge = conflict })
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            if (settings.homeLayout == HomeLayout.TASKS) {
                                HomeTaskSections(
                                    categories = listedCategories,
                                    settings = settings,
                                    tagDefinitions = tagDefinitionsByList,
                                    tagDisplay = tagDisplay,
                                    onToggleCollapse = { category ->
                                        val key = category.uri.toString()
                                        val collapsed = settings.homeCollapsed
                                        appSettingsRepository.setHomeCollapsed(if (key in collapsed) collapsed - key else collapsed + key)
                                    },
                                    onOpenCategory = onOpenCategory,
                                    onToggleTodo = { category, todo -> viewModel.toggleTodo(category.uri, todo) },
                                    header = {},
                                )
                            } else LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(bottom = 96.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(
                                    items = listedCategories,
                                    key = { it.uri.toString() },
                                ) { category ->
                                    CategoryCard(
                                        category = category,
                                        onClick = { onOpenCategory(category.uri) },
                                        onRename = {
                                            renameTarget = category
                                            // Notes in subfolders show their path; rename only the file itself.
                                            renameValue = category.displayName.substringAfterLast('/')
                                        },
                                        onDelete = {
                                            deleteTarget = category
                                        },
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = tween(durationMillis = 180),
                                            placementSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                            fadeOutSpec = tween(durationMillis = 160),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class HomeTab { FOCUS, LISTS, CALENDAR }

private enum class HomeContentState {
    LOADING,
    EMPTY,
    DATA,
}

private data class RenameCategoryValidation(
    val normalizedFileName: String?,
    @param:StringRes val errorMessageResId: Int?,
)

private fun validateCategoryNameForRename(input: String): RenameCategoryValidation {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return RenameCategoryValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_category_error_empty,
        )
    }

    if (trimmed.contains("sync-conflict", ignoreCase = true)) {
        return RenameCategoryValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_category_error_sync_conflict,
        )
    }

    val invalidChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    if (trimmed.any { it in invalidChars }) {
        return RenameCategoryValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_category_error_invalid_chars,
        )
    }

    val base = trimmed.removeSuffix(".md").removeSuffix(".MD").removeSuffix(".Md").removeSuffix(".mD").trim()
    if (base.isEmpty()) {
        return RenameCategoryValidation(
            normalizedFileName = null,
            errorMessageResId = R.string.home_rename_category_error_empty,
        )
    }

    val normalized = "$base.md"
    return RenameCategoryValidation(
        normalizedFileName = normalized,
        errorMessageResId = null,
    )
}

@Composable
private fun CategoryCard(
    category: Category,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (category.todoCount == 0) 0f else category.doneCount.toFloat() / category.todoCount.toFloat()
    var menuExpanded by remember(category.uri) { mutableStateOf(false) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_overflow_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.home_category_action_rename)) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.home_category_action_delete)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
            Text(
                text = category.displayName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${category.doneCount}/${category.todoCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TodosianLinearProgress(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptyHomeState(
    onCreateCategory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TodosianEmptyState(
        icon = Icons.Outlined.CreateNewFolder,
        title = stringResource(R.string.home_empty_title),
        subtitle = stringResource(R.string.home_empty_subtitle),
        actionLabel = stringResource(R.string.home_create_category),
        onAction = onCreateCategory,
        modifier = modifier,
    )
}

private class HomeViewModelFactory(
    private val fileRepository: FileRepository,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(fileRepository, appSettingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/** One chip per saved view, e.g. "Small tasks"; tapping opens its tasks from all folders. */
@Composable
private fun SavedViewsRow(views: List<SavedView>, onOpen: (SavedView) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(views, key = { "${it.folder}\u001F${it.name}" }) { view ->
            AssistChip(
                onClick = { onOpen(view) },
                label = { Text(text = view.name) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

private const val UNDO_MS = 10_000L
