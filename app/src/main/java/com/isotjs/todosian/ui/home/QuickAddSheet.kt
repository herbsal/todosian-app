package com.isotjs.todosian.ui.home

import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.isotjs.todosian.utils.ListRecognition
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.settings.AppSettings
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.data.settings.NewTodoFilePosition
import com.isotjs.todosian.ui.components.RecognitionToggle
import com.isotjs.todosian.ui.components.RecognizedDateChip
import com.isotjs.todosian.ui.components.RecognizedTagChips
import com.isotjs.todosian.ui.components.RemovableChip
import com.isotjs.todosian.ui.components.TaskInputRecognizer
import com.isotjs.todosian.ui.components.TaskTextField
import com.isotjs.todosian.ui.components.fieldValueFor
import com.isotjs.todosian.ui.components.tagsSetIn
import com.isotjs.todosian.ui.components.typedTaskText
import com.isotjs.todosian.utils.TagRecognition
import com.isotjs.todosian.utils.TagText
import androidx.compose.ui.text.input.TextFieldValue
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TagDefinitions
import kotlinx.coroutines.launch
import java.time.LocalDate

/** How far the sheet is pulled up to open the normal task sheet. */
private val PULL_UP_DISTANCE = 40.dp

/** Name of the list quick tasks go to when no list is named and no default list is set. */
private const val INBOX_NAME = "Inbox"

