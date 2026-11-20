package com.isotjs.todosian.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

interface AppSettingsRepository {
    val settings: Flow<AppSettings>

    fun setThemeMode(themeMode: ThemeMode)
    fun setDynamicColorEnabled(enabled: Boolean)

    fun setShowDailyFocus(enabled: Boolean)
    fun setDailyFocusMode(mode: DailyFocusMode)
    fun setCategorySort(sort: CategorySort)
    fun setTodoGrouping(grouping: TodoGrouping)
    fun setTodoSort(sort: TodoSort)
    fun setNewTodoFilePosition(position: NewTodoFilePosition)

    fun setEnableTasksPluginSupport(enabled: Boolean)

    fun setTasksPluginUseEmojisInUi(enabled: Boolean)

    fun setCreatedMarker(marker: String)

    fun setStartMarker(marker: String)

    fun setScheduledMarker(marker: String)

    fun setDueMarker(marker: String)

    fun setDoneMarker(marker: String)

    fun setRecurrenceMarker(marker: String)

    fun setEnableSubtasks(enabled: Boolean)

    fun setIncludeSubfolders(enabled: Boolean)

    fun setReminderTime(hour: Int, minute: Int)

    fun setReminderRepeat(repeat: ReminderRepeat)

    fun setFolderWatchMode(mode: FolderWatchMode)

    fun setProfileRemindersEnabled(enabled: Boolean)

    /** How keyword tags appear in task rows (encoded [com.isotjs.todosian.utils.TagDisplaySettings]). */
    fun setTagDisplay(encoded: String) {}

    fun setTagRecognition(enabled: Boolean) {}

    fun setTagRecognitionRemoveWords(enabled: Boolean) {}

    fun setHomeLayout(layout: HomeLayout) {}

    fun setHomeTasksPerList(count: Int) {}

    fun setHomeCollapsed(uris: Set<String>) {}

    /** Keyword group (by name) for [TodoSort.TAG_GROUP]. */
    fun setTodoSortGroup(group: String) {}
}

