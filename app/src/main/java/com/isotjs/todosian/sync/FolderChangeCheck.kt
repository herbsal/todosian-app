package com.isotjs.todosian.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.VaultSync
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import com.isotjs.todosian.notifications.TaskReminderScheduler
import com.isotjs.todosian.ui.widget.WidgetRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Notices notes that other apps changed (Obsidian, Syncthing). It brings the note index up to
 * date and compares a fingerprint of the index with the last one seen. When it changed, ⏰ alarms
 * are re-planned and widgets refreshed. Only notes whose time or size changed are read.
 */
object FolderChangeCheck {
    private const val TAG = "FolderChangeCheck"
    private const val PREFS = "folder_watch_state"
    private const val KEY_FINGERPRINT = "index_fingerprint"

    /** More reported files than this are checked with one look at the whole folder. */
    private const val MAX_SINGLE_FILES = 50

    private val mutex = Mutex()

    /** Lists every folder; returns true when the notes changed since the last check. */
    suspend fun run(context: Context): Boolean = check(context.applicationContext) { sync, folders, recursive ->
        folders.forEach { folder ->
            runCatching { sync.notes(folder, recursive) }.onFailure { Log.w(TAG, "Folder check failed", it) }
        }
    }

    /**
     * Checks only the files the folder watch reported, as paths relative to their folder with
     * `.md`. Falls back to [run] for many files or a Syncthing conflict copy.
     */
    suspend fun runForFiles(context: Context, files: Map<Uri, Set<String>>): Boolean {
        if (files.values.sumOf { it.size } > MAX_SINGLE_FILES) return run(context)
        var needsFullLook = false
        val changed = check(context.applicationContext) { sync, folders, recursive ->
            files.filterKeys { it in folders }.forEach { (folder, paths) ->
                val result = runCatching { sync.checkFiles(folder, paths, recursive) }
                    .onFailure { Log.w(TAG, "File check failed", it) }
                    .getOrNull()
                if (result == null) needsFullLook = true
            }
        }
        return if (needsFullLook) run(context) || changed else changed
    }

    private suspend fun check(
        appContext: Context,
        update: (sync: VaultSync, folders: List<Uri>, recursive: Boolean) -> Unit,
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val preferences = PreferencesManager(appContext)
            val settings = SharedPrefsAppSettingsRepository(appContext).settings.first()
            val folders = (listOfNotNull(preferences.getFolderUri()) + preferences.getExtraFolderUris()).distinct()
            val sync = VaultSync.get(appContext)
            update(sync, folders, settings.includeSubfolders)
            // The app may have read the changes already (lists); the fingerprint still tells.
            val fingerprint = sync.fingerprint(folders) * 31 + settings.includeSubfolders.hashCode()
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val changed = !prefs.contains(KEY_FINGERPRINT) || prefs.getLong(KEY_FINGERPRINT, 0L) != fingerprint
            if (changed) {
                Log.d(TAG, "Notes changed")
                TaskReminderScheduler.sync(appContext)
                runCatching { WidgetRefresher.refreshWidgets(appContext) }
                    .onFailure { Log.w(TAG, "Widget refresh failed", it) }
                prefs.edit { putLong(KEY_FINGERPRINT, fingerprint) }
            }
            changed
        }
    }
}
