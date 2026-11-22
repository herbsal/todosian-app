package com.isotjs.todosian.ui.widget

import android.content.Context
import androidx.core.net.toUri
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagDisplay
import com.isotjs.todosian.utils.TagDisplaySettings
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.isotjs.todosian.R
import com.isotjs.todosian.TodosianApplication
import com.isotjs.todosian.data.settings.DailyFocusMode
import com.isotjs.todosian.utils.DailyFocusFilter
import java.time.LocalDate
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.utils.SavedView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val KEY_WIDGET_HAS_FOLDER = booleanPreferencesKey("widget_has_folder")
internal val KEY_WIDGET_ACTIVE_COUNT = intPreferencesKey("widget_active_count")
internal val KEY_WIDGET_ITEM_COUNT = intPreferencesKey("widget_item_count")
internal val KEY_SELECTED_CATEGORY_URI = stringPreferencesKey("selected_category_uri")
internal val KEY_SELECTED_CATEGORY_NAME = stringPreferencesKey("selected_category_name")
internal val KEY_SHOW_COMPLETED = booleanPreferencesKey("show_completed")
internal val KEY_SELECTED_VIEW_NAME = stringPreferencesKey("selected_view_name")
internal val KEY_SELECTED_VIEW_FOLDER = stringPreferencesKey("selected_view_folder")
internal val KEY_SELECTED_FOCUS_MODE = stringPreferencesKey("selected_focus_mode")
private const val ITEM_KEY_PREFIX = "widget_item_"

/**
 * Drops checked entries from the snapshot when the widget's "show completed" preference is off.
 */
internal fun filterCompletedItems(
    items: List<WidgetTaskItem>,
    showCompleted: Boolean,
): List<WidgetTaskItem> = if (showCompleted) items else items.filter { !it.todo.isDone }

/**
 * Which note a widget instance is bound to. Null [categoryUri] means "All lists" (the unbound,
 * backward-compatible mode). [categoryName] is stored alongside the URI so a stale URI (the note
 * was renamed, which changes its document URI) can still be resolved by display name.
 */
internal data class WidgetSelection(
    val categoryUri: String?,
    val categoryName: String?,
    /** A saved view (quick view) instead of a note: its open tasks from all folders. */
    val viewName: String? = null,
    val viewFolder: String? = null,
    /** The daily focus with its own mode, over all lists. */
    val focusMode: DailyFocusMode? = null,
) {
    val isView: Boolean get() = viewName != null
    val isFocus: Boolean get() = focusMode != null
    val isAllLists: Boolean get() = categoryUri == null && viewName == null && focusMode == null

    fun writeTo(prefs: MutablePreferences) {
        if (viewName != null) prefs[KEY_SELECTED_VIEW_NAME] = viewName
        else prefs.remove(KEY_SELECTED_VIEW_NAME)
        if (viewFolder != null) prefs[KEY_SELECTED_VIEW_FOLDER] = viewFolder
        else prefs.remove(KEY_SELECTED_VIEW_FOLDER)
        if (categoryUri != null) prefs[KEY_SELECTED_CATEGORY_URI] = categoryUri
        else prefs.remove(KEY_SELECTED_CATEGORY_URI)
        if (categoryName != null) prefs[KEY_SELECTED_CATEGORY_NAME] = categoryName
        else prefs.remove(KEY_SELECTED_CATEGORY_NAME)
        if (focusMode != null) prefs[KEY_SELECTED_FOCUS_MODE] = focusMode.name
        else prefs.remove(KEY_SELECTED_FOCUS_MODE)
    }

    companion object {
        val ALL = WidgetSelection(null, null)

        fun from(prefs: Preferences): WidgetSelection =
            WidgetSelection(
                prefs[KEY_SELECTED_CATEGORY_URI],
                prefs[KEY_SELECTED_CATEGORY_NAME],
                prefs[KEY_SELECTED_VIEW_NAME],
                prefs[KEY_SELECTED_VIEW_FOLDER],
                prefs[KEY_SELECTED_FOCUS_MODE]?.let { raw -> runCatching { DailyFocusMode.valueOf(raw) }.getOrNull() },
            )
    }
}

/**
 * Pure core of the selection resolution, over (uri string, display name) pairs so it can be unit
 * tested without Android framework classes. Matches by URI first and falls back to the stored
 * display name (e.g. when the document URI changed). Returns the matched URI string, or null for
 * the "All lists" selection, or when the selected note no longer exists.
 */
internal fun resolveCategoryKey(
    categoryKeys: List<Pair<String, String>>,
    selection: WidgetSelection,
): String? {
    if (selection.isAllLists) return null
    categoryKeys.firstOrNull { it.first == selection.categoryUri }?.let { return it.first }
    val name = selection.categoryName?.takeIf { it.isNotBlank() } ?: return null
    return categoryKeys.firstOrNull { it.second.equals(name, ignoreCase = true) }?.first
}

