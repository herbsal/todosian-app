package com.isotjs.todosian.data.settings

import com.isotjs.todosian.utils.TaskMarkers

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColorEnabled: Boolean = true,
    val showDailyFocus: Boolean = true,
    val dailyFocusMode: DailyFocusMode = DailyFocusMode.TODAY,
    val categorySort: CategorySort = CategorySort.A_Z,
    val todoGrouping: TodoGrouping = TodoGrouping.GROUPED,
    val todoSort: TodoSort = TodoSort.FILE_ORDER,
    val newTodoFilePosition: NewTodoFilePosition = NewTodoFilePosition.BOTTOM,
    val enableTasksPluginSupport: Boolean = false,
    val tasksPluginUseEmojisInUi: Boolean = false,
    val createdMarker: String? = null,
    val startMarker: String? = null,
    val scheduledMarker: String? = null,
    val dueMarker: String? = null,
    val doneMarker: String? = null,
    val recurrenceMarker: String? = null,
    val enableSubtasks: Boolean = false,
    val includeSubfolders: Boolean = false,
    val reminderTimeHour: Int = 19,
    val reminderTimeMinute: Int = 0,
    val reminderRepeat: ReminderRepeat = ReminderRepeat.OFF,
    val folderWatchMode: FolderWatchMode = FolderWatchMode.PERIODIC,
    /** Main switch for reminders from reminder profiles; ⏰ in a line always rings. */
    val profileRemindersEnabled: Boolean = true,
    /** Encoded [com.isotjs.todosian.utils.TagDisplaySettings]; empty = defaults. */
    val tagDisplay: String = "",
) {
    /** Resolves the configured markers, falling back to the emoji default when a field is blank. */
    fun toTaskMarkers(): TaskMarkers = TaskMarkers(
        created = createdMarker?.takeIf { it.isNotBlank() } ?: TaskMarkers.Default.created,
        start = startMarker?.takeIf { it.isNotBlank() } ?: TaskMarkers.Default.start,
        scheduled = scheduledMarker?.takeIf { it.isNotBlank() } ?: TaskMarkers.Default.scheduled,
        due = dueMarker?.takeIf { it.isNotBlank() } ?: TaskMarkers.Default.due,
        done = doneMarker?.takeIf { it.isNotBlank() } ?: TaskMarkers.Default.done,
        recurrence = recurrenceMarker?.takeIf { it.isNotBlank() } ?: TaskMarkers.Default.recurrence,
    )
}

enum class DailyFocusMode {
    TODAY,
    OVERDUE,
    TODAY_AND_OVERDUE,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class CategorySort {
    A_Z,
    MOST_REMAINING,
}

enum class TodoGrouping {
    GROUPED,
    FILE_ORDER,
}

enum class TodoSort {
    FILE_ORDER,
    PRIORITY_HIGH_TO_LOW,
    CREATED_DATE_NEWEST_FIRST,
    DUE_DATE_EARLIEST_FIRST,
}

enum class NewTodoFilePosition {
    TOP,
    BOTTOM,
}

/** How often a ⏰ reminder is shown again while its todo stays open. */
/** How the app notices notes that other apps (Obsidian, Syncthing) changed while it is closed. */
enum class FolderWatchMode {
    /** WorkManager check every 15 minutes through the folder permission. */
    PERIODIC,

    /** Foreground service with file observers; needs "All files access". */
    INSTANT,
}

enum class ReminderRepeat {
    OFF,
    HOURLY,
    DAILY,
}
