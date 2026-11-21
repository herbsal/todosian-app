package com.isotjs.todosian.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.VaultSync
import com.isotjs.todosian.data.findReminderProfiles
import com.isotjs.todosian.data.findTagNote
import com.isotjs.todosian.data.settings.DeletedNoteReminders
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.SeenTask
import com.isotjs.todosian.utils.TagDefinitionParser
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagDisplay
import com.isotjs.todosian.utils.TagDisplaySettings
import com.isotjs.todosian.utils.TagText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Keeps one exact alarm per open todo with a ⏰ reminder. The notes are the only source of truth:
 * every sync re-reads them, so alarms follow edits made in Todosian, Obsidian or via Syncthing.
 */
object TaskReminderScheduler {
    const val ACTION_FIRE = "com.isotjs.todosian.action.TASK_REMINDER"

    const val EXTRA_FILE_URI = "file_uri"
    const val EXTRA_TEXT = "text"
    const val EXTRA_REMINDER = "reminder"
    const val EXTRA_LINE_INDEX = "line_index"
    const val EXTRA_KEY = "key"
    const val EXTRA_FROM_PROFILE = "from_profile"
    const val EXTRA_DISPLAY_TEXT = "display_text"
    const val EXTRA_TASK_ID = "task_id"

    private const val TAG = "TaskReminderScheduler"
    private const val STATE_PREFS = "task_reminder_state"
    private const val KEY_SCHEDULED = "scheduled"
    private const val KEY_NOTIFIED = "notified"
    private const val KEY_SIGNATURES = "alarm_signatures"
    private const val RECORD_SEPARATOR = '\u001E'
    private const val SYNC_DEBOUNCE_MS = 750L

    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pendingSync: Job? = null

