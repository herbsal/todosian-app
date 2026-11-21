package com.isotjs.todosian.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.utils.DateMatch
import com.isotjs.todosian.utils.ListHit
import com.isotjs.todosian.utils.ListRecognition
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.NaturalDates
import com.isotjs.todosian.utils.REMINDER_MARKER
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagDisplay
import com.isotjs.todosian.utils.TagHit
import com.isotjs.todosian.utils.TagRecognition
import com.isotjs.todosian.utils.TaskMarkers
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What a new task's words say besides the task: a list ("@Haushalt", "auf die Einkaufsliste"),
 * a date ("morgen 17 Uhr") and tags of the tag note ("5 Minuten", "Alex"). Found in this order,
 * each on the text without the earlier ones, so "in 2 Stunden" stays a time.
 */
data class TaskRecognition(
    val list: ListHit?,
    val date: DateMatch?,
    val tags: List<TagHit>,
    /** The text without list and date words; the tag hits point into this text. */
    val baseText: String,
)

object TaskInputRecognizer {
    fun recognize(
        text: String,
        definitions: TagDefinitions,
        listNames: List<String> = emptyList(),
        recognizeTagsAndLists: Boolean,
        ignoreDate: Boolean,
        ignoreList: Boolean = false,
        ignoredTags: Set<String> = emptySet(),
        now: LocalDateTime = LocalDateTime.now(),
    ): TaskRecognition {
        val list = if (recognizeTagsAndLists && !ignoreList) ListRecognition.find(text, listNames) else null
        val withoutList = list?.let { ListRecognition.remove(text, it) } ?: text
        val date = if (ignoreDate) null else NaturalDates.find(withoutList, now)
        val base = date?.text ?: withoutList
        val tags = if (recognizeTagsAndLists) {
            TagRecognition.find(base, definitions).filter { it.term.primaryTag !in ignoredTags }
        } else {
            emptyList()
        }
        return TaskRecognition(list, date, tags, base)
    }

    /** The task text and meta to save. */
    fun build(
        recognition: TaskRecognition,
        meta: MarkdownParser.TasksMeta,
        removeWords: Boolean,
        tasksPlugin: Boolean,
        markers: TaskMarkers,
    ): Pair<String, MarkdownParser.TasksMeta> {
        val text = TagRecognition.apply(recognition.baseText, recognition.tags, removeWords)
        return applyRecognizedDate(recognition.date?.copy(text = text), text, meta, tasksPlugin, markers)
    }
}

/**
 * Moves a date found in the text into the task: always as 📅 due date, with a time also as ⏰
 * reminder on that day ("heute 14 Uhr" = due today, reminder 14:00). Obsidian Tasks dates have no
 * time, so the ⏰ carries it. Without Obsidian Tasks support the markers go into the text itself.
 */
internal fun applyRecognizedDate(
    match: DateMatch?,
    text: String,
    meta: MarkdownParser.TasksMeta,
    tasksPlugin: Boolean,
    markers: TaskMarkers,
): Pair<String, MarkdownParser.TasksMeta> {
    if (match == null) return text to meta
    val reminder = match.dateTime?.let { MarkdownParser.formatReminder(it) }
    return if (tasksPlugin) {
        match.text to meta.copy(reminder = reminder ?: meta.reminder, dueDate = meta.dueDate ?: match.date.toString())
    } else {
        // ⏰ stays right behind the text, the date fields follow.
        val suffix = listOfNotNull(reminder?.let { "$REMINDER_MARKER $it" }, "${markers.due} ${match.date}").joinToString(" ")
        "${match.text} $suffix" to meta
    }
}

/** ✨ at the end of the text field: turns finding tags (and lists) in the words on or off. */
@Composable
fun RecognitionToggle(enabled: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (enabled) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
            contentDescription = stringResource(
                if (enabled) R.string.recognition_toggle_on else R.string.recognition_toggle_off,
            ),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RecognizedDateChip(match: DateMatch, onIgnore: (() -> Unit)?) {
    val locale = LocalConfiguration.current.locales[0]
    val dateText = match.date.format(DateTimeFormatter.ofPattern("EEE d. MMM", locale))
    val label = match.time?.let { time ->
        stringResource(R.string.category_recognized_reminder, dateText, time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)))
    } ?: stringResource(R.string.category_recognized_due, dateText)
    RemovableChip(label = label, onRemove = onIgnore)
}

/** One chip per recognized tag, e.g. "⏱ 5 Min"; tapping one leaves that tag out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecognizedTagChips(hits: List<TagHit>, onRemove: (TagHit) -> Unit, modifier: Modifier = Modifier) {
    if (hits.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        hits.forEach { hit ->
            RemovableChip(label = "${TagDisplay.iconFor(hit.group)} ${hit.term.label}", onRemove = { onRemove(hit) })
        }
    }
}

@Composable
internal fun RemovableChip(label: String, onRemove: (() -> Unit)?, onClick: (() -> Unit)? = null) {
    InputChip(
        selected = true,
        onClick = { (onClick ?: onRemove)?.invoke() },
        enabled = true,
        label = { Text(text = label) },
        trailingIcon = if (onRemove == null) null else {
            {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.category_recognized_ignore),
                    modifier = Modifier
                        .size(InputChipDefaults.IconSize),
                )
            }
        },
    )
}
