package com.isotjs.todosian.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.isotjs.todosian.data.undo.UndoKind
import com.isotjs.todosian.data.undo.recordUndo
import com.isotjs.todosian.MainActivity
import com.isotjs.todosian.R
import com.isotjs.todosian.TodosianApplication
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import com.isotjs.todosian.sync.FolderWatchScheduler
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TaskMarkers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** Shows ⏰ reminders and handles their Done / Later actions, and re-plans alarms on system events. */
class TaskReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                when (intent.action) {
                    TaskReminderScheduler.ACTION_FIRE -> showReminder(context, intent)
                    ACTION_DONE -> completeTodo(context, intent)
                    ACTION_SNOOZE_15 -> postpone(context, intent) { now, _ ->
                        now.plusMinutes(15).truncatedTo(ChronoUnit.MINUTES)
                    }
                    ACTION_LATER -> postpone(context, intent) { now, _ ->
                        now.plusHours(1).truncatedTo(ChronoUnit.MINUTES)
                    }
                    ACTION_TOMORROW_MORNING -> postpone(context, intent) { _, _ ->
                        LocalDate.now().plusDays(1).atTime(MORNING)
                    }
                    ACTION_TOMORROW -> postpone(context, intent) { _, reminder ->
                        LocalDate.now().plusDays(1).atTime(reminder.toLocalTime())
                    }
                    else -> {
                        TaskReminderScheduler.sync(context)
                        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                            val mode = SharedPrefsAppSettingsRepository(context).settings.first().folderWatchMode
                            FolderWatchScheduler.apply(context, mode)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Handling ${intent.action} failed", e)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun showReminder(context: Context, intent: Intent) {
        val target = Target.from(intent) ?: return
        TaskReminderScheduler.onAlarmFired(context, target.key)
        val app = context.applicationContext as? TodosianApplication ?: return
        val markers = app.appSettingsRepository.settings.first().toTaskMarkers()
        val lines = app.fileRepository.readLines(target.fileUri.toUri()).getOrNull()
        // The todo may have been completed, edited or moved to another time since the alarm was set.
        val stillOpen = lines != null &&
            target.locate(lines, markers) != null
        if (stillOpen) {
            TaskReminderScheduler.markNotified(context, target.key)
            notify(context, target)
        }
        TaskReminderScheduler.sync(context)
    }

    private suspend fun completeTodo(context: Context, intent: Intent) {
        val target = Target.from(intent) ?: return
        val app = context.applicationContext as? TodosianApplication ?: return
        val settings = app.appSettingsRepository.settings.first()
        val markers = settings.toTaskMarkers()
        cancelNotification(context, target.key)
        recordUndo(UndoKind.CHECK, target.text) {
            app.fileRepository.updateLines(target.fileUri.toUri()) { lines ->
                val index = target.locate(lines, markers) ?: return@updateLines null
                MarkdownParser.tryToggleLine(
                    lines = lines,
                    lineIndex = index,
                    enableTasksPlugin = settings.enableTasksPluginSupport,
                    markers = markers,
                )
            }
        }
        TaskReminderScheduler.sync(context)
    }

