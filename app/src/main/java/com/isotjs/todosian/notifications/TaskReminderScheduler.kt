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
import com.isotjs.todosian.data.findReminderProfiles
import com.isotjs.todosian.data.findTagNote
import com.isotjs.todosian.data.scanMarkdownTree
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TagDefinitionParser
import com.isotjs.todosian.utils.TagDefinitions
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

    private const val TAG = "TaskReminderScheduler"
    private const val STATE_PREFS = "task_reminder_state"
    private const val KEY_SCHEDULED = "scheduled"
    private const val KEY_NOTIFIED = "notified"
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

        val candidates = folders.flatMap { folder ->
            val files = runCatching { scanMarkdownTree(context, folder, settings.includeSubfolders).files }
                .getOrElse { emptyList() }
            val profile = if (settings.profileRemindersEnabled) loadProfile(context, preferences, folder) else null
            val definitions = if (profile != null) loadTagDefinitions(context, folder) else TagDefinitions.EMPTY
            files.flatMap { file ->
                val lines = runCatching {
                    context.contentResolver.openInputStream(file.ref)?.bufferedReader()?.use { it.readLines() }
                }.getOrNull().orEmpty()
                val open = MarkdownParser.parse(lines, markers).filter { !it.isDone }
                val direct = open.mapNotNull { todo ->
                    val reminder = MarkdownParser.parseReminder(todo.reminder) ?: return@mapNotNull null
                    ReminderCandidate(file.ref.toString(), todo.text, reminder, todo.lineIndex)
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
                            )
                        },
                        profile = it,
                        definitions = definitions,
                    )
                }.orEmpty()
                direct + fromProfile
            }
        }

        val prefs = statePrefs(context)
        val existingKeys = candidates.map { TaskReminderPlanner.keyOf(it) }.toSet()
        val notified = readNotified(prefs).filterKeys { it in existingKeys }
        val plan = TaskReminderPlanner.plan(candidates, LocalDateTime.now(), notified, settings.reminderRepeat)

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val previous = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        val planned = plan.map { it.key }.toSet()
        (previous - planned).forEach { key -> cancelAlarm(context, alarmManager, key) }
        plan.forEach { setAlarm(context, alarmManager, it) }

        prefs.edit {
            putStringSet(KEY_SCHEDULED, planned)
            putStringSet(KEY_NOTIFIED, encode(notified))
        }
        Log.d(TAG, "Scheduled ${plan.size} reminder(s)")
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

    private fun loadTagDefinitions(context: Context, folder: Uri): TagDefinitions = runCatching {
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

    private fun encode(notified: Map<String, LocalDateTime>): Set<String> =
        notified.map { (key, at) -> "$key$RECORD_SEPARATOR$at" }.toSet()
}
