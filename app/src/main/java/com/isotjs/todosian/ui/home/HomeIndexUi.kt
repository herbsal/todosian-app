package com.isotjs.todosian.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.data.NoteConflict
import com.isotjs.todosian.data.SyncProgress
import kotlinx.coroutines.delay

/** Delay before the small indicator shows, so a quick look at the folders shows nothing. */
private const val SHOW_AFTER_MS = 500L

/**
 * A small spinning circle for the top bar while changed notes are read. It shows only after half
 * a second; a tap tells what is going on.
 */
@Composable
internal fun SyncIndicator(progress: SyncProgress?) {
    var visible by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(progress != null) {
        if (progress == null) {
            visible = false
            details = false
        } else {
            delay(SHOW_AFTER_MS)
            visible = true
        }
    }
    if (!visible || progress == null) return
    val remaining = (progress.total - progress.done).coerceAtLeast(1)
    Box {
        IconButton(onClick = { details = true }) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        DropdownMenu(expanded = details, onDismissRequest = { details = false }) {
            Text(
                text = pluralStringResource(R.plurals.home_sync_reading, remaining, remaining),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** The first build of the index: nothing to show yet, so the progress goes in the middle. */
@Composable
internal fun FirstBuildProgress(progress: SyncProgress, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(R.string.home_index_first_build, progress.done, progress.total),
                style = MaterialTheme.typography.bodyLarge,
            )
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A Syncthing conflict copy next to a note; its tasks are not shown twice. */
@Composable
internal fun ConflictCard(conflict: NoteConflict, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.home_conflict_title, conflict.relPath.substringAfterLast('/')),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.home_conflict_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
