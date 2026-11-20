package com.isotjs.todosian

import android.app.Application
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.SafFileRepository
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import com.isotjs.todosian.notifications.DueReminderScheduler
import com.isotjs.todosian.notifications.TaskReminderScheduler
import com.isotjs.todosian.sync.FolderWatchScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TodosianApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(applicationContext)
    }

    val fileRepository: FileRepository by lazy {
        SafFileRepository(
            appContext = applicationContext,
            preferencesManager = preferencesManager,
            appSettingsRepository = appSettingsRepository,
        )
    }

    val appSettingsRepository: AppSettingsRepository by lazy {
        SharedPrefsAppSettingsRepository(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()

        // A save cut off by a crash or a killed app is repaired before anything else reads the note.
        appScope.launch { fileRepository.recoverInterruptedWrites() }

        appScope.launch {
            // Re-plan ⏰ alarms on start and whenever a setting that affects them changes.
            appSettingsRepository.settings
                .map { listOf(it.reminderRepeat, it.includeSubfolders, it.toTaskMarkers(), it.profileRemindersEnabled) }
                .distinctUntilChanged()
                .collect { TaskReminderScheduler.sync(applicationContext) }
        }

        appScope.launch {
            // Notice notes changed by other apps: 15-minute check, plus the watch service if chosen.
            appSettingsRepository.settings
                .map { it.folderWatchMode to it.includeSubfolders }
                .distinctUntilChanged()
                .collect { (mode, _) -> FolderWatchScheduler.apply(applicationContext, mode) }
        }

        appScope.launch {
            appSettingsRepository.settings
                .map { settings ->
                    ReminderSchedule(
                        enabled = settings.enableTasksPluginSupport,
                        hour = settings.reminderTimeHour,
                        minute = settings.reminderTimeMinute,
                    )
                }
                .distinctUntilChanged()
                .collect { schedule ->
                    DueReminderScheduler.sync(
                        applicationContext,
                        schedule.enabled,
                        schedule.hour,
                        schedule.minute,
                    )
                }
        }
    }

    private data class ReminderSchedule(
        val enabled: Boolean,
        val hour: Int,
        val minute: Int,
    )

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
