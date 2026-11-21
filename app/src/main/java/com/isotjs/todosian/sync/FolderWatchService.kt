package com.isotjs.todosian.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.isotjs.todosian.MainActivity
import com.isotjs.todosian.R
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Watches the note folders with [FileObserver]s while the user chose "Instantly". Needs "All files
 * access" because file observers work on paths, not on the folder permission. Shows a silent,
 * permanent notification as Android requires for a service that keeps running.
 */
class FolderWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observers = mutableListOf<FileObserver>()
    private var pendingCheck: Job? = null
    private var pendingRebuild: Job? = null
    private var recursive = false

    /** Notes reported since the last check: folder to paths relative to it, with `.md`. */
    private val reported = HashMap<Uri, MutableSet<String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (!Environment.isExternalStorageManager()) {
            Log.w(TAG, "All files access missing, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            rebuildObservers()
            // Catch up on anything that changed while nobody was watching.
            FolderChangeCheck.run(applicationContext)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(observers) {
            observers.forEach { it.stopWatching() }
            observers.clear()
        }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun rebuildObservers() {
        val preferences = PreferencesManager(applicationContext)
        recursive = SharedPrefsAppSettingsRepository(applicationContext).settings.first().includeSubfolders
        val primary = Environment.getExternalStorageDirectory()
        val roots = (listOfNotNull(preferences.getFolderUri()) + preferences.getExtraFolderUris())
            .distinct()
            .mapNotNull { folder -> treeUriToDirectory(folder, primary)?.let { folder to it } }
            .filter { it.second.isDirectory }
        val directories = roots.flatMap { (folder, root) -> collectDirectories(root, recursive).map { WatchedDirectory(folder, root, it) } }
            .distinctBy { it.dir }

        synchronized(observers) {
            observers.forEach { it.stopWatching() }
            observers.clear()
            directories.forEach { dir ->
                val observer = DirectoryObserver(dir)
                observer.startWatching()
                observers.add(observer)
            }
        }
        Log.d(TAG, "Watching ${directories.size} folder(s) in ${roots.size} root(s)")
    }

    private fun collectDirectories(root: File, recursive: Boolean): List<File> {
        if (!recursive) return listOf(root)
        val result = mutableListOf<File>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty() && result.size < MAX_DIRECTORIES) {
            val dir = pending.removeFirst()
            result.add(dir)
            dir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith('.') }
                ?.forEach { pending.add(it) }
        }
        return result
    }

    /** Collects reports for a moment; then only the reported notes are checked, no folder listing. */
    private fun onNoteEvent(folder: Uri, path: String) {
        synchronized(reported) { reported.getOrPut(folder) { HashSet() }.add(path) }
        pendingCheck?.cancel()
        pendingCheck = scope.launch {
            delay(CHECK_DEBOUNCE_MS)
            val files = synchronized(reported) {
                reported.mapValues { it.value.toSet() }.also { reported.clear() }
            }
            if (files.isNotEmpty()) FolderChangeCheck.runForFiles(applicationContext, files)
        }
    }

    private fun onDirectoryEvent() {
        if (!recursive) return
        pendingRebuild?.cancel()
        pendingRebuild = scope.launch {
            delay(CHECK_DEBOUNCE_MS)
            rebuildObservers()
            FolderChangeCheck.run(applicationContext)
        }
    }

    /** A watched [dir] below [root], the directory of the note folder [folder]. */
    private data class WatchedDirectory(val folder: Uri, val root: File, val dir: File)

    private inner class DirectoryObserver(private val watched: WatchedDirectory) : FileObserver(watched.dir, EVENTS) {
        override fun onEvent(event: Int, path: String?) {
            val name = path ?: return
            val file = File(watched.dir, name)
            // IN_ISDIR is passed through in the raw event; a check on disk covers devices that drop it.
            val isDirectory = event and IS_DIRECTORY_FLAG != 0 || file.isDirectory
            when {
                isDirectory && !name.startsWith('.') -> onDirectoryEvent()
                // Conflict copies are reported too: they start or end a Syncthing conflict.
                !isDirectory && name.endsWith(".md", ignoreCase = true) &&
                    (isWatchedNoteName(name) || name.contains("sync-conflict", ignoreCase = true)) ->
                    onNoteEvent(watched.folder, file.relativeTo(watched.root).invariantSeparatorsPath)
            }
        }
    }

    companion object {
        private const val TAG = "FolderWatchService"
        private const val CHANNEL_ID = "folder_watch"
        private const val NOTIFICATION_ID = 0x0F01DE40
        private const val CHECK_DEBOUNCE_MS = 2_000L
        private const val MAX_DIRECTORIES = 2_000
        private const val IS_DIRECTORY_FLAG = 0x40000000
        private const val EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CREATE or FileObserver.DELETE

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FolderWatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FolderWatchService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.folder_watch_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = context.getString(R.string.folder_watch_channel_description)
                    setShowBadge(false)
                },
            )
        }

        private fun buildNotification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.folder_watch_notification_title))
                .setContentText(context.getString(R.string.folder_watch_notification_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}