/**
 * Resolves a widget's selection against the current category list. Matches by URI first and falls
 * back to the stored display name (e.g. when the document URI changed). Returns null for the
 * "All lists" selection, or when the selected note no longer exists.
 */
internal fun resolveSelectedCategory(
    categories: List<Category>,
    selection: WidgetSelection,
): Category? {
    if (selection.isAllLists) return null
    val resolvedKey = resolveCategoryKey(
        categoryKeys = categories.map { it.uri.toString() to it.displayName },
        selection = selection,
    ) ?: return null
    return categories.first { it.uri.toString() == resolvedKey }
}

/**
 * Snapshot of one widget instance's content, stored in that widget's Glance state (Preferences
 * DataStore). The widget composition renders exclusively from this snapshot; it never reads the
 * Markdown files itself. This makes every refresh a single, atomic recomposition: Glance reliably
 * pushes new state into a live session, and a new session reads the state that was just written.
 *
 * [title] is the bound note's display name, or null for "All lists". [selectedCategoryUri] lets
 * the widget route its "+" action straight into that note. [missingList] marks a selection whose
 * note no longer exists (renamed or deleted).
 */
internal data class WidgetSnapshot(
    val hasFolder: Boolean,
    val activeCount: Int,
    val items: List<WidgetTaskItem>,
    val title: String? = null,
    val selectedCategoryUri: String? = null,
    val missingList: Boolean = false,
) {
    /**
     * Writes all snapshot keys into [prefs], first removing the item keys of any previous
     * snapshot. Without this, optional fields that are absent in the new snapshot (currently
     * `due_date`) would keep stale values from whatever item previously occupied the same index.
     */
    fun writeTo(prefs: MutablePreferences) {
        val previousCount = prefs[KEY_WIDGET_ITEM_COUNT] ?: 0
        for (index in 0 until maxOf(previousCount, items.size)) {
            val prefix = "$ITEM_KEY_PREFIX$index."
            prefs.remove(stringPreferencesKey("${prefix}category_uri"))
            prefs.remove(stringPreferencesKey("${prefix}category_name"))
            prefs.remove(stringPreferencesKey("${prefix}text"))
            prefs.remove(stringPreferencesKey("${prefix}display_text"))
            prefs.remove(stringPreferencesKey("${prefix}line_index"))
            prefs.remove(stringPreferencesKey("${prefix}is_done"))
            prefs.remove(stringPreferencesKey("${prefix}due_date"))
        }

        prefs[KEY_WIDGET_HAS_FOLDER] = hasFolder
        prefs[KEY_WIDGET_ACTIVE_COUNT] = activeCount
        prefs[KEY_WIDGET_ITEM_COUNT] = items.size
        if (title != null) prefs[TITLE_KEY] = title else prefs.remove(TITLE_KEY)
        if (selectedCategoryUri != null) prefs[SELECTED_URI_KEY] = selectedCategoryUri
        else prefs.remove(SELECTED_URI_KEY)
        prefs[MISSING_LIST_KEY] = missingList
        items.forEachIndexed { index, item ->
            val prefix = "$ITEM_KEY_PREFIX$index."
            prefs[stringPreferencesKey("${prefix}category_uri")] = item.categoryUri
            prefs[stringPreferencesKey("${prefix}category_name")] = item.categoryName
            prefs[stringPreferencesKey("${prefix}text")] = item.todo.text
            prefs[stringPreferencesKey("${prefix}display_text")] = item.displayText
            prefs[intPreferencesKey("${prefix}line_index")] = item.todo.lineIndex
            prefs[booleanPreferencesKey("${prefix}is_done")] = item.todo.isDone
            item.todo.dueDate?.let { prefs[stringPreferencesKey("${prefix}due_date")] = it }
        }
    }

    fun toPreferences(): Preferences = mutablePreferencesOf().also { writeTo(it) }

    companion object {
        private val TITLE_KEY = stringPreferencesKey("widget_title")
        private val SELECTED_URI_KEY = stringPreferencesKey("widget_selected_category_uri")
        private val MISSING_LIST_KEY = booleanPreferencesKey("widget_missing_list")

        /** Returns null when no snapshot was written yet (e.g. a freshly placed widget). */
        fun fromPreferences(prefs: Preferences): WidgetSnapshot? {
            val hasFolder = prefs[KEY_WIDGET_HAS_FOLDER] ?: return null
            val itemCount = prefs[KEY_WIDGET_ITEM_COUNT] ?: 0
            val items = ArrayList<WidgetTaskItem>(itemCount)
            for (index in 0 until itemCount) {
                val prefix = "$ITEM_KEY_PREFIX$index."
                val categoryUri = prefs[stringPreferencesKey("${prefix}category_uri")] ?: continue
                val text = prefs[stringPreferencesKey("${prefix}text")] ?: continue
                val lineIndex = prefs[intPreferencesKey("${prefix}line_index")] ?: continue
                val isDone = prefs[booleanPreferencesKey("${prefix}is_done")] ?: false
                val dueDate = prefs[stringPreferencesKey("${prefix}due_date")]
                items.add(
                    WidgetTaskItem(
                        categoryUri = categoryUri,
                        categoryName = prefs[stringPreferencesKey("${prefix}category_name")] ?: "",
                        todo = Todo(
                            // `id` is a per-parse random UUID and is not part of the snapshot.
                            id = "",
                            text = text,
                            isDone = isDone,
                            lineIndex = lineIndex,
                            dueDate = dueDate,
                        ),
                        displayText = prefs[stringPreferencesKey("${prefix}display_text")] ?: text,
                    )
                )
            }
            return WidgetSnapshot(
                hasFolder = hasFolder,
                activeCount = prefs[KEY_WIDGET_ACTIVE_COUNT] ?: 0,
                items = items,
                title = prefs[TITLE_KEY],
                selectedCategoryUri = prefs[SELECTED_URI_KEY],
                missingList = prefs[MISSING_LIST_KEY] ?: false,
            )
        }
    }
}

