package com.isotjs.todosian.sync

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.isotjs.todosian.data.settings.FolderWatchMode
import java.util.concurrent.TimeUnit

/**
 * Starts the way the app notices external note changes. The 15-minute check always runs as a
 * safety net; in [FolderWatchMode.INSTANT] the [FolderWatchService] reacts within seconds.
 */
object FolderWatchScheduler {
    private const val TAG = "FolderWatchScheduler"
    private const val PERIODIC_WORK = "folder_check"
    private const val PERIOD_MINUTES = 15L

    /** True when "All files access" is granted, which instant watching needs. */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun apply(context: Context, mode: FolderWatchMode) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FolderCheckWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build(),
        )
        if (mode == FolderWatchMode.INSTANT && hasAllFilesAccess()) {
            // Android only lets an app start a foreground service from the background in a few
            // cases (boot, app in use, ...). Otherwise the periodic check covers it until next start.
            runCatching { FolderWatchService.start(appContext) }
                .onFailure { Log.w(TAG, "Could not start the folder watch service", it) }
        } else {
            FolderWatchService.stop(appContext)
        }
    }
}