class SharedPrefsAppSettingsRepository(
    context: Context,
) : AppSettingsRepository {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val settings: Flow<AppSettings> = callbackFlow {
        fun sendCurrent() {
            trySend(readSettings()).isSuccess
        }

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            sendCurrent()
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)
        sendCurrent()

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    override fun setThemeMode(themeMode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, themeMode.name) }
    }

    override fun setDynamicColorEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
    }

    override fun setShowDailyFocus(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_DAILY_FOCUS, enabled) }
    }

    override fun setDailyFocusMode(mode: DailyFocusMode) {
        prefs.edit { putString(KEY_DAILY_FOCUS_MODE, mode.name) }
    }

    override fun setCategorySort(sort: CategorySort) {
        prefs.edit { putString(KEY_CATEGORY_SORT, sort.name) }
    }

    override fun setTodoGrouping(grouping: TodoGrouping) {
        prefs.edit { putString(KEY_TODO_GROUPING, grouping.name) }
    }

    override fun setTodoSort(sort: TodoSort) {
        prefs.edit { putString(KEY_TODO_SORT, sort.name) }
    }

    override fun setNewTodoFilePosition(position: NewTodoFilePosition) {
        prefs.edit { putString(KEY_NEW_TODO_FILE_POSITION, position.name) }
    }

    override fun setEnableTasksPluginSupport(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_TASKS_PLUGIN, enabled) }
    }

    override fun setTasksPluginUseEmojisInUi(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_TASKS_PLUGIN_UI_EMOJIS, enabled) }
    }

    override fun setCreatedMarker(marker: String) {
        prefs.edit { putString(KEY_CREATED_MARKER, marker.trim()) }
    }

    override fun setStartMarker(marker: String) {
        prefs.edit { putString(KEY_START_MARKER, marker.trim()) }
    }

    override fun setScheduledMarker(marker: String) {
        prefs.edit { putString(KEY_SCHEDULED_MARKER, marker.trim()) }
    }

    override fun setDueMarker(marker: String) {
        prefs.edit { putString(KEY_DUE_MARKER, marker.trim()) }
    }

    override fun setDoneMarker(marker: String) {
        prefs.edit { putString(KEY_DONE_MARKER, marker.trim()) }
    }

    override fun setRecurrenceMarker(marker: String) {
        prefs.edit { putString(KEY_RECURRENCE_MARKER, marker.trim()) }
    }

    override fun setEnableSubtasks(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SUBTASKS, enabled) }
    }

    override fun setIncludeSubfolders(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_INCLUDE_SUBFOLDERS, enabled) }
    }

    override fun setReminderTime(hour: Int, minute: Int) {
        prefs.edit {
            putInt(KEY_REMINDER_TIME_HOUR, hour)
            putInt(KEY_REMINDER_TIME_MINUTE, minute)
        }
    }

    override fun setReminderRepeat(repeat: ReminderRepeat) {
        prefs.edit { putString(KEY_REMINDER_REPEAT, repeat.name) }
    }

    override fun setFolderWatchMode(mode: FolderWatchMode) {
        prefs.edit { putString(KEY_FOLDER_WATCH_MODE, mode.name) }
    }

    override fun setProfileRemindersEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_PROFILE_REMINDERS, enabled) }
    }

    override fun setTagDisplay(encoded: String) {
        prefs.edit { putString(KEY_TAG_DISPLAY, encoded) }
    }

    override fun setTagRecognition(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_TAG_RECOGNITION, enabled) }
    }

    override fun setTagRecognitionRemoveWords(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_TAG_RECOGNITION_REMOVE, enabled) }
    }

    override fun setHomeLayout(layout: HomeLayout) {
        prefs.edit { putString(KEY_HOME_LAYOUT, layout.name) }
    }

    override fun setHomeTasksPerList(count: Int) {
        prefs.edit { putInt(KEY_HOME_TASKS_PER_LIST, count) }
    }

    override fun setHomeCollapsed(uris: Set<String>) {
        prefs.edit { putStringSet(KEY_HOME_COLLAPSED, uris) }
    }

    override fun setTodoSortGroup(group: String) {
        prefs.edit { putString(KEY_TODO_SORT_GROUP, group) }
    }

    private fun readSettings(): AppSettings {
        val themeMode = prefs.getString(KEY_THEME_MODE, null)?.let { raw ->
            runCatching { ThemeMode.valueOf(raw) }.getOrNull()
        } ?: ThemeMode.SYSTEM

        val categorySort = prefs.getString(KEY_CATEGORY_SORT, null)?.let { raw ->
            runCatching { CategorySort.valueOf(raw) }.getOrNull()
        } ?: CategorySort.A_Z

        val todoGrouping = prefs.getString(KEY_TODO_GROUPING, null)?.let { raw ->
            runCatching { TodoGrouping.valueOf(raw) }.getOrNull()
        } ?: TodoGrouping.GROUPED

        val todoSort = prefs.getString(KEY_TODO_SORT, null)?.let { raw ->
            runCatching { TodoSort.valueOf(raw) }.getOrNull()
        } ?: TodoSort.FILE_ORDER

        val newTodoFilePosition = prefs.getString(KEY_NEW_TODO_FILE_POSITION, null)?.let { raw ->
            runCatching { NewTodoFilePosition.valueOf(raw) }.getOrNull()
        } ?: NewTodoFilePosition.BOTTOM

        return AppSettings(
            themeMode = themeMode,
            dynamicColorEnabled = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            showDailyFocus = prefs.getBoolean(KEY_SHOW_DAILY_FOCUS, true),
            dailyFocusMode = prefs.getString(KEY_DAILY_FOCUS_MODE, null)?.let { raw ->
                runCatching { DailyFocusMode.valueOf(raw) }.getOrNull()
            } ?: DailyFocusMode.TODAY,
            categorySort = categorySort,
            todoGrouping = todoGrouping,
            todoSort = todoSort,
            todoSortGroup = prefs.getString(KEY_TODO_SORT_GROUP, null).orEmpty(),
            newTodoFilePosition = newTodoFilePosition,
            enableTasksPluginSupport = prefs.getBoolean(KEY_TASKS_PLUGIN, false),
            tasksPluginUseEmojisInUi = prefs.getBoolean(KEY_TASKS_PLUGIN_UI_EMOJIS, false),
            createdMarker = prefs.getString(KEY_CREATED_MARKER, null)?.takeIf { it.isNotBlank() },
            startMarker = prefs.getString(KEY_START_MARKER, null)?.takeIf { it.isNotBlank() },
            scheduledMarker = prefs.getString(KEY_SCHEDULED_MARKER, null)?.takeIf { it.isNotBlank() },
            dueMarker = prefs.getString(KEY_DUE_MARKER, null)?.takeIf { it.isNotBlank() },
            doneMarker = prefs.getString(KEY_DONE_MARKER, null)?.takeIf { it.isNotBlank() },
            recurrenceMarker = prefs.getString(KEY_RECURRENCE_MARKER, null)?.takeIf { it.isNotBlank() },
            enableSubtasks = prefs.getBoolean(KEY_SUBTASKS, false),
            includeSubfolders = prefs.getBoolean(KEY_INCLUDE_SUBFOLDERS, false),
            reminderTimeHour = prefs.getInt(KEY_REMINDER_TIME_HOUR, 19),
            reminderTimeMinute = prefs.getInt(KEY_REMINDER_TIME_MINUTE, 0),
            reminderRepeat = prefs.getString(KEY_REMINDER_REPEAT, null)?.let { raw ->
                runCatching { ReminderRepeat.valueOf(raw) }.getOrNull()
            } ?: ReminderRepeat.OFF,
            folderWatchMode = prefs.getString(KEY_FOLDER_WATCH_MODE, null)?.let { raw ->
                runCatching { FolderWatchMode.valueOf(raw) }.getOrNull()
            } ?: FolderWatchMode.PERIODIC,
            profileRemindersEnabled = prefs.getBoolean(KEY_PROFILE_REMINDERS, true),
            tagDisplay = prefs.getString(KEY_TAG_DISPLAY, null).orEmpty(),
            tagRecognition = prefs.getBoolean(KEY_TAG_RECOGNITION, true),
            tagRecognitionRemoveWords = prefs.getBoolean(KEY_TAG_RECOGNITION_REMOVE, true),
            homeLayout = prefs.getString(KEY_HOME_LAYOUT, null)
                ?.let { raw -> runCatching { HomeLayout.valueOf(raw) }.getOrNull() } ?: HomeLayout.TASKS,
            homeTasksPerList = prefs.getInt(KEY_HOME_TASKS_PER_LIST, 3),
            homeCollapsed = prefs.getStringSet(KEY_HOME_COLLAPSED, emptySet()).orEmpty().toSet(),
        )
    }

    private companion object {
        private const val PREFS_NAME = "todosian_settings"

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_SHOW_DAILY_FOCUS = "show_daily_focus"
        private const val KEY_DAILY_FOCUS_MODE = "daily_focus_mode"
        private const val KEY_CATEGORY_SORT = "category_sort"
        private const val KEY_TODO_GROUPING = "todo_grouping"
        private const val KEY_TODO_SORT = "todo_sort"
        private const val KEY_NEW_TODO_FILE_POSITION = "new_todo_file_position"

        private const val KEY_TASKS_PLUGIN = "tasks_plugin_support"

        private const val KEY_TASKS_PLUGIN_UI_EMOJIS = "tasks_plugin_ui_emojis"

        private const val KEY_CREATED_MARKER = "created_marker"
        private const val KEY_START_MARKER = "start_marker"
        private const val KEY_SCHEDULED_MARKER = "scheduled_marker"
        private const val KEY_DUE_MARKER = "due_marker"
        private const val KEY_DONE_MARKER = "done_marker"
        private const val KEY_RECURRENCE_MARKER = "recurrence_marker"

        private const val KEY_SUBTASKS = "enable_subtasks"
        private const val KEY_INCLUDE_SUBFOLDERS = "include_subfolders"

        private const val KEY_REMINDER_TIME_HOUR = "reminder_time_hour"
        private const val KEY_REMINDER_TIME_MINUTE = "reminder_time_minute"
        private const val KEY_REMINDER_REPEAT = "reminder_repeat"
        private const val KEY_FOLDER_WATCH_MODE = "folder_watch_mode"
        private const val KEY_PROFILE_REMINDERS = "profile_reminders_enabled"
        private const val KEY_TAG_DISPLAY = "tag_display"
        private const val KEY_TAG_RECOGNITION = "tag_recognition"
        private const val KEY_TAG_RECOGNITION_REMOVE = "tag_recognition_remove_words"
        private const val KEY_HOME_LAYOUT = "home_layout"
        private const val KEY_HOME_TASKS_PER_LIST = "home_tasks_per_list"
        private const val KEY_HOME_COLLAPSED = "home_collapsed"
        private const val KEY_TODO_SORT_GROUP = "todo_sort_group"
    }
}