/**
 * ⚡ Quick capture from the home screen: one text field. The list comes from the words
 * ("@Haushalt", "auf die Einkaufsliste"), else the default list from the settings, else "Inbox"
 * (created when missing). Dates and tags are recognized as in the task sheet.
 * Pulling the sheet up (or "All options") opens the normal task sheet of that list with the text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickAddSheet(
    categories: List<Category>,
    settings: AppSettings,
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    preferencesManager: PreferencesManager,
    onDismiss: () -> Unit,
    onSaved: (listName: String) -> Unit,
    onFailed: () -> Unit,
    onOpenFullAdd: (listUri: Uri?, text: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // Typed words are not dropped by a swipe, a tap beside the sheet or Back: first ask.
    var askDiscard by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
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
    val scope = rememberCoroutineScope()
    var ignoreDate by remember { mutableStateOf(false) }
    var ignoreList by remember { mutableStateOf(false) }
    var ignoredTags by remember { mutableStateOf(emptySet<String>()) }
    // Words that finished tag words were turned into, to bring them back when a tag is removed.
    var replacedWords by remember { mutableStateOf(emptyMap<String, String>()) }
    var field by remember { mutableStateOf(TextFieldValue()) }
    var chosenList by remember { mutableStateOf<Category?>(null) }
    var showListMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Lists are matched by their own name, also when they sit in a subfolder ("Familie/Kinder").
    val byName = remember(categories) { categories.associateBy { it.displayName.substringAfterLast('/') } }
    val defaultList = remember(categories) {
        val uri = preferencesManager.getDefaultAddTaskFileUri()
        categories.firstOrNull { it.uri == uri }
    }
    val inbox = remember(categories) { byName.entries.firstOrNull { it.key.equals(INBOX_NAME, ignoreCase = true) }?.value }

    // Tags are read from the folder of the list the task goes to; the list comes from the text first.
    var definitions by remember { mutableStateOf(TagDefinitions.EMPTY) }
    val firstPass = remember(text, ignoreDate, ignoreList, ignoredTags, chosenList, settings.tagRecognition, definitions, byName) {
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
    val target: Category? = chosenList ?: firstPass.list?.let { byName[it.name] } ?: defaultList ?: inbox
    val loadedDefinitions by produceState(fileRepository.cachedTagDefinitions(target?.uri) ?: TagDefinitions.EMPTY, target?.uri) {
        value = fileRepository.getTagDefinitions(target?.uri)
    }
    LaunchedEffect(loadedDefinitions) { definitions = loadedDefinitions }
    val recognition = firstPass

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    var openingFull by remember { mutableStateOf(false) }
    // The pull gesture keeps its first handler; these give it the list and words chosen by then.
    val currentTarget by rememberUpdatedState(target)
    val currentFirstPass by rememberUpdatedState(firstPass)
    fun openFull() {
        if (openingFull) return
        openingFull = true
        // List words go, the list is chosen; date and tag words stay for the task sheet to recognize.
        val rest = currentFirstPass.list?.let { ListRecognition.remove(text, it) } ?: text
        val list = currentTarget
        if (list != null) {
            onOpenFullAdd(list.uri, rest.trim())
        } else {
            // No list yet: the Inbox, as when saving here, so the same task form opens and not the list picker.
            scope.launch { onOpenFullAdd(fileRepository.createCategoryByName(INBOX_NAME).getOrNull(), rest.trim()) }
        }
    }
    val currentOpenFull by rememberUpdatedState({ openFull() })
    val pullDistance = with(LocalDensity.current) { PULL_UP_DISTANCE.toPx() }

    fun save() {
        if (text.isBlank() || saving) return
        saving = true
        scope.launch {
            val tasksPlugin = settings.enableTasksPluginSupport
            val markers = settings.toTaskMarkers()
            val baseMeta = if (tasksPlugin) MarkdownParser.TasksMeta(createdDate = LocalDate.now().toString()) else MarkdownParser.TasksMeta()
            val (taskText, meta) = TaskInputRecognizer.build(
                recognition = recognition,
                meta = baseMeta,
                removeWords = settings.tagRecognitionRemoveWords,
                tasksPlugin = tasksPlugin,
                markers = markers,
            )
            val targetUri: Uri? = target?.uri ?: fileRepository.createCategoryByName(INBOX_NAME).getOrNull()
            val listName = target?.displayName?.substringAfterLast('/') ?: INBOX_NAME
            val written = targetUri != null && taskText.isNotBlank() && fileRepository.updateLines(targetUri) { lines ->
                MarkdownParser.addTodo(
                    lines = lines,
                    text = taskText,
                    meta = if (tasksPlugin) meta else null,
                    enableTasksPlugin = tasksPlugin,
                    addAtStart = settings.newTodoFilePosition == NewTodoFilePosition.TOP,
                    markers = markers,
                )
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
                    closing = true
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) { Text(text = stringResource(R.string.category_discard_draft_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) {
                    Text(text = stringResource(R.string.category_discard_draft_keep))
                }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // The handle is where people pull: pulling it up opens the normal task sheet too.
        dragHandle = {
            Box(modifier = Modifier.pullUp(pullDistance) { currentOpenFull() }) {
                BottomSheetDefaults.DragHandle()
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.ime)
                .padding(bottom = 16.dp)
                // The sheet is already fully open, so a pull upwards means "more": the normal task sheet.
                .pullUp(pullDistance) { currentOpenFull() },
        ) {
            Text(text = stringResource(R.string.quick_add_title), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = fieldValueFor(field, text),
                onValueChange = { typed ->
                    // Finished tag words turn into the tag right away: "5 Minuten " → "#5min ".
                    val (value, replaced) = typedTaskText(
                        previous = text,
                        typed = typed,
                        settle = settings.tagRecognition,
                        definitions = definitions,
                        ignoredTags = ignoredTags,
                        removeWords = settings.tagRecognitionRemoveWords,
                    )
                    replacedWords = replacedWords + replaced
                    field = value
                    text = value.text
                },
                maxLines = TaskTextField.MAX_LINES,
                keyboardOptions = TaskTextField.keyboardOptions,
                label = { Text(text = stringResource(R.string.quick_add_hint)) },
                trailingIcon = {
                    RecognitionToggle(
                        enabled = settings.tagRecognition,
                        onToggle = { appSettingsRepository.setTagRecognition(!settings.tagRecognition) },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            Spacer(modifier = Modifier.height(8.dp))
            // The list chip: where the task goes. Tapping it picks another list.
            Box {
                val listLabel = target?.displayName?.substringAfterLast('/') ?: INBOX_NAME
                RemovableChip(
                    label = stringResource(R.string.quick_add_list_chip, listLabel),
                    onRemove = null,
                    onClick = { showListMenu = true },
                )
                DropdownMenu(expanded = showListMenu, onDismissRequest = { showListMenu = false }) {
                    if (firstPass.list != null || chosenList != null) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.quick_add_list_not_recognized)) },
                            onClick = {
                                chosenList = null
                                ignoreList = true
                                showListMenu = false
                            },
                        )
                    }
                    categories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(text = category.displayName) },
                            onClick = {
                                chosenList = category
                                showListMenu = false
                            },
                        )
                    }
                }
            }
            recognition.date?.let { date ->
                Spacer(modifier = Modifier.height(8.dp))
                RecognizedDateChip(match = date, onIgnore = { ignoreDate = true })
            }
            val tagsSet = tagsSetIn(text, definitions)
            if (recognition.tags.isNotEmpty() || tagsSet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                RecognizedTagChips(
                    hits = recognition.tags,
                    onRemove = { hit -> ignoredTags = ignoredTags + hit.term.primaryTag },
                    set = tagsSet,
                    onRemoveSet = { _, term ->
                        val tag = term.tags.firstOrNull { TagText.tagsIn(text).any { t -> t.equals(it, ignoreCase = true) } } ?: term.primaryTag
                        ignoredTags = ignoredTags + term.primaryTag
                        text = TagRecognition.unsettle(text, tag, replacedWords[tag])
                    },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { openFull() }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                    Text(text = stringResource(R.string.quick_add_all_options))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
                TextButton(onClick = { save() }, enabled = text.isNotBlank() && !saving) {
                    Text(text = stringResource(R.string.action_save))
                }
            }
        }
    }
}

/**
 * Calls [onPull] once the finger has moved [distance] upwards. The gesture is only watched,
 * never consumed, so the sheet still closes when pulled down.
 */
private fun Modifier.pullUp(distance: Float, onPull: () -> Unit): Modifier = pointerInput(distance) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dragged = 0f
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            dragged += change.position.y - change.previousPosition.y
            if (dragged < -distance) {
                onPull()
                break
            }
        }
    }
}
