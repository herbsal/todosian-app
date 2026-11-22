package com.isotjs.todosian.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isotjs.todosian.R
import com.isotjs.todosian.data.ai.AiCleanup
import com.isotjs.todosian.data.ai.AiCleanupResult
import com.isotjs.todosian.data.ai.AiFailure
import com.isotjs.todosian.data.ai.AiStatus
import com.isotjs.todosian.data.settings.AppSettings
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.utils.TaskMarkers
import com.isotjs.todosian.utils.ai.AiTextGuard
import kotlinx.coroutines.launch

/**
 * ✨ with a small sign in its upper corner. Two of them sit side by side at a task's text field:
 * "#" switches tag recognition, "AI" cleans the text up with Gemini Nano.
 */
@Composable
fun BadgedSparkle(badge: String, filled: Boolean, tint: Color, badgeColor: Color = tint) {
    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = if (filled) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp).align(Alignment.BottomStart),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-1).dp),
        ) {
            Text(
                text = badge,
                color = badgeColor,
                fontSize = 9.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 1.dp),
            )
        }
    }
}

/** What the AI cleanup of one text field is doing; one per field. */
@Stable
class AiCleanupState {
    var running by mutableStateOf(false)
        internal set

    /** The text before the last cleanup and the text it produced, for "Undo". */
    var original by mutableStateOf<String?>(null)
        internal set
    var cleaned by mutableStateOf<String?>(null)
        internal set
    var failure by mutableStateOf<AiFailure?>(null)
        internal set
    var unchanged by mutableStateOf(false)
        internal set

    /** Why the AI's answer was not taken, and the answer itself. */
    var rejection by mutableStateOf<AiTextGuard.Rejection?>(null)
        internal set
    var rejectedAnswer by mutableStateOf<String?>(null)
        internal set

    /** The text a failure or "nothing to clean up" belongs to; typing on hides the note. */
    internal var noticeText by mutableStateOf<String?>(null)

    internal fun clearNotice() {
        failure = null
        rejection = null
        rejectedAnswer = null
        unchanged = false
        noticeText = null
    }
}

@Composable
fun rememberAiCleanupState(): AiCleanupState = remember { AiCleanupState() }

/** The button is shown when the setting is on and the device has (or can load) Gemini Nano. */
@Composable
fun aiCleanupShown(settings: AppSettings): Boolean {
    val status by AiCleanup.status.collectAsStateWithLifecycle()
    LaunchedEffect(settings.aiCleanupEnabled, settings.aiModel) {
        AiCleanup.preferred = settings.preferredAiModel()
        if (settings.aiCleanupEnabled) AiCleanup.refresh()
    }
    return settings.aiCleanupEnabled && status != AiStatus.Unknown && status != AiStatus.Unavailable
}

/**
 * The "AI" button at the end of a task's text field. Tapping it cleans up [currentText] and hands
 * the result to [onCleaned]; nothing is loaded or sent anywhere without the person's yes.
 * [protect] gives the parts of the text the AI must keep, e.g. words that become a tag.
 * [verify] may reject a result (e.g. when the words for a date changed).
 */
