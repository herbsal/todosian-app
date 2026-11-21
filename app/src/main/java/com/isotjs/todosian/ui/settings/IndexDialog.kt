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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import com.isotjs.todosian.data.settings.DeletedNoteReminders
import com.isotjs.todosian.data.settings.TaskIdMode
import com.isotjs.todosian.data.settings.ConflictMode
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.conflict.ConflictResolver
import com.isotjs.todosian.data.conflict.MergeBackup
import com.isotjs.todosian.data.settings.normalizeFolderPath
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
    val resources = LocalResources.current

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

/** The folders whose notes the app leaves out: a list with remove buttons and a field to add one. */
@Composable
internal fun ExcludedFoldersDialog(
    folders: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    fun add() {
        val path = normalizeFolderPath(input)
        if (path.isNotEmpty()) onChange(folders + path)
        input = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_excluded_folders)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_excluded_folders_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                folders.sortedBy { it.lowercase() }.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(text = folder, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(folders - folder) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_excluded_folders_remove, folder))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.settings_excluded_folders_placeholder)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { add() }, enabled = normalizeFolderPath(input).isNotEmpty()) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.settings_excluded_folders_add))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                add()
                onDismiss()
            }) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
internal fun deletedNoteRemindersLabel(mode: DeletedNoteReminders): String = when (mode) {
    DeletedNoteReminders.CANCEL_SILENTLY -> stringResource(R.string.settings_deleted_note_silent)
    DeletedNoteReminders.NOTIFY -> stringResource(R.string.settings_deleted_note_notify)
}

@Composable
internal fun conflictModeLabel(mode: ConflictMode): String = when (mode) {
    ConflictMode.SUGGEST -> stringResource(R.string.settings_conflicts_suggest)
    ConflictMode.AUTO -> stringResource(R.string.settings_conflicts_auto)
}

/** Merged conflicts of the last 30 days, each with "Restore" (note and copy as before). */
@Composable
internal fun MergedConflictsDialog(fileRepository: FileRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val resolver = remember { ConflictResolver(context.applicationContext, fileRepository) }
    var backups by remember { mutableStateOf<List<MergeBackup>?>(null) }
    var refreshes by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshes) { backups = withContext(Dispatchers.IO) { resolver.backups() } }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.settings_merged_conflicts)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_merged_conflicts_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val list = backups
                if (list != null && list.isEmpty()) {
                    Text(stringResource(R.string.settings_merged_conflicts_none), style = MaterialTheme.typography.bodyLarge)
                }
                list.orEmpty().forEach { backup ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(backup.notePath.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = DateUtils.getRelativeTimeSpanString(backup.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val ok = resolver.restore(backup.id)
                                    result = resources.getString(if (ok) R.string.settings_merged_restored else R.string.settings_merged_restore_failed)
                                    busy = false
                                    refreshes++
                                }
                            },
                        ) { Text(stringResource(R.string.settings_merged_restore)) }
                    }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
internal fun taskIdModeLabel(mode: TaskIdMode): String = when (mode) {
    TaskIdMode.NEVER -> stringResource(R.string.settings_task_ids_never)
    TaskIdMode.REMINDERS -> stringResource(R.string.settings_task_ids_reminders)
    TaskIdMode.ALL -> stringResource(R.string.settings_task_ids_all)
}

/** The vault name Obsidian links use; empty means the name of the notes folder. */
@Composable
internal fun ObsidianVaultNameDialog(
    current: String,
    folderName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_obsidian_vault)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_obsidian_vault_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(folderName) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name.trim())
                onDismiss()
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