/**
 * Central refresh point for the Todosian widget.
 *
 * After any change (a write from the app UI, a checkbox toggle in the widget, a manual refresh),
 * this re-reads the task list from the Markdown files (which remain the single source of truth),
 * stores a per-widget [WidgetSnapshot] in each placed widget's Glance state, and pushes an update
 * to all of them. Each widget shows only its bound note (see [WidgetSelection]); widgets without a
 * selection show all notes, matching the pre-selection behavior.
 */
object WidgetRefresher {
    private const val TAG = "WidgetRefresher"
    private const val MAX_TASKS = 50

    private val refreshMutex = Mutex()

    /**
     * Reloads the task snapshots from the files and updates all placed [TodosianWidget] instances.
     * Concurrent calls are serialized so overlapping triggers cannot interleave state writes.
     */
    suspend fun refreshWidgets(callerContext: Context) {
        // Glance keeps each widget state's DataStore, and the context it was opened with, for the
        // whole process. Callers may pass an Activity, which must not end up there.
        val context = callerContext.applicationContext
        val manager = GlanceAppWidgetManager(context)
        val glanceIds = runCatching { manager.getGlanceIds(TodosianWidget::class.java) }
            .getOrElse {
                Log.e(TAG, "Unable to query placed TodosianWidgets", it)
                return
            }
        if (glanceIds.isEmpty()) return

        val app = context.applicationContext as? TodosianApplication
        val fileRepository = app?.fileRepository
        if (fileRepository == null) {
            Log.w(TAG, "Unable to access file repository")
            return
        }
        val appSettings = app.appSettingsRepository.settings.first()
        val markers = appSettings.toTaskMarkers()
        val tagDisplay = TagDisplaySettings.decode(appSettings.tagDisplay)

        refreshMutex.withLock {
            val categories = fileRepository.getCategories(markers).getOrDefault(emptyList())
            val widgetStates = glanceIds.map { glanceId ->
                runCatching {
                    getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
                }.getOrElse {
                    Log.w(TAG, "Unable to read widget state for $glanceId", it)
                    emptyPreferences()
                }
            }
            val selections = widgetStates.map { WidgetSelection.from(it) }
            val showCompletedFlags = widgetStates.map { it[KEY_SHOW_COMPLETED] ?: true }

            val savedViews = if (selections.any { it.isView }) fileRepository.getSavedViews() else emptyList()
            val hasFolder = fileRepository.getFolderUri() != null
            val tagsByList = mutableMapOf<String, TagDefinitions>()
            for ((index, glanceId) in glanceIds.withIndex()) {
                val snapshot = buildSnapshot(
                    hasFolder = hasFolder,
                    categories = categories,
                    selection = selections[index],
                    view = selections[index].takeIf { it.isView }?.let { selection ->
                        savedViews.firstOrNull { it.name == selection.viewName && it.folder == selection.viewFolder }
                            ?: savedViews.firstOrNull { it.name.equals(selection.viewName, ignoreCase = true) }
                    },
                    showCompleted = showCompletedFlags[index],
                    focusTitle = context.getString(R.string.home_daily_focus_title),
                ).let { built ->
                    // Tags as symbols, as in the app; each list with the tags of its folder.
                    built.copy(
                        items = built.items.map { item ->
                            val definitions = tagsByList.getOrPut(item.categoryUri) {
                                runCatching { fileRepository.getTagDefinitions(item.categoryUri.toUri()) }.getOrDefault(TagDefinitions.EMPTY)
                            }
                            item.copy(displayText = TagDisplay.plainText(item.todo.text, definitions, tagDisplay))
                        },
                    )
                }
                runCatching {
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                        prefs.toMutablePreferences().apply { snapshot.writeTo(this) }
                    }
                }.onFailure { Log.e(TAG, "Failed to update widget state for $glanceId", it) }
            }
            // Push the new state; this starts a session if none is currently running.
            TodosianWidget().updateAll(context)
            Log.d(TAG, "Widget refresh completed: widgets=${glanceIds.size}")
        }
    }

    /** True once a snapshot has been written for [id] (used for freshly placed widgets). */
    internal suspend fun hasSnapshot(context: Context, id: GlanceId): Boolean =
        runCatching {
            getAppWidgetState(context.applicationContext, PreferencesGlanceStateDefinition, id)
                .contains(KEY_WIDGET_HAS_FOLDER)
        }.getOrDefault(false)

    /**
     * Builds the snapshot for one widget instance. For "All lists" the items are aggregated across
     * every note (active first, then completed), capped at [MAX_TASKS]; for a bound note only that
     * note's tasks are used.
     */
    private fun buildSnapshot(
        hasFolder: Boolean,
        categories: List<Category>,
        selection: WidgetSelection,
        view: SavedView?,
        showCompleted: Boolean,
        focusTitle: String = "",
    ): WidgetSnapshot {
        selection.focusMode?.let { mode ->
            // Daily focus: the tasks due in the chosen mode from all lists, earliest due first.
            val today = LocalDate.now().toString()
            val focusItems = categories.flatMap { category ->
                category.todos.filter { DailyFocusFilter.matches(it, mode, today) }.map { todo ->
                    WidgetTaskItem(categoryUri = category.uri.toString(), categoryName = category.displayName, todo = todo)
                }
            }.sortedWith(compareBy<WidgetTaskItem> { it.todo.dueDate.orEmpty() }.thenBy { it.categoryName.lowercase() }.thenBy { it.todo.lineIndex })
            val active = focusItems.filter { !it.todo.isDone }
            val completed = focusItems.filter { it.todo.isDone }
            return WidgetSnapshot(
                hasFolder = hasFolder,
                activeCount = active.size,
                items = filterCompletedItems(active + completed, showCompleted).take(MAX_TASKS),
                title = focusTitle,
                selectedCategoryUri = null,
                missingList = false,
            )
        }
        if (selection.isView && view == null) {
            // The view was renamed or removed from the views note.
            return WidgetSnapshot(
                hasFolder = true,
                activeCount = 0,
                items = emptyList(),
                title = selection.viewName ?: "",
                selectedCategoryUri = null,
                missingList = true,
            )
        }
        val resolved = resolveSelectedCategory(categories, selection)

        if (selection.categoryUri != null && resolved == null) {
            // The bound note was renamed or deleted; keep the title so the user knows which
            // selection to redo, and surface a recoverable state instead of an empty list.
            Log.d(TAG, "Widget selection no longer resolves to a note")
            return WidgetSnapshot(
                hasFolder = true,
                activeCount = 0,
                items = emptyList(),
                title = selection.categoryName ?: "",
                selectedCategoryUri = null,
                missingList = true,
            )
        }

        val scopedCategories = if (resolved != null) listOf(resolved) else categories
        val taskItems = scopedCategories.flatMap { category ->
            // The tasks come with the lists (from the note index), no second read per note.
            category.todos.map { todo ->
                WidgetTaskItem(
                    categoryUri = category.uri.toString(),
                    categoryName = category.displayName,
                    todo = todo,
                )
            }
        }
            .filter { item -> view == null || view.matches(item.todo.text) }
        val activeTasks = taskItems.filter { !it.todo.isDone }
        val completedTasks = taskItems.filter { it.todo.isDone }
        Log.d(
            TAG,
            "loadSnapshot: allLists=${resolved == null} tasks=${taskItems.size} " +
                "active=${activeTasks.size} completed=${completedTasks.size}",
        )
        return WidgetSnapshot(
            hasFolder = hasFolder,
            activeCount = activeTasks.size,
            items = filterCompletedItems(activeTasks + completedTasks, showCompleted)
                .take(MAX_TASKS),
            title = view?.name ?: resolved?.displayName,
            selectedCategoryUri = resolved?.uri?.toString(),
            missingList = false,
        )
    }
}