@Composable
fun AiCleanupButton(
    state: AiCleanupState,
    settings: AppSettings,
    appSettingsRepository: AppSettingsRepository,
    currentText: () -> String,
    onCleaned: (String) -> Unit,
    markers: TaskMarkers = TaskMarkers.Default,
    protect: (text: String) -> List<IntRange> = { emptyList() },
    verify: (original: String, cleaned: String) -> Boolean = { _, _ -> true },
) {
    val status by AiCleanup.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var askSettings by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var askHint by remember { mutableStateOf(false) }

    fun run() {
        val start = currentText()
        if (start.isBlank() || state.running) return
        state.clearNotice()
        state.running = true
        scope.launch {
            val result = AiCleanup.clean(start, markers, protect(start)) { cleaned -> verify(start, cleaned) }
            state.running = false
            // Typed on while the AI worked: the newer words win, the result is dropped.
            if (currentText() != start) return@launch
            state.noticeText = start
            when (result) {
                is AiCleanupResult.Cleaned -> {
                    state.original = start
                    state.cleaned = result.text
                    onCleaned(result.text)
                }
                AiCleanupResult.Unchanged -> state.unchanged = true
                is AiCleanupResult.Failed -> if (result.reason != AiFailure.LEFT_APP) {
                    state.failure = result.reason
                    state.rejection = result.rejection
                    state.rejectedAnswer = result.answer
                }
            }
        }
    }

    fun tap() {
        when (status) {
            AiStatus.Available -> if (!settings.aiCleanupHintShown) askHint = true else run()
            // Not ready yet: offer the AI settings, where the person sees what is missing and can load it.
            else -> askSettings = true
        }
    }

    val description = stringResource(R.string.ai_cleanup_button)
    IconButton(
        onClick = ::tap,
        enabled = !state.running,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        if (state.running || status is AiStatus.Downloading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
        } else {
            BadgedSparkle(
                badge = "AI",
                filled = status == AiStatus.Available,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
    }

    if (askSettings) {
        AlertDialog(
            onDismissRequest = { askSettings = false },
            icon = { BadgedSparkle(badge = "AI", filled = false, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text(text = stringResource(R.string.ai_cleanup_not_ready_title)) },
            text = {
                Text(
                    text = stringResource(
                        when (status) {
                            AiStatus.Downloadable -> R.string.ai_cleanup_not_ready_downloadable
                            is AiStatus.Downloading -> R.string.ai_cleanup_not_ready_downloading
                            AiStatus.Preparing -> R.string.ai_cleanup_not_ready_preparing
                            else -> R.string.ai_cleanup_not_ready_other
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askSettings = false
                    showSettings = true
                }) { Text(text = stringResource(R.string.ai_cleanup_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { askSettings = false }) {
                    Text(text = stringResource(R.string.ai_cleanup_download_cancel))
                }
            },
        )
    }

    if (showSettings) {
        AiSettingsDialog(settings = settings, appSettingsRepository = appSettingsRepository, onDismiss = { showSettings = false })
    }

    if (askHint) {
        AlertDialog(
            onDismissRequest = { askHint = false },
            icon = { BadgedSparkle(badge = "AI", filled = true, tint = MaterialTheme.colorScheme.tertiary) },
            title = { Text(text = stringResource(R.string.ai_cleanup_hint_title)) },
            text = { Text(text = stringResource(R.string.ai_cleanup_hint_message)) },
            confirmButton = {
                TextButton(onClick = {
                    askHint = false
                    appSettingsRepository.setAiCleanupHintShown()
                    run()
                }) { Text(text = stringResource(R.string.ai_cleanup_hint_confirm)) }
            },
        )
    }
}

/**
 * Under the text field: a thin bar while the AI works, then "✨ Cleaned up with AI · Undo" as long
 * as the text is still the cleaned one, or why it did not work.
 */
@Composable
fun AiCleanupNotice(state: AiCleanupState, currentText: String, onUndo: (String) -> Unit) {
    if (state.running) {
        LinearProgressIndicator(
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        return
    }
    val original = state.original
    val noticeShown = state.noticeText == currentText
    val failure = state.failure.takeIf { noticeShown }
    when {
        original != null && state.cleaned == currentText -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BadgedSparkle(badge = "AI", filled = true, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.ai_cleanup_done),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = {
                state.original = null
                state.cleaned = null
                onUndo(original)
            }) { Text(text = stringResource(R.string.ai_cleanup_undo)) }
        }
        failure != null -> Column(modifier = Modifier.padding(top = 4.dp)) {
            val rejection = state.rejection
            Text(
                text = if (rejection == null) {
                    stringResource(failureText(failure))
                } else {
                    stringResource(R.string.ai_cleanup_failed_unsafe_reason, stringResource(rejectionText(rejection)))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            state.rejectedAnswer?.takeIf { it.isNotBlank() }?.let { answer ->
                Text(
                    text = stringResource(R.string.ai_cleanup_rejected_answer, answer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        noticeShown && state.unchanged -> Text(
            text = stringResource(R.string.ai_cleanup_unchanged),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun rejectionText(rejection: AiTextGuard.Rejection): Int = when (rejection) {
    AiTextGuard.Rejection.EMPTY -> R.string.ai_cleanup_rejected_empty
    AiTextGuard.Rejection.LINE_BREAK -> R.string.ai_cleanup_rejected_line_break
    AiTextGuard.Rejection.TOO_LONG -> R.string.ai_cleanup_rejected_too_long
    AiTextGuard.Rejection.PLACEHOLDERS -> R.string.ai_cleanup_rejected_placeholders
    AiTextGuard.Rejection.NO_WORDS -> R.string.ai_cleanup_rejected_no_words
    AiTextGuard.Rejection.RECOGNITION -> R.string.ai_cleanup_rejected_recognition
}

private fun failureText(failure: AiFailure): Int = when (failure) {
    AiFailure.BUSY -> R.string.ai_cleanup_failed_busy
    AiFailure.DAILY_LIMIT -> R.string.ai_cleanup_failed_limit
    AiFailure.TIMEOUT -> R.string.ai_cleanup_failed_timeout
    AiFailure.UNSAFE -> R.string.ai_cleanup_failed_unsafe
    AiFailure.NOT_READY -> R.string.ai_cleanup_failed_not_ready
    AiFailure.LEFT_APP, AiFailure.OTHER -> R.string.ai_cleanup_failed_other
}
