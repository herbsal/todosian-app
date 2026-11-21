package com.isotjs.todosian.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.isotjs.todosian.R
import com.isotjs.todosian.data.NoteConflict
import com.isotjs.todosian.data.conflict.ConflictResolver
import com.isotjs.todosian.data.conflict.MergeChoice
import com.isotjs.todosian.data.conflict.MergeOutcome
import com.isotjs.todosian.data.conflict.MergePart
import com.isotjs.todosian.data.conflict.MergeSource
import com.isotjs.todosian.data.conflict.PreparedMerge
import com.isotjs.todosian.utils.TaskMarkers
import kotlinx.coroutines.launch

/**
 * The proposed merge of a note and its Syncthing conflict copy: what comes from which side, the
 * contradictions to choose, and one button that writes the merge and deletes the copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConflictSheet(
    conflict: NoteConflict,
    resolver: ConflictResolver,
    markers: TaskMarkers,
    onDismiss: () -> Unit,
    onMerged: (backupId: String) -> Unit,
    onOpenInObsidian: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var prepared by remember(conflict) { mutableStateOf<PreparedMerge?>(null) }
    var loading by remember(conflict) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val choices = remember(conflict) { mutableStateMapOf<Int, MergeChoice>() }

    fun show(next: PreparedMerge?) {
        prepared = next
        choices.clear()
        // Without a base nothing is known about who changed what: start with keeping both.
        if (next != null && !next.result.hasBase) next.result.contradictions.forEach { choices[it.id] = MergeChoice.BOTH }
    }

    LaunchedEffect(conflict) {
        show(resolver.prepare(conflict, markers))
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_conflict_title, conflict.relPath.substringAfterLast('/')),
                style = MaterialTheme.typography.titleLarge,
            )
            val current = prepared
            when {
                loading -> CircularProgressIndicator()
                current == null -> Text(stringResource(R.string.conflict_unreadable), style = MaterialTheme.typography.bodyMedium)
                else -> MergeDetails(current, choices)
            }
            message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            val result = current?.result
            Button(
                enabled = !busy && result != null && result.contradictions.all { it.id in choices },
                onClick = {
                    val target = current ?: return@Button
                    busy = true
                    message = null
                    scope.launch {
                        when (val outcome = resolver.apply(target, choices.toMap(), markers)) {
                            is MergeOutcome.Done -> onMerged(outcome.backupId)
                            is MergeOutcome.Changed -> {
                                show(outcome.prepared)
                                message = R.string.conflict_changed
                            }
                            MergeOutcome.Failed -> message = R.string.conflict_failed
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.conflict_merge)) }
            OutlinedButton(onClick = onOpenInObsidian, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.conflict_open_obsidian))
            }
        }
    }
}

@Composable
private fun MergeDetails(prepared: PreparedMerge, choices: MutableMap<Int, MergeChoice>) {
    val result = prepared.result
    if (!result.hasBase) {
        Text(stringResource(R.string.conflict_no_base), style = MaterialTheme.typography.bodyMedium)
    }
    val counts = listOf(
        R.string.conflict_summary_original to result.places(MergeSource.ORIGINAL),
        R.string.conflict_summary_copy to result.places(MergeSource.COPY),
        R.string.conflict_summary_fields to result.places(MergeSource.FIELDS),
        R.string.conflict_summary_added to result.places(MergeSource.ADDED_BOTH),
    ).filter { it.second > 0 }
    if (counts.isEmpty() && result.contradictions.isEmpty()) {
        Text(stringResource(R.string.conflict_summary_none), style = MaterialTheme.typography.bodyMedium)
    }
    counts.forEach { (text, count) ->
        Text(stringResource(text, count), style = MaterialTheme.typography.bodyMedium)
    }
    if (result.contradictions.isEmpty()) return
    Text(stringResource(R.string.conflict_questions), style = MaterialTheme.typography.titleSmall)
    result.contradictions.forEach { question -> Question(question, choices[question.id]) { choices[question.id] = it } }
}

@Composable
private fun Question(question: MergePart.Contradiction, chosen: MergeChoice?, onChoose: (MergeChoice) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Side(stringResource(R.string.conflict_side_original), question.original)
            Side(stringResource(R.string.conflict_side_copy), question.copy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    MergeChoice.ORIGINAL to R.string.conflict_side_original,
                    MergeChoice.COPY to R.string.conflict_side_copy,
                    MergeChoice.BOTH to R.string.conflict_side_both,
                ).forEach { (choice, label) ->
                    FilterChip(selected = chosen == choice, onClick = { onChoose(choice) }, label = { Text(stringResource(label)) })
                }
            }
        }
    }
}

@Composable
private fun Side(label: String, lines: List<String>) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        text = if (lines.isEmpty()) stringResource(R.string.conflict_deleted) else lines.joinToString("\n"),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
    )
}
