package com.isotjs.todosian.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodic safety net: picks up notes that changed while the app was closed. */
class FolderCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        FolderChangeCheck.run(applicationContext)
        return Result.success()
    }
}
