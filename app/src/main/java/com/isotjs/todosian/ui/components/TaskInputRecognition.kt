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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import com.isotjs.todosian.utils.TagGroup
import com.isotjs.todosian.utils.TagHit
import com.isotjs.todosian.utils.TagRecognition
import com.isotjs.todosian.utils.TagTerm
import com.isotjs.todosian.utils.TagText
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

/** Keywords of the tag note already set in [text] as tags, in the note's order. */
internal fun tagsSetIn(text: String, definitions: TagDefinitions): List<Pair<TagGroup, TagTerm>> =
    definitions.groups.flatMap { group -> group.terms.filter { TagText.hasTerm(text, it) }.map { group to it } }

/**
 * One chip with its symbol per tag of the task, e.g. "⏱ 5 Min": the tags already in the text
 * ([set]) and those still written as words ([hits]). ✕ leaves the tag out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecognizedTagChips(
    hits: List<TagHit>,
    onRemove: (TagHit) -> Unit,
    modifier: Modifier = Modifier,
    set: List<Pair<TagGroup, TagTerm>> = emptyList(),
    onRemoveSet: (TagGroup, TagTerm) -> Unit = { _, _ -> },
) {
    if (hits.isEmpty() && set.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        set.forEach { (group, term) ->
            RemovableChip(label = "${TagDisplay.iconFor(group)} ${term.label}", onRemove = { onRemoveSet(group, term) })
        }
        hits.forEach { hit ->
            RemovableChip(label = "${TagDisplay.iconFor(hit.group)} ${hit.term.label}", onRemove = { onRemove(hit) })
        }
    }
}

/** The field's value for [text]: the typed one with its cursor, or [text] with the cursor at the end. */
internal fun fieldValueFor(field: TextFieldValue, text: String): TextFieldValue =
    if (field.text == text) field else TextFieldValue(text, TextRange(text.length))

/** A typed change in a task's text field, kept on one line. */
internal fun oneLineValue(typed: TextFieldValue): TextFieldValue {
    val oneLine = TaskTextField.oneLine(typed.text)
    return if (oneLine == typed.text) typed else TextFieldValue(oneLine, TextRange(typed.selection.end.coerceAtMost(oneLine.length)))
}

/**
 * True when [typed] is [previous] plus one space or punctuation mark typed at the cursor, the
 * end of a word on the keyboard. Dictation inserts whole words and is left alone: changing the
 * text under it stops the microphone.
 */
internal fun endsTypedWord(previous: String, typed: TextFieldValue): Boolean {
    val text = typed.text
    val at = typed.selection.end - 1
    if (!typed.selection.collapsed || text.length != previous.length + 1 || at !in text.indices) return false
    return !text[at].isLetterOrDigit() && text.removeRange(at, at + 1) == previous
}

/** How long typing must rest after a word before its tag words turn into the tag. */
private const val SETTLE_DELAY_MS = 700L

/**
 * [value] with its tag words turned into tags ([TagRecognition.settle]); with [all] also the last
 * words, as when leaving the field. Null when nothing changes. The map holds the words each tag replaced.
 */
internal fun settleTags(
    value: TextFieldValue,
    definitions: TagDefinitions,
    ignoredTags: Set<String>,
    removeWords: Boolean,
    all: Boolean,
): Pair<TextFieldValue, Map<String, String>>? {
    val settled = TagRecognition.settle(
        text = value.text,
        cursor = value.selection.end,
        definitions = definitions,
        ignoredTags = ignoredTags,
        removeWords = removeWords,
        finishedOnly = !all,
    ) ?: return null
    return TextFieldValue(settled.text, TextRange(settled.cursor)) to settled.replaced
}

/**
 * Turns finished tag words into tags a moment after a word was typed on the keyboard
 * ([wordTyped], see [endsTypedWord]); not while the keyboard still composes a word and never
 * during dictation, where changing the text would stop the microphone.
 */
@Composable
internal fun TagSettleEffect(
    value: TextFieldValue,
    wordTyped: Boolean,
    enabled: Boolean,
    definitions: TagDefinitions,
    ignoredTags: Set<String>,
    removeWords: Boolean,
    onSettled: (TextFieldValue, Map<String, String>) -> Unit,
) {
    val current by rememberUpdatedState(value)
    val latest by rememberUpdatedState(onSettled)
    LaunchedEffect(value.text, wordTyped, enabled, definitions) {
        if (!enabled || !wordTyped || definitions.isEmpty) return@LaunchedEffect
        delay(SETTLE_DELAY_MS)
        val now = current
        if (now.text != value.text || now.composition != null) return@LaunchedEffect
        settleTags(now, definitions, ignoredTags, removeWords, all = false)?.let { (settled, replaced) -> latest(settled, replaced) }
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