    private suspend fun postpone(
        context: Context,
        intent: Intent,
        newTime: (now: LocalDateTime, reminder: LocalDateTime) -> LocalDateTime,
    ) {
        val target = Target.from(intent) ?: return
        val app = context.applicationContext as? TodosianApplication ?: return
        val markers = app.appSettingsRepository.settings.first().toTaskMarkers()
        val next = newTime(LocalDateTime.now(), target.reminder)
        cancelNotification(context, target.key)
        // Written into the note, so the new time also reaches other devices.
        recordUndo(UndoKind.SNOOZE, target.text) {
            app.fileRepository.updateLines(target.fileUri.toUri()) { lines ->
                val index = target.locate(lines, markers) ?: return@updateLines null
                MarkdownParser.setReminder(lines, index, next, markers)
            }
        }
        TaskReminderScheduler.sync(context)
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, target: Target) {
        val appContext = context.applicationContext
        if (!canPostNotifications(appContext)) return
        createChannelIfNeeded(appContext)

        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = target.reminder.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle((target.displayText ?: target.text).ifBlank { appContext.getString(R.string.notification_task_reminder_untitled) })
            .setContentText(appContext.getString(R.string.notification_task_reminder_body, time))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, appContext.getString(R.string.notification_task_reminder_done), actionIntent(appContext, ACTION_DONE, target))
            .addAction(0, appContext.getString(R.string.notification_task_reminder_snooze_15), actionIntent(appContext, ACTION_SNOOZE_15, target))
            // Android shows at most three actions, so the other choices open a small dialog.
            .addAction(0, appContext.getString(R.string.notification_task_reminder_later_choice), laterChoiceIntent(appContext, target))
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId(target.key), notification)
        }.getOrElse { throwable ->
            if (throwable !is SecurityException) throw throwable
        }
    }

    private fun targetExtras(target: Target): android.os.Bundle =
        TaskReminderScheduler.reminderExtras(
            key = target.key,
            fileUri = target.fileUri,
            text = target.text,
            reminder = target.reminder,
            lineIndex = target.lineIndex,
        ).apply {
            putBoolean(TaskReminderScheduler.EXTRA_FROM_PROFILE, target.fromProfile)
            putString(TaskReminderScheduler.EXTRA_TASK_ID, target.taskId)
        }

    private fun laterChoiceIntent(context: Context, target: Target): PendingIntent {
        val intent = Intent(context, SnoozeActivity::class.java).apply {
            data = TaskReminderScheduler.alarmUri(target.key)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
            putExtras(targetExtras(target))
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, action: String, target: Target): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            this.action = action
            data = TaskReminderScheduler.alarmUri(target.key)
            putExtras(targetExtras(target))
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelNotification(context: Context, key: String) {
        NotificationManagerCompat.from(context.applicationContext).cancel(notificationId(key))
    }

    private fun notificationId(key: String): Int = NOTIFICATION_ID_BASE + (key.hashCode() and 0x0FFFFFFF)

    private fun createChannelIfNeeded(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_task_reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_task_reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private data class Target(
        val key: String,
        val fileUri: String,
        val text: String,
        val reminderText: String,
        val reminder: LocalDateTime,
        val lineIndex: Int,
        val fromProfile: Boolean,
        /** The task with tags as symbols, only for showing it. */
        val displayText: String? = null,
        val taskId: String? = null,
    ) {
        /**
         * Line of this reminder's todo: by its id when it has one, else by text (and ⏰ unless the
         * reminder comes from a profile).
         */
        fun locate(lines: List<String>, markers: TaskMarkers): Int? =
            taskId?.let { MarkdownParser.locateOpenTodoById(lines, it, markers) }
                ?: if (fromProfile) MarkdownParser.locateOpenTodo(lines, text, lineIndex, markers)
            else MarkdownParser.locateReminderTodo(lines, text, reminderText, lineIndex, markers)

        companion object {
            fun from(intent: Intent): Target? {
                val reminderText = intent.getStringExtra(TaskReminderScheduler.EXTRA_REMINDER) ?: return null
                return Target(
                    key = intent.getStringExtra(TaskReminderScheduler.EXTRA_KEY) ?: return null,
                    fileUri = intent.getStringExtra(TaskReminderScheduler.EXTRA_FILE_URI) ?: return null,
                    text = intent.getStringExtra(TaskReminderScheduler.EXTRA_TEXT) ?: return null,
                    reminderText = reminderText,
                    reminder = MarkdownParser.parseReminder(reminderText) ?: return null,
                    lineIndex = intent.getIntExtra(TaskReminderScheduler.EXTRA_LINE_INDEX, -1),
                    fromProfile = intent.getBooleanExtra(TaskReminderScheduler.EXTRA_FROM_PROFILE, false),
                    displayText = intent.getStringExtra(TaskReminderScheduler.EXTRA_DISPLAY_TEXT),
                    taskId = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_ID),
                )
            }
        }
    }

    companion object {
        private const val TAG = "TaskReminderReceiver"
        const val CHANNEL_ID = "task_reminders"
        private const val NOTIFICATION_ID_BASE = 0x10000000
        const val ACTION_DONE = "com.isotjs.todosian.action.TASK_REMINDER_DONE"
        const val ACTION_LATER = "com.isotjs.todosian.action.TASK_REMINDER_LATER"
        const val ACTION_TOMORROW = "com.isotjs.todosian.action.TASK_REMINDER_TOMORROW"
        const val ACTION_SNOOZE_15 = "com.isotjs.todosian.action.TASK_REMINDER_SNOOZE_15"
        const val ACTION_TOMORROW_MORNING = "com.isotjs.todosian.action.TASK_REMINDER_TOMORROW_MORNING"

        /** "Tomorrow morning" in the Later choice. */
        val MORNING: java.time.LocalTime = java.time.LocalTime.of(8, 0)
    }
}
