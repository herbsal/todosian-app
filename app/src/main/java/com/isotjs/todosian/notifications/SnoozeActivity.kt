package com.isotjs.todosian.notifications

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.isotjs.todosian.R
import com.isotjs.todosian.ui.theme.TodosianTheme

/**
 * The "Later" choice of a ⏰ reminder: a small dialog over whatever is on screen. A notification
 * holds only three actions, so 1 hour and tomorrow are chosen here. The choice goes to
 * [TaskReminderReceiver] with the reminder's extras, which writes the new time into the note.
 */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras
        if (extras == null) {
            finish()
            return
        }
        // As in the notification: tags as symbols ("⏱ 5 Min"), not as #tags.
        val title = (extras.getString(TaskReminderScheduler.EXTRA_DISPLAY_TEXT) ?: extras.getString(TaskReminderScheduler.EXTRA_TEXT)).orEmpty()
        setContent {
            TodosianTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = {
                        Text(text = title.ifBlank { stringResource(R.string.notification_task_reminder_untitled) })
                    },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            listOf(
                                TaskReminderReceiver.ACTION_SNOOZE_15 to R.string.notification_task_reminder_snooze_15,
                                TaskReminderReceiver.ACTION_LATER to R.string.notification_task_reminder_later,
                                TaskReminderReceiver.ACTION_TOMORROW_MORNING to R.string.notification_task_reminder_tomorrow_morning,
                                TaskReminderReceiver.ACTION_TOMORROW to R.string.notification_task_reminder_tomorrow_same_time,
                            ).forEach { (action, label) ->
                                TextButton(onClick = { choose(action, extras) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = stringResource(label))
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { finish() }) {
                            Text(text = stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
        }
    }

    private fun choose(action: String, extras: Bundle) {
        sendBroadcast(
            Intent(this, TaskReminderReceiver::class.java).apply {
                this.action = action
                data = intent.data
                putExtras(extras)
            },
        )
        finish()
    }
}
