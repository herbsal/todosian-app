package com.isotjs.todosian.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.data.IndexStats
import com.isotjs.todosian.data.VaultSync
import com.isotjs.todosian.data.settings.DeepCheckMode
import com.isotjs.todosian.sync.FolderChangeCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the app knows about the note index, how often it is checked, and the two repair buttons. */
@Composable
internal fun IndexDialog(
    deepCheckMode: DeepCheckMode,
    onDeepCheckModeChange: (DeepCheckMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<IndexStats?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var refreshes by remember { mutableIntStateOf(0) }
    val resources = context.resources

    LaunchedEffect(refreshes) {
        stats = withContext(Dispatchers.IO) { runCatching { VaultSync.get(context).stats() }.getOrNull() }
    }

    fun runRepair(work: suspend () -> String) {
        if (busy) return
        busy = true
        result = null
        scope.launch {
            result = runCatching { withContext(Dispatchers.IO) { work() } }
                .getOrElse { resources.getString(R.string.settings_index_failed) }
            busy = false
            refreshes++
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.settings_index)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val current = stats
                if (current != null) {
                    Text(
                        text = pluralStringResource(R.plurals.settings_index_notes, current.notes, current.notes) + " · " +
                            pluralStringResource(R.plurals.settings_index_tasks, current.tasks, current.tasks),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_index_last_sync, relative(current.lastSync)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (current.lastDeepCheck == null) {
                            stringResource(R.string.settings_index_last_deep_check_never)
                        } else {
                            stringResource(
                                R.string.settings_index_last_deep_check,
                                relative(current.lastDeepCheck),
                                current.lastDeepCheckFixed ?: 0,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = stringResource(R.string.settings_index_deep_check),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.settings_index_deep_check_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DeepCheckMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = mode == deepCheckMode,
                                role = Role.RadioButton,
                                onClick = { onDeepCheckModeChange(mode) },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == deepCheckMode, onClick = null)
                        Text(
                            text = deepCheckModeLabel(mode),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            runRepair {
                                val fixed = FolderChangeCheck.deepCheck(context)
                                resources.getQuantityString(R.plurals.settings_index_check_done, fixed, fixed)
                            }
                        },
                    ) { Text(stringResource(R.string.settings_index_check_now)) }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            runRepair {
                                FolderChangeCheck.rebuild(context)
                                resources.getString(R.string.settings_index_rebuild_done)
                            }
                        },
                    ) { Text(stringResource(R.string.settings_index_rebuild)) }
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.settings_index_working),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

@Composable
internal fun deepCheckModeLabel(mode: DeepCheckMode): String = when (mode) {
    DeepCheckMode.OFF -> stringResource(R.string.settings_index_deep_check_off)
    DeepCheckMode.DAILY_CHARGING -> stringResource(R.string.settings_index_deep_check_daily)
    DeepCheckMode.WEEKLY -> stringResource(R.string.settings_index_deep_check_weekly)
}

@Composable
private fun relative(time: Long?): String =
    if (time == null) {
        stringResource(R.string.settings_index_never)
    } else {
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
