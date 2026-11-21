package com.isotjs.todosian.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.isotjs.todosian.MainActivity
import com.isotjs.todosian.R

/** Says that a note was deleted outside the app and its reminders were cancelled. */
internal object DeletedNoteNotice {
    private const val CHANNEL_ID = "deleted_notes"
    private const val NOTIFICATION_ID_BASE = 0x0DE1E7E0

    /** [cancelled] maps the path of each deleted note to the number of its cancelled reminders. */
    @SuppressLint("MissingPermission")
    fun show(context: Context, cancelled: Map<String, Int>) {
        if (cancelled.isEmpty()) return
        val appContext = context.applicationContext
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return
        createChannelIfNeeded(appContext)
        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        cancelled.forEach { (path, count) ->
            val name = path.substringAfterLast('/').ifEmpty { appContext.getString(R.string.notification_deleted_note_unknown) }
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(appContext.getString(R.string.notification_deleted_note_title, name))
                .setContentText(appContext.resources.getQuantityString(R.plurals.notification_deleted_note_text, count, count))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching {
                NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_BASE + (path.hashCode() and 0x00FFFFFF), notification)
            }.getOrElse { if (it !is SecurityException) throw it }
        }
    }

    private fun createChannelIfNeeded(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_deleted_note_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_deleted_note_channel_description) },
        )
    }
}
