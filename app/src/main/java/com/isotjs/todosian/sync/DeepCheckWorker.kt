package com.isotjs.todosian.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.isotjs.todosian.data.settings.DeepCheckMode
import java.util.concurrent.TimeUnit

/** Reads every note again now and then, to find anything the note index missed. */
class DeepCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        FolderChangeCheck.deepCheck(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK = "deep_check"

        /** Plans the deep check for [mode]; a mode that did not change keeps its schedule. */
        fun apply(context: Context, mode: DeepCheckMode) {
            val work = WorkManager.getInstance(context.applicationContext)
            val request = when (mode) {
                DeepCheckMode.OFF -> null
                DeepCheckMode.DAILY_CHARGING -> PeriodicWorkRequestBuilder<DeepCheckWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
                    .addTag(mode.name)
                    .build()
                DeepCheckMode.WEEKLY -> PeriodicWorkRequestBuilder<DeepCheckWorker>(7, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .addTag(mode.name)
                    .build()
            }
            if (request == null) {
                work.cancelUniqueWork(WORK)
            } else {
                work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }
    }
}
