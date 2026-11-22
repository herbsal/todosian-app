package com.isotjs.todosian.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.settings.AppSettings
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.data.settings.NewTodoFilePosition
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.NaturalDates
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagRecognition
import com.isotjs.todosian.utils.TagText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** Name of the list new tasks go to when no list is named or chosen and no default list is set. */
internal const val INBOX_LIST_NAME = "Inbox"

/** Lists by their own name, also when they sit in a subfolder ("Familie/Kinder"). */
internal fun listsByName(categories: List<Category>): Map<String, Category> =
    categories.associateBy { it.displayName.substringAfterLast('/') }

internal fun inboxOf(categories: List<Category>): Category? =
    listsByName(categories).entries.firstOrNull { it.key.equals(INBOX_LIST_NAME, ignoreCase = true) }?.value

/**
 * The list chip of a new task: where it goes. Tapping it picks another list; with a list named
 * in the words ([recognized]) or chosen ([chosen]), the menu also offers to ignore that.
 */
@Composable
internal fun ListPickerChip(
    categories: List<Category>,
    target: Category?,
    recognized: Boolean,
    chosen: Boolean,
    onChoose: (Category) -> Unit,
    onIgnore: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        val label = target?.displayName?.substringAfterLast('/') ?: INBOX_LIST_NAME
        RemovableChip(
            label = stringResource(R.string.quick_add_list_chip, label),
            onRemove = null,
            onClick = { showMenu = true },
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            if (recognized || chosen) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.quick_add_list_not_recognized)) },
                    onClick = {
                        onIgnore()
                        showMenu = false
                    },
                )
            }
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(text = category.displayName) },
                    onClick = {
                        onChoose(category)
                        showMenu = false
                    },
                )
            }
        }
    }
}

