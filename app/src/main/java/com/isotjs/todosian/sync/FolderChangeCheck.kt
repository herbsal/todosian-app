package com.isotjs.todosian.sync

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.markdownTreeFingerprint
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import com.isotjs.todosian.notifications.TaskReminderScheduler
import com.isotjs.todosian.ui.widget.WidgetRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Notices notes that other apps changed (Obsidian, Syncthing) by comparing a fingerprint of all
 * folders with the last one seen. When it changed, ⏰ alarms are re-planned and widgets refreshed.
 * Reads no note content unless something changed.
 */
object FolderChangeCheck {
    private const val TAG = "FolderChangeCheck"
    private const val PREFS = "folder_watch_state"
    private const val KEY_FINGERPRINT = "fingerprint"

    private val mutex = Mutex()

    /** Returns true when the notes changed since the last check. */
    suspend fun run(context: Context): Boolean {
        val appContext = context.applicationContext
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val fingerprint = runCatching { currentFingerprint(appContext) }
                    .onFailure { Log.w(TAG, "Fingerprint failed", it) }
                    .getOrNull() ?: return@withContext false
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val changed = !prefs.contains(KEY_FINGERPRINT) || prefs.getLong(KEY_FINGERPRINT, 0L) != fingerprint
                if (changed) {
                    Log.d(TAG, "Notes changed outside the app")
                    TaskReminderScheduler.sync(appContext)
                    runCatching { WidgetRefresher.refreshWidgets(appContext) }
                        .onFailure { Log.w(TAG, "Widget refresh failed", it) }
                    prefs.edit { putLong(KEY_FINGERPRINT, fingerprint) }
                }
                changed
            }
        }
    }

    private suspend fun currentFingerprint(context: Context): Long {
        val preferences = PreferencesManager(context)
        val settings = SharedPrefsAppSettingsRepository(context).settings.first()
        val folders = (listOfNotNull(preferences.getFolderUri()) + preferences.getExtraFolderUris()).distinct()
        // Combine per-folder fingerprints; a folder that cannot be read counts as empty.
        return folders.fold(settings.includeSubfolders.hashCode().toLong()) { acc, folder ->
            val part = runCatching { markdownTreeFingerprint(context, folder, settings.includeSubfolders) }.getOrDefault(0L)
            acc * 31 + (folder.toString().hashCode().toLong() xor part)
        }
    }
}
