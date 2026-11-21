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
    /** Keyword group (by name) used when [todoSort] is [TodoSort.TAG_GROUP]. */
    val todoSortGroup: String = "",
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
    /** How often every note is read again to find anything the note index missed. */
    val deepCheckMode: DeepCheckMode = DeepCheckMode.DAILY_CHARGING,
    /** Folders (paths relative to a note folder) whose notes the app leaves out completely. */
    val excludedFolders: Set<String> = emptySet(),
    /** Home screen: leave out notes without tasks. */
    val hideEmptyNotes: Boolean = true,
    /** What happens to the reminders of a note that was deleted outside the app. */
    val deletedNoteReminders: DeletedNoteReminders = DeletedNoteReminders.CANCEL_SILENTLY,
    /** Which tasks get a stable `🆔` when the app writes their line anyway. */
    val taskIdMode: TaskIdMode = TaskIdMode.REMINDERS,
    /** Name of the vault in Obsidian for `obsidian://` links; empty means the folder's name. */
    val obsidianVaultName: String = "",
    /** Main switch for reminders from reminder profiles; ⏰ in a line always rings. */
    val profileRemindersEnabled: Boolean = true,
    /** Encoded [com.isotjs.todosian.utils.TagDisplaySettings]; empty = defaults. */
    val tagDisplay: String = "",
    /** Find tags of the tag note in the words of a new task (✨ next to the text field). */
    val tagRecognition: Boolean = true,
    /** Take recognized words out of the title; people's names always stay. */
    val tagRecognitionRemoveWords: Boolean = true,
    /** Home screen: open tasks per list, or the list tiles. */
    val homeLayout: HomeLayout = HomeLayout.TASKS,
    /** How many open tasks each list shows on the home screen. */
    val homeTasksPerList: Int = 3,
    /** Lists folded on the home screen (note URIs). */
    val homeCollapsed: Set<String> = emptySet(),
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

enum class HomeLayout {
    TASKS,
    TILES,
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
    REMINDER_EARLIEST_FIRST,
    TITLE_A_Z,

    /** By the order of the keywords of one keyword group, see [AppSettings.todoSortGroup]. */
    TAG_GROUP,
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

enum class TaskIdMode {
    NEVER,

    /** Only open tasks with a ⏰ reminder. */
    REMINDERS,
    ALL,
}

enum class DeletedNoteReminders {
    CANCEL_SILENTLY,

    /** Cancel them and say so in a notification. */
    NOTIFY,
}

enum class DeepCheckMode {
    OFF,

    /** Once a day while the phone charges and is not in use. */
    DAILY_CHARGING,

    WEEKLY,
}

enum class ReminderRepeat {
    OFF,
    HOURLY,
    DAILY,
}