/**
 * "Add task" with all options, the same from everywhere: a list, quick capture pulled up, the
 * home screen or the daily focus. The list can be changed here; [initialListUri] is chosen at
 * first (the list it was opened from, named [initialListName]), else a list named in the words, else [defaultListUri],
 * else "Inbox" (created when missing). The sheet opens over the current screen and leaves it there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskSheet(
    categories: List<Category>,
    settings: AppSettings,
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    onDismiss: () -> Unit,
    onSaved: (listName: String) -> Unit,
    onFailed: () -> Unit,
    initialListUri: Uri? = null,
    initialListName: String? = null,
    initialText: String = "",
    defaultListUri: Uri? = null,
) {
    val scope = rememberCoroutineScope()
    val tasksPlugin = settings.enableTasksPluginSupport
    val markers = remember(settings) { settings.toTaskMarkers() }

    var text by remember { mutableStateOf(initialText) }
    var field by remember { mutableStateOf(TextFieldValue()) }
    var meta by remember {
        mutableStateOf(if (tasksPlugin) MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString()) else MarkdownParser.TasksMeta())
    }
    var firstStep by remember { mutableStateOf<String?>(null) }
    var ignoreDate by remember { mutableStateOf(false) }
    var ignoreList by remember { mutableStateOf(false) }
    var ignoredTags by remember { mutableStateOf(emptySet<String>()) }
    // Words that tag words were turned into, to bring them back when a tag is removed.
    var replacedWords by remember { mutableStateOf(emptyMap<String, String>()) }
    var wordTyped by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    var chosenList by remember { mutableStateOf<Category?>(null) }
    var saving by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val aiCleanup = rememberAiCleanupState()
    val aiShown = aiCleanupShown(settings)

    // Changes are not dropped by a swipe, a tap beside the sheet or Back: first ask.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && text.isNotBlank() && !closing) {
                askDiscard = true
                false
            } else {
                true
            }
        },
    )

    val byName = remember(categories) { listsByName(categories) }
    // While the lists are still loading, the list it was opened from stands for itself.
    val initialList = remember(categories, initialListUri, initialListName) {
        categories.firstOrNull { it.uri == initialListUri }
            ?: initialListUri?.let { uri -> initialListName?.let { Category(it, uri, 0, 0, 0, 0) } }
    }
    val defaultList = remember(categories, defaultListUri) { categories.firstOrNull { it.uri == defaultListUri } }
    val inbox = remember(categories) { inboxOf(categories) }

    // Tags come from the folder of the list the task goes to; the list comes from the words first.
    var definitions by remember { mutableStateOf(TagDefinitions.EMPTY) }
    val recognition = remember(text, ignoreDate, ignoreList, ignoredTags, chosenList, settings.tagRecognition, definitions, byName) {
        TaskInputRecognizer.recognize(
            text = text,
            definitions = definitions,
            listNames = byName.keys.toList(),
            recognizeTagsAndLists = settings.tagRecognition,
            ignoreDate = ignoreDate,
            ignoreList = ignoreList || chosenList != null,
            ignoredTags = ignoredTags,
        )
    }
    val target: Category? = chosenList ?: recognition.list?.let { byName[it.name] } ?: initialList ?: defaultList ?: inbox
    val loadedDefinitions by produceState(fileRepository.cachedTagDefinitions(target?.uri) ?: TagDefinitions.EMPTY, target?.uri) {
        value = fileRepository.getTagDefinitions(target?.uri)
    }
    LaunchedEffect(loadedDefinitions) { definitions = loadedDefinitions }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun setText(value: TextFieldValue, replaced: Map<String, String> = emptyMap()) {
        replacedWords = replacedWords + replaced
        field = value
        text = value.text
    }

    fun close() {
        closing = true
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun save() {
        if (text.isBlank() || saving) return
        saving = true
        scope.launch {
            val (taskText, taskMeta) = TaskInputRecognizer.build(
                recognition = recognition,
                meta = meta,
                removeWords = settings.tagRecognitionRemoveWords,
                tasksPlugin = tasksPlugin,
                markers = markers,
            )
            val step = firstStep?.trim()?.takeIf { it.isNotEmpty() }
            val (stepText, stepMeta) = step?.let { raw ->
                applyRecognizedDate(
                    NaturalDates.find(raw, LocalDateTime.now()),
                    raw,
                    MarkdownParser.TasksMeta(createdDate = taskMeta.createdDate),
                    tasksPlugin,
                    markers,
                )
            } ?: (null to null)
            val targetUri: Uri? = target?.uri ?: fileRepository.createCategoryByName(INBOX_LIST_NAME).getOrNull()
            val listName = target?.displayName?.substringAfterLast('/') ?: INBOX_LIST_NAME
            val written = targetUri != null && taskText.isNotBlank() && fileRepository.updateLines(targetUri) { lines ->
                val added = MarkdownParser.addTodo(
                    lines = lines,
                    text = taskText,
                    meta = if (tasksPlugin) taskMeta else null,
                    enableTasksPlugin = tasksPlugin,
                    addAtStart = settings.newTodoFilePosition == NewTodoFilePosition.TOP,
                    markers = markers,
                )
                if (stepText == null || added == lines) return@updateLines added
                val parentIndex = added.indices.firstOrNull { it >= lines.size || added[it] != lines[it] }
                    ?: return@updateLines added
                MarkdownParser.addSubTodo(
                    lines = added,
                    parentLineIndex = parentIndex,
                    text = stepText,
                    meta = if (tasksPlugin) stepMeta else null,
                    enableTasksPlugin = tasksPlugin,
                    markers = markers,
                ) ?: added
            }.getOrNull() != null
            saving = false
            if (written) {
                closing = true
                sheetState.hide()
                onSaved(listName)
            } else {
                onFailed()
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(text = stringResource(R.string.category_discard_draft_title)) },
            text = { Text(text = stringResource(R.string.category_discard_draft_message)) },
            confirmButton = {
                TextButton(onClick = {
                    askDiscard = false
                    close()
                }) { Text(text = stringResource(R.string.category_discard_draft_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) {
                    Text(text = stringResource(R.string.category_discard_draft_keep))
                }
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.ime)
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState())
                .clearFocusOnTap(),
        ) {
            Text(text = stringResource(R.string.category_add_todo_title), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = fieldValueFor(field, text),
                onValueChange = { typed ->
                    val value = oneLineValue(typed)
                    if (value.text != text) wordTyped = endsTypedWord(text, value)
                    setText(value)
                },
                maxLines = TaskTextField.MAX_LINES,
                keyboardOptions = TaskTextField.keyboardOptions,
                label = { Text(text = stringResource(R.string.category_add_todo_hint)) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (aiShown) {
                            val recognizeWords: (String, LocalDateTime) -> TaskRecognition = { words, now ->
                                TaskInputRecognizer.recognize(
                                    text = words,
                                    definitions = definitions,
                                    listNames = byName.keys.toList(),
                                    recognizeTagsAndLists = settings.tagRecognition,
                                    ignoreDate = ignoreDate,
                                    ignoreList = ignoreList || chosenList != null,
                                    ignoredTags = ignoredTags,
                                    now = now,
                                )
                            }
                            AiCleanupButton(
                                state = aiCleanup,
                                settings = settings,
                                appSettingsRepository = appSettingsRepository,
                                currentText = { text },
                                onCleaned = { cleaned -> setText(TextFieldValue(cleaned, TextRange(cleaned.length))) },
                                markers = markers,
                                protect = { words -> protectedWords(words, recognizeWords) },
                                verify = { original, cleaned -> keepsRecognized(original, cleaned, recognizeWords) },
                            )
                        }
                        RecognitionToggle(
                            enabled = settings.tagRecognition,
                            onToggle = { appSettingsRepository.setTagRecognition(!settings.tagRecognition) },
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusEvent { state ->
                        if (!state.isFocused && fieldFocused && settings.tagRecognition) {
                            // Leaving the field: the last tag words (also dictated ones) become tags.
                            settleTags(fieldValueFor(field, text), definitions, ignoredTags, settings.tagRecognitionRemoveWords, all = true)
                                ?.let { (value, replaced) -> setText(value, replaced) }
                        }
                        fieldFocused = state.isFocused
                    },
            )
            AiCleanupNotice(
                state = aiCleanup,
                currentText = text,
                onUndo = { original -> setText(TextFieldValue(original, TextRange(original.length))) },
            )
            // Finished tag words typed on the keyboard turn into the tag: "5 Minuten " → "#5min ".
            TagSettleEffect(
                value = fieldValueFor(field, text),
                wordTyped = wordTyped,
                enabled = settings.tagRecognition,
                definitions = definitions,
                ignoredTags = ignoredTags,
                removeWords = settings.tagRecognitionRemoveWords,
                onSettled = { value, replaced ->
                    wordTyped = false
                    setText(value, replaced)
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            ListPickerChip(
                categories = categories,
                target = target,
                recognized = recognition.list != null,
                chosen = chosenList != null,
                onChoose = { chosenList = it },
                onIgnore = {
                    chosenList = null
                    ignoreList = true
                },
            )
            recognition.date?.let { date ->
                Spacer(modifier = Modifier.height(8.dp))
                RecognizedDateChip(match = date, onIgnore = { ignoreDate = true })
            }
            // The task's tags with their symbol: those set in the text and those still in words.
            val tagsSet = tagsSetIn(text, definitions)
            if (recognition.tags.isNotEmpty() || tagsSet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                RecognizedTagChips(
                    hits = recognition.tags,
                    onRemove = { hit -> ignoredTags = ignoredTags + hit.term.primaryTag },
                    set = tagsSet,
                    onRemoveSet = { _, term ->
                        val tag = term.tags.firstOrNull { t -> TagText.tagsIn(text).any { it.equals(t, ignoreCase = true) } } ?: term.primaryTag
                        ignoredTags = ignoredTags + term.primaryTag
                        text = TagRecognition.unsettle(text, tag, replacedWords[tag])
                    },
                )
            }

            // U1: an optional small first step, saved as a subtask with its own ⏰.
            val step = firstStep
            if (step == null) {
                TextButton(onClick = { firstStep = "" }) {
                    Text(text = stringResource(R.string.category_first_step_add))
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = step,
                    onValueChange = { firstStep = TaskTextField.oneLine(it) },
                    maxLines = TaskTextField.MAX_LINES,
                    keyboardOptions = TaskTextField.keyboardOptions,
                    label = { Text(text = stringResource(R.string.category_first_step)) },
                    placeholder = { Text(text = stringResource(R.string.category_first_step_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val stepMatch = remember(step) { NaturalDates.find(step, LocalDateTime.now()) }
                if (stepMatch != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    RecognizedDateChip(match = stepMatch, onIgnore = null)
                }
            }

            if (!definitions.isEmpty) {
                Spacer(modifier = Modifier.height(8.dp))
                TagQuickPicker(
                    definitions = definitions,
                    text = text,
                    onTextChange = { text = it },
                    recognized = recognition.tags,
                    onOverride = { hit -> ignoredTags = ignoredTags + hit.term.primaryTag },
                )
            }

            if (tasksPlugin) {
                Spacer(modifier = Modifier.height(12.dp))
                // A date recognized in the words shows in the date rows right away. Changing a row
                // takes it over: its words leave the text and the rows hold the date from then on.
                val recognizedDate = recognition.date
                val shownMeta = if (recognizedDate == null) meta else {
                    applyRecognizedDate(recognizedDate, text, meta, tasksPlugin = true, markers = markers).second
                }
                TasksMetaEditor(
                    mode = TodoSheetMode.Add,
                    meta = shownMeta,
                    onMetaChange = { changed ->
                        if (recognizedDate != null) {
                            // The date words leave the text (list words too; the list stays chosen).
                            if (chosenList == null) chosenList = recognition.list?.let { byName[it.name] }
                            text = recognizedDate.text
                        }
                        meta = changed
                    },
                    useEmojisInUi = settings.tasksPluginUseEmojisInUi,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { close() }) { Text(text = stringResource(R.string.action_cancel)) }
                TextButton(onClick = { save() }, enabled = text.isNotBlank() && !saving) {
                    Text(text = stringResource(R.string.action_save))
                }
            }
        }
    }
}
