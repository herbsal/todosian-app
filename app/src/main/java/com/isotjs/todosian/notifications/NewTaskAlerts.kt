package com.isotjs.todosian.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.isotjs.todosian.MainActivity
import com.isotjs.todosian.R
import com.isotjs.todosian.utils.NewTasks
import com.isotjs.todosian.utils.SeenTask

/**
 * "New for you": a notification when someone else adds a task to a list chosen on this device,
 * also without ⏰. Runs as part of every reminder sync, so it follows the folder watch.
 */
object NewTaskAlerts {
    private const val PREFS = "new_task_state"
    private const val KEY_SEEN_PREFIX = "seen:"
    private const val KEY_OWN = "own_lines"
    private const val OWN_KEEP_MILLIS = 3L * 24 * 60 * 60 * 1000
    private const val OWN_MAX = 500
    private const val CHANNEL_ID = "new_tasks"
    private const val NOTIFICATION_ID_BASE = 0x20000000

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Remembers lines this device just wrote, so they never count as someone else's new task. */
    @Synchronized
    fun recordOwnLines(context: Context, lines: Collection<String>) {
        if (lines.isEmpty()) return
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        val kept = prefs.getStringSet(KEY_OWN, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val (key, at) = entry.split('|').takeIf { it.size == 2 } ?: return@mapNotNull null
                val time = at.toLongOrNull() ?: return@mapNotNull null
                if (now - time > OWN_KEEP_MILLIS) null else key to time
            }
        val added = lines.filter { it.isNotBlank() }.map { NewTasks.lineKey(it) to now }
        val all = (kept + added).sortedByDescending { it.second }.distinctBy { it.first }.take(OWN_MAX)
        prefs.edit { putStringSet(KEY_OWN, all.map { "${it.first}|${it.second}" }.toSet()) }
    }

    private fun ownLines(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_OWN, emptySet()).orEmpty().map { it.substringBefore('|') }.toSet()

    /** Compares the open tasks of a watched note with the last look and notifies about new ones. */
    fun check(
        context: Context,
        fileKey: String,
        listName: String,
        open: List<SeenTask>,
        display: (String) -> String = { it },
    ) {
        val prefs = prefs(context)
        val previous = prefs.getStringSet(KEY_SEEN_PREFIX + fileKey, null)
        val fresh = NewTasks.detect(previous, open, ownLines(context))
        prefs.edit { putStringSet(KEY_SEEN_PREFIX + fileKey, open.map { it.key }.toSet()) }
        if (fresh.isNotEmpty()) notify(context, fileKey, listName, fresh.map { display(it.text) })
    }

    /** Forgets notes that are no longer watched, so watching them again starts fresh. */
    fun keepOnly(context: Context, fileKeys: Set<String>) {
        val prefs = prefs(context)
        val stale = prefs.all.keys.filter { it.startsWith(KEY_SEEN_PREFIX) && it.removePrefix(KEY_SEEN_PREFIX) !in fileKeys }
        if (stale.isNotEmpty()) prefs.edit { stale.forEach { remove(it) } }
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, fileKey: String, listName: String, tasks: List<String>) {
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
        val title = if (tasks.size == 1) {
            appContext.getString(R.string.notification_new_task_title, listName)
        } else {
            appContext.resources.getQuantityString(R.plurals.notification_new_tasks_title, tasks.size, tasks.size, listName)
        }
        val style = NotificationCompat.InboxStyle().also { inbox -> tasks.take(6).forEach { inbox.addLine(it) } }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(tasks.joinToString(", "))
            .setStyle(style)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_BASE + (fileKey.hashCode() and 0x0FFFFFFF), notification)
        }.getOrElse { if (it !is SecurityException) throw it }
    }

    private fun createChannelIfNeeded(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_new_task_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notification_new_task_channel_description) },
        )
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