    /** Debounced [sync], for callers that just wrote a note and should not wait. */
    fun requestSync(context: Context) {
        val appContext = context.applicationContext
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(SYNC_DEBOUNCE_MS)
            sync(appContext)
        }
    }

    suspend fun sync(context: Context) {
        val appContext = context.applicationContext
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { syncLocked(appContext) }
                    .onFailure { Log.w(TAG, "Reminder sync failed", it) }
            }
        }
    }

    /** Remembers that [key] was just shown, so it is only repeated as the setting allows. */
    fun markNotified(context: Context, key: String, at: LocalDateTime = LocalDateTime.now()) {
        val prefs = statePrefs(context)
        val notified = readNotified(prefs).toMutableMap().apply { put(key, at) }
        prefs.edit { putStringSet(KEY_NOTIFIED, encode(notified)) }
    }

    private suspend fun syncLocked(context: Context) {
        val preferences = PreferencesManager(context)
        val settings = SharedPrefsAppSettingsRepository(context).settings.first()
        val markers = settings.toTaskMarkers()
        val folders = (listOfNotNull(preferences.getFolderUri()) + preferences.getExtraFolderUris()).distinct()
        val alertFiles = preferences.getNewTaskAlertFiles()
        val tagDisplay = TagDisplaySettings.decode(settings.tagDisplay)

        val sync = VaultSync.get(context)
        sync.retainFolders(folders)
        val candidates = folders.flatMap { folder ->
            val notes = runCatching { sync.notes(folder, settings.includeSubfolders) }
                .getOrElse { emptyList() }
            val profile = if (settings.profileRemindersEnabled) loadProfile(context, preferences, folder) else null
            val personScope = preferences.getPersonScope(folder)
            // Always read: notifications show the tags as symbols, as in the app.
            val definitions = loadTagDefinitions(context, folder)
            val display = { text: String -> TagDisplay.plainText(text, definitions, tagDisplay) }
            notes.flatMap { synced ->
                val file = synced.entry
                // Notes come from the note index; only changed ones were read.
                val readLines = synced.takeIf { it.isKnown }?.lines
                val lines = readLines.orEmpty()
                // Only tasks of the people chosen on this device remind, also those with ⏰.
                val open = MarkdownParser.parse(lines, markers)
                    .filter { !it.isDone }
                    .filter { todo -> personScope?.allows(todo.text, definitions) ?: true }
                if (readLines != null && file.ref.toString() in alertFiles) {
                    runCatching {
                        NewTaskAlerts.check(
                            context = context,
                            fileKey = file.ref.toString(),
                            listName = file.relativePath.substringAfterLast('/').removeSuffix(".md"),
                            open = open.map { todo -> SeenTask(todo.text, lines.getOrElse(todo.lineIndex) { todo.text }) },
                            display = display,
                        )
                    }.onFailure { Log.w(TAG, "Checking for new tasks failed", it) }
                }
                val direct = open.mapNotNull { todo ->
                    val reminder = MarkdownParser.parseReminder(todo.reminder) ?: return@mapNotNull null
                    ReminderCandidate(file.ref.toString(), todo.text, reminder, todo.lineIndex, taskId = todo.taskId)
                }
                val fromProfile = profile?.let {
                    ProfileReminderPlanner.candidates(
                        fileKey = file.ref.toString(),
                        todos = open.map { todo ->
                            ProfileTodo(
                                text = todo.text,
                                lineIndex = todo.lineIndex,
                                tags = TagText.tagsIn(todo.text),
                                date = (todo.dueDate ?: todo.scheduledDate ?: todo.startDate)
                                    ?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() },
                                hasOwnReminder = MarkdownParser.parseReminder(todo.reminder) != null,
                                taskId = todo.taskId,
                            )
                        },
                        profile = it,
                        definitions = definitions,
                    )
                }.orEmpty()
                (direct + fromProfile).map { it.copy(displayText = display(it.text)) }
            }
        }

        NewTaskAlerts.keepOnly(context, alertFiles)
        val prefs = statePrefs(context)
        // "Already shown" stays with a task whose note was renamed or whose text was fixed.
        val renames = sync.takeRenames()
        val deletions = sync.takeDeletions()
        val notified = TaskReminderPlanner.carryOverNotified(readNotified(prefs), candidates, renames)
        val plan = TaskReminderPlanner.plan(candidates, LocalDateTime.now(), notified, settings.reminderRepeat)

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val previous = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        val previousSignatures = decodeSignatures(prefs.getStringSet(KEY_SIGNATURES, emptySet()).orEmpty())
        val planned = plan.map { it.key }.toSet()
        val cancelled = previous - planned
        cancelled.forEach { key -> cancelAlarm(context, alarmManager, key) }
        if (settings.deletedNoteReminders == DeletedNoteReminders.NOTIFY) {
            // Reminders that went away with a note deleted outside the app, by the note's path.
            val byNote = cancelled.mapNotNull { key -> deletions[TaskReminderPlanner.fileKeyOf(key)] }
                .groupingBy { it }.eachCount()
            runCatching { DeletedNoteNotice.show(context, byNote) }.onFailure { Log.w(TAG, "Deleted note notice failed", it) }
        }
        // Only alarms that are new or changed are set again; the others stay as they are. An alarm
        // the system dropped (restart, app stopped) has no pending intent any more and is set too.
        val signatures = plan.associate { it.key to TaskReminderPlanner.signatureOf(it) }
        val changed = plan.filter {
            previousSignatures[it.key] != signatures[it.key] || it.key !in previous || !alarmExists(context, it.key)
        }
        changed.forEach { setAlarm(context, alarmManager, it) }

        prefs.edit {
            putStringSet(KEY_SCHEDULED, planned)
            putStringSet(KEY_SIGNATURES, encodeSignatures(signatures))
            putStringSet(KEY_NOTIFIED, encode(notified))
        }
        Log.d(TAG, "Planned ${plan.size} reminder(s), set ${changed.size}, cancelled ${cancelled.size}")
    }

    /** The reminder profile chosen on this device for [folder], or null. */
    private fun loadProfile(context: Context, preferences: PreferencesManager, folder: Uri): ReminderProfile? {
        val name = preferences.getReminderProfile(folder) ?: return null
        return runCatching {
            val uri = findReminderProfiles(context, folder).firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
                ?: return@runCatching null
            val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() }.orEmpty()
            ReminderProfileParser.parse(name, lines)
        }.onFailure { Log.w(TAG, "Reading reminder profile failed", it) }.getOrNull()
    }

    internal fun loadTagDefinitions(context: Context, folder: Uri): TagDefinitions = runCatching {
        val note = findTagNote(context, folder) ?: return@runCatching TagDefinitions.EMPTY
        val lines = context.contentResolver.openInputStream(note)?.bufferedReader()?.use { it.readLines() }.orEmpty()
        TagDefinitionParser.parse(lines)
    }.getOrDefault(TagDefinitions.EMPTY)

    private fun setAlarm(context: Context, alarmManager: AlarmManager, planned: PlannedReminder) {
        val triggerMillis = planned.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = firePendingIntent(context, planned.key, planned.candidate)
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        }
    }

    /** An alarm went off: its pending intent is dropped, so the next sync sets it again if still due. */
    fun onAlarmFired(context: Context, key: String) {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            data = alarmUri(key)
        }
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
    }

    private fun alarmExists(context: Context, key: String): Boolean {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            data = alarmUri(key)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
    }

    private fun cancelAlarm(context: Context, alarmManager: AlarmManager, key: String) {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            data = alarmUri(key)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun firePendingIntent(context: Context, key: String, candidate: ReminderCandidate): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            // The data URI makes each reminder's PendingIntent distinct; extras alone would not.
            data = alarmUri(key)
            putExtras(reminderExtras(key, candidate.fileKey, candidate.text, candidate.reminder, candidate.lineIndex))
            putExtra(EXTRA_FROM_PROFILE, candidate.fromProfile)
            putExtra(EXTRA_DISPLAY_TEXT, candidate.displayText)
            putExtra(EXTRA_TASK_ID, candidate.taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun reminderExtras(
        key: String,
        fileUri: String,
        text: String,
        reminder: LocalDateTime,
        lineIndex: Int,
    ) = android.os.Bundle().apply {
        putString(EXTRA_KEY, key)
        putString(EXTRA_FILE_URI, fileUri)
        putString(EXTRA_TEXT, text)
        putString(EXTRA_REMINDER, MarkdownParser.formatReminder(reminder))
        putInt(EXTRA_LINE_INDEX, lineIndex)
    }

    fun alarmUri(key: String): Uri = Uri.parse("todosian-reminder://alarm/" + Uri.encode(key))

    private fun statePrefs(context: Context) =
        context.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun readNotified(prefs: android.content.SharedPreferences): Map<String, LocalDateTime> =
        prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty().mapNotNull { record ->
            val separator = record.lastIndexOf(RECORD_SEPARATOR)
            if (separator <= 0) return@mapNotNull null
            val at = runCatching { LocalDateTime.parse(record.substring(separator + 1)) }.getOrNull()
                ?: return@mapNotNull null
            record.substring(0, separator) to at
        }.toMap()

    private fun encodeSignatures(signatures: Map<String, String>): Set<String> =
        signatures.map { (key, signature) -> "$key$RECORD_SEPARATOR$signature" }.toSet()

    private fun decodeSignatures(records: Set<String>): Map<String, String> =
        records.mapNotNull { record ->
            // The key never holds the record separator; the signature comes after the first one.
            val separator = record.indexOf(RECORD_SEPARATOR)
            if (separator <= 0) null else record.substring(0, separator) to record.substring(separator + 1)
        }.toMap()

    private fun encode(notified: Map<String, LocalDateTime>): Set<String> =
        notified.map { (key, at) -> "$key$RECORD_SEPARATOR$at" }.toSet()
}
