package com.isotjs.todosian.ui.settings

import android.app.TimePickerDialog
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.NoteChangedException
import com.isotjs.todosian.data.settings.ReminderRepeat
import com.isotjs.todosian.notifications.ProfileNote
import com.isotjs.todosian.notifications.ProfileNoteEdits
import com.isotjs.todosian.notifications.ProfileNoteKeyword
import com.isotjs.todosian.notifications.ProfileRule
import com.isotjs.todosian.notifications.ReminderProfileParser
import com.isotjs.todosian.utils.NoteLines
import com.isotjs.todosian.utils.NoteProblem
import com.isotjs.todosian.utils.NoteProblemKind
import com.isotjs.todosian.utils.TagDefinitionParser
import com.isotjs.todosian.utils.SavedViews
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagDisplay
import com.isotjs.todosian.utils.TagGroup
import com.isotjs.todosian.utils.TagNote
import com.isotjs.todosian.utils.TagNoteEdits
import com.isotjs.todosian.utils.TagNoteGroup
import com.isotjs.todosian.utils.TagNoteTerm
import com.isotjs.todosian.utils.TagRecognition
import com.isotjs.todosian.utils.TagTerm
import com.isotjs.todosian.utils.TagWords
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.launch

private data class EditorFolder(val uri: Uri, val name: String)

/** The note a line belongs to. */
private enum class NoteTarget { TAGS, PROFILE, VIEWS }

/** Which small dialog of the editor is open. Each one keeps the note it was opened on. */
private sealed interface EditorPrompt {
    data object NewGroup : EditorPrompt
    data class RenameGroup(val note: TagNote, val group: TagNoteGroup) : EditorPrompt
    /** [initial] fills a new keyword, e.g. with a copy of another one. */
    data class Term(val note: TagNote, val group: TagNoteGroup?, val term: TagNoteTerm?, val initial: TagTerm? = null) : EditorPrompt

    /** A saved view: its name and alternatives, each a set of tags that must all apply. */
    data class View(val note: TagNote, val entry: TagNoteTerm?, val name: String, val alternatives: List<List<String>>) : EditorPrompt
    data object NewProfile : EditorPrompt
    data object AddKeyword : EditorPrompt
    data class AddRule(val note: ProfileNote, val keyword: ProfileNoteKeyword) : EditorPrompt
    data object DeleteProfile : EditorPrompt
    data class FixProblem(val problem: NoteProblem, val target: NoteTarget) : EditorPrompt
}

/**
 * Edits the keyword note and the reminder profiles of a folder. Each change touches only the
 * lines it concerns and is written at once, so the notes can also be edited in other apps and
 * sync to other devices. Lines the app does not understand are listed in a warning.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun KeywordEditorDialog(
    fileRepository: FileRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.keyword_editor_save_failed)
    val noteChanged = stringResource(R.string.keyword_editor_note_changed)
    val noteTitle = stringResource(R.string.keyword_editor_note_title)
    val viewsTitle = stringResource(R.string.keyword_editor_views)
    val german = LocalConfiguration.current.locales[0].language == "de"
    val resources = LocalResources.current
    /** "Name (Kopie)" for a duplicate. */
    fun copyName(name: String): String = resources.getString(R.string.keyword_editor_copy_name, name)

    val folders by produceState<List<EditorFolder>?>(initialValue = null) {
        value = (listOfNotNull(fileRepository.getFolderUri()) + fileRepository.getExtraFolderUris()).distinct().map { uri ->
            EditorFolder(uri, fileRepository.getFolderDisplayName(uri).getOrNull() ?: uri.toString())
        }
    }
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(folders) {
        if (folderUri == null) folderUri = folders?.firstOrNull()?.uri
    }

    var tagLines by remember { mutableStateOf<List<String>?>(null) }
    var viewLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var profileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var profileName by remember { mutableStateOf<String?>(null) }
    var profileLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var prompt by remember { mutableStateOf<EditorPrompt?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    val tagNote = remember(tagLines) { tagLines?.let { TagDefinitionParser.parseNote(it) } }
    val viewNote = remember(viewLines) { TagDefinitionParser.parseNote(viewLines) }
    val profileNote = remember(profileName, profileLines) {
        profileName?.let { ReminderProfileParser.parseNote(it, profileLines) }
    }

    LaunchedEffect(folderUri) {
        val uri = folderUri ?: return@LaunchedEffect
        tagLines = null
        profileName = null
        val lines = fileRepository.readTagNote(uri)
        viewLines = fileRepository.readViewNote(uri)
        tagLines = lines
        profileNames = fileRepository.getReminderProfileNames(uri)
        if (TagDefinitionParser.parseNote(lines).definitions.isEmpty) showHelp = true
    }

    fun report(error: Throwable) {
        val message = if (error is NoteChangedException) noteChanged else saveFailed
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun editTags(edit: (List<String>) -> List<String>?) {
        val uri = folderUri ?: return
        scope.launch {
            fileRepository.editTagNote(uri, noteTitle, edit)
                .onSuccess { tagLines = it }
                .onFailure {
                    report(it)
                    tagLines = fileRepository.readTagNote(uri)
                }
        }
    }

    fun editViews(edit: (List<String>) -> List<String>?) {
        val uri = folderUri ?: return
        scope.launch {
            fileRepository.editViewNote(uri, viewsTitle, edit)
                .onSuccess { viewLines = it }
                .onFailure {
                    report(it)
                    viewLines = fileRepository.readViewNote(uri)
                }
        }
    }

    fun editProfile(name: String, edit: (List<String>) -> List<String>?) {
        val uri = folderUri ?: return
        scope.launch {
            fileRepository.editReminderProfile(uri, name, edit)
                .onSuccess {
                    profileName = name
                    profileLines = it
                    profileNames = fileRepository.getReminderProfileNames(uri)
                }
                .onFailure {
                    report(it)
                    fileRepository.readReminderProfile(uri, name)?.let { lines -> profileLines = lines }
                }
        }
    }

    val definitions = tagNote?.definitions ?: TagDefinitions.EMPTY
    val labelFor: (String) -> String = { tag -> definitions.termFor(tag)?.label?.let { "$it ($tag)" } ?: tag }

    Dialog(
        onDismissRequest = { if (profileName != null) profileName = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = profileName ?: stringResource(R.string.keyword_editor_title))
                    },
                    navigationIcon = {
                        if (profileName != null) {
                            IconButton(onClick = { profileName = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.keyword_editor_back))
                            }
                        } else {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.keyword_editor_close))
                            }
                        }
                    },
                    actions = {
                        if (profileName != null) {
                            IconButton(onClick = { prompt = EditorPrompt.DeleteProfile }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.keyword_editor_delete_profile))
                            }
                        }
                        IconButton(onClick = { showHelp = !showHelp }) {
                            Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.keyword_editor_help))
                        }
                    },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showHelp) {
                    HelpCard(profileHelp = profileName != null)
                }

                val loadedFolders = folders
                if (loadedFolders != null && loadedFolders.size > 1 && profileName == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        loadedFolders.forEach { folder ->
                            FilterChip(
                                selected = folder.uri == folderUri,
                                onClick = { folderUri = folder.uri },
                                label = { Text(folder.name) },
                            )
                        }
                    }
                }
                if (loadedFolders != null && loadedFolders.isEmpty()) {
                    Text(text = stringResource(R.string.keyword_editor_no_folder))
                }

                val note = tagNote
                val profile = profileNote
                when {
                    folderUri != null && note == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    note == null -> Unit
                    profile != null -> {
                        ProblemCard(
                            problems = profile.problems,
                            onFix = { prompt = EditorPrompt.FixProblem(it, NoteTarget.PROFILE) },
                            onDelete = { problem -> editProfile(profile.name) { NoteLines.remove(it, listOf(problem.line to problem.text)) } },
                        )
                        ProfileSection(
                            note = profile,
                            labelFor = labelFor,
                            onRemoveKeyword = { keyword -> editProfile(profile.name) { ProfileNoteEdits.removeKeyword(it, profile, keyword) } },
                            onRemoveRule = { rule -> editProfile(profile.name) { ProfileNoteEdits.removeRule(it, profile, rule) } },
                            onSetRepeat = { keyword, repeat ->
                                editProfile(profile.name) { ProfileNoteEdits.setRepeat(it, profile, keyword, repeat, german) }
                            },
                            onAddKeyword = { prompt = EditorPrompt.AddKeyword },
                            onAddRule = { keyword -> prompt = EditorPrompt.AddRule(profile, keyword) },
                        )
                    }
                    else -> {
                        ProblemCard(
                            problems = note.problems,
                            onFix = { prompt = EditorPrompt.FixProblem(it, NoteTarget.TAGS) },
                            onDelete = { problem -> editTags { NoteLines.remove(it, listOf(problem.line to problem.text)) } },
                        )
                        KeywordSection(
                            note = note,
                            onEditGroup = { prompt = EditorPrompt.RenameGroup(note, it) },
                            onEditTerm = { group, term -> prompt = EditorPrompt.Term(note, group, term) },
                            onAddGroup = { prompt = EditorPrompt.NewGroup },
                        )
                        HorizontalDivider()
                        ViewSection(
                            note = viewNote,
                            onFix = { prompt = EditorPrompt.FixProblem(it, NoteTarget.VIEWS) },
                            onDeleteProblem = { problem -> editViews { NoteLines.remove(it, listOf(problem.line to problem.text)) } },
                            onEdit = { entry ->
                                prompt = EditorPrompt.View(
                                    note = viewNote,
                                    entry = entry,
                                    name = entry?.term?.label.orEmpty(),
                                    alternatives = entry?.let { SavedViews.alternativesOf(viewNote.lines[it.line]) }.orEmpty(),
                                )
                            },
                        )
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.keyword_editor_profiles),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.keyword_editor_profiles_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        profileNames.forEach { name ->
                            ListItem(
                                headlineContent = { Text(name) },
                                trailingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    val uri = folderUri ?: return@clickable
                                    scope.launch {
                                        profileLines = fileRepository.readReminderProfile(uri, name).orEmpty()
                                        profileName = name
                                    }
                                },
                            )
                        }
                        TextButton(onClick = { prompt = EditorPrompt.NewProfile }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(text = stringResource(R.string.keyword_editor_add_profile), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }

    when (val current = prompt) {
        null -> Unit
        EditorPrompt.NewGroup -> TextPrompt(
            title = stringResource(R.string.keyword_editor_add_group),
            label = stringResource(R.string.keyword_editor_group_name),
            initial = "",
            onDismiss = { prompt = null },
            onConfirm = { name ->
                prompt = null
                editTags { TagNoteEdits.addGroup(it, name.trim()) }
            },
        )
        is EditorPrompt.RenameGroup -> TextPrompt(
            title = stringResource(R.string.keyword_editor_edit_group),
            label = stringResource(R.string.keyword_editor_group_name),
            initial = current.group.name.orEmpty(),
            onDismiss = { prompt = null },
            onConfirm = { name ->
                prompt = null
                editTags { TagNoteEdits.renameGroup(it, current.note, current.group, name.trim()) }
            },
            onDelete = {
                prompt = null
                editTags { TagNoteEdits.removeGroup(it, current.note, current.group) }
            },
            onDuplicate = {
                prompt = null
                editTags { TagNoteEdits.duplicateGroup(it, current.group, copyName(current.group.name.orEmpty())) }
            },
        )
        is EditorPrompt.Term -> {
            TermPrompt(
                term = current.term?.term ?: current.initial,
                isNew = current.term == null,
                groupName = current.group?.name,
                onDismiss = { prompt = null },
                onConfirm = { updated ->
                    prompt = null
                    val term = current.term
                    if (term == null) {
                        editTags { TagNoteEdits.addTerm(it, current.note, current.group, updated) }
                    } else {
                        editTags { TagNoteEdits.replaceTerm(it, current.note, term, updated) }
                    }
                },
                onDelete = current.term?.let { term ->
                    {
                        prompt = null
                        editTags { TagNoteEdits.removeTerm(it, current.note, term) }
                    }
                },
                // The copy opens as a new keyword, so its tags can be changed before it is saved.
                onDuplicate = current.term?.let { term ->
                    { prompt = current.copy(term = null, initial = term.term.copy(label = copyName(term.term.label))) }
                },
            )
        }
        is EditorPrompt.View -> {
            ViewPrompt(
                isNew = current.entry == null,
                initialName = current.name,
                initialAlternatives = current.alternatives,
                definitions = definitions,
                onDismiss = { prompt = null },
                onConfirm = { name, alternatives ->
                    prompt = null
                    val line = SavedViews.line(name, alternatives)
                    val entry = current.entry
                    if (entry == null) {
                        editViews { TagNoteEdits.addLine(it, current.note, current.note.editableGroups.lastOrNull(), line) }
                    } else {
                        editViews { TagNoteEdits.replaceLine(it, current.note, entry, line) }
                    }
                },
                onDelete = current.entry?.let { entry ->
                    {
                        prompt = null
                        editViews { TagNoteEdits.removeTerm(it, current.note, entry) }
                    }
                },
                onDuplicate = current.entry?.let {
                    { name, alternatives -> prompt = current.copy(entry = null, name = copyName(name), alternatives = alternatives) }
                },
            )
        }
        EditorPrompt.NewProfile -> TextPrompt(
            title = stringResource(R.string.keyword_editor_add_profile),
            label = stringResource(R.string.keyword_editor_profile_name),
            initial = "",
            onDismiss = { prompt = null },
            onConfirm = { input ->
                prompt = null
                val name = input.replace(Regex("""[\\/:*?"<>|#]"""), "").trim()
                if (name.isNotEmpty()) editProfile(name) { it }
            },
        )
        EditorPrompt.AddKeyword -> {
            val profile = profileNote
            KeywordPrompt(
                definitions = definitions,
                used = profile?.keywords?.map { it.tag }.orEmpty(),
                onDismiss = { prompt = null },
                onPick = { tag ->
                    prompt = null
                    if (profile != null) editProfile(profile.name) { ProfileNoteEdits.addKeyword(it, tag) }
                },
            )
        }
        is EditorPrompt.AddRule -> DaysBeforePrompt(
            onDismiss = { prompt = null },
            onPick = { days ->
                prompt = null
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        val rule = ProfileRule(days, LocalTime.of(hour, minute))
                        editProfile(current.note.name) { ProfileNoteEdits.addRule(it, current.note, current.keyword, rule, german) }
                    },
                    if (days == 0) 9 else 18,
                    0,
                    DateFormat.is24HourFormat(context),
                ).show()
            },
        )
        is EditorPrompt.FixProblem -> TextPrompt(
            title = stringResource(R.string.keyword_editor_fix_line),
            label = stringResource(R.string.keyword_editor_line_number, current.problem.line + 1),
            initial = current.problem.text,
            onDismiss = { prompt = null },
            onConfirm = { text ->
                prompt = null
                val problem = current.problem
                val profile = profileNote
                when {
                    current.target == NoteTarget.PROFILE && profile != null ->
                        editProfile(profile.name) { NoteLines.replace(it, problem.line, problem.text, text) }
                    current.target == NoteTarget.VIEWS -> editViews { NoteLines.replace(it, problem.line, problem.text, text) }
                    else -> editTags { NoteLines.replace(it, problem.line, problem.text, text) }
                }
            },
            supportingText = problemHint(current.problem.kind),
        )
        EditorPrompt.DeleteProfile -> AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(text = stringResource(R.string.keyword_editor_delete_profile)) },
            text = { Text(text = stringResource(R.string.keyword_editor_delete_profile_text, profileName.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    prompt = null
                    val uri = folderUri
                    val name = profileName
                    profileName = null
                    if (uri != null && name != null) {
                        scope.launch {
                            fileRepository.deleteReminderProfile(uri, name).onFailure { report(it) }
                            profileNames = fileRepository.getReminderProfileNames(uri)
                        }
                    }
                }) { Text(text = stringResource(R.string.keyword_editor_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { prompt = null }) { Text(text = stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun HelpCard(profileHelp: Boolean) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = stringResource(R.string.keyword_editor_help), style = MaterialTheme.typography.titleSmall)
            val lines = if (profileHelp) {
                listOf(
                    R.string.keyword_editor_help_profile_1,
                    R.string.keyword_editor_help_profile_2,
                    R.string.keyword_editor_help_profile_3,
                )
            } else {
                listOf(
                    R.string.keyword_editor_help_1,
                    R.string.keyword_editor_help_2,
                    R.string.keyword_editor_help_3,
                    R.string.keyword_editor_help_icon,
                    R.string.keyword_editor_help_4,
                )
            }
            lines.forEach { res ->
                Text(text = "• " + stringResource(res), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Lists the lines the app did not understand, each with Fix and Delete. */
@Composable
private fun ProblemCard(
    problems: List<NoteProblem>,
    onFix: (NoteProblem) -> Unit,
    onDelete: (NoteProblem) -> Unit,
) {
    if (problems.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(
                    text = pluralStringResource(R.plurals.keyword_editor_problems, problems.size, problems.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            problems.forEach { problem ->
                Column {
                    Text(
                        text = stringResource(R.string.keyword_editor_line_number, problem.line + 1) + ": " + problem.text.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(text = problemHint(problem.kind), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { onFix(problem) }) { Text(text = stringResource(R.string.keyword_editor_fix)) }
                        TextButton(onClick = { onDelete(problem) }) { Text(text = stringResource(R.string.keyword_editor_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun problemHint(kind: NoteProblemKind): String = stringResource(
    when (kind) {
        NoteProblemKind.NO_TAG -> R.string.keyword_editor_problem_no_tag
        NoteProblemKind.UNKNOWN_TEXT -> R.string.keyword_editor_problem_unknown_text
        NoteProblemKind.NO_TIME -> R.string.keyword_editor_problem_no_time
        NoteProblemKind.BAD_REPEAT -> R.string.keyword_editor_problem_bad_repeat
        NoteProblemKind.BAD_HEADING -> R.string.keyword_editor_problem_bad_heading
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordSection(
    note: TagNote,
    onEditGroup: (TagNoteGroup) -> Unit,
    onEditTerm: (group: TagNoteGroup?, term: TagNoteTerm?) -> Unit,
    onAddGroup: () -> Unit,
) {
    Text(text = stringResource(R.string.keyword_editor_keywords), style = MaterialTheme.typography.titleMedium)
    val groups = note.editableGroups
    if (groups.none { it.name == null }) {
        // No ungrouped keywords yet: the first one goes below the note's title.
        TextButton(onClick = { onEditTerm(null, null) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(text = stringResource(R.string.keyword_editor_add_keyword), modifier = Modifier.padding(start = 8.dp))
        }
    }
    groups.forEach { group ->
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.name ?: stringResource(R.string.keyword_editor_ungrouped),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(
                                if (group.name != null) R.string.keyword_editor_group_exclusive else R.string.keyword_editor_group_free,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (group.name != null) {
                        IconButton(onClick = { onEditGroup(group) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.keyword_editor_edit_group))
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.terms.forEach { term ->
                        InputChip(
                            selected = false,
                            onClick = { onEditTerm(group, term) },
                            label = { Text(term.term.label) },
                        )
                    }
                    InputChip(
                        selected = false,
                        onClick = { onEditTerm(group, null) },
                        label = { Text(stringResource(R.string.keyword_editor_add_keyword)) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
                    )
                }
            }
        }
    }
    TextButton(onClick = onAddGroup) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(text = stringResource(R.string.keyword_editor_add_group), modifier = Modifier.padding(start = 8.dp))
    }
}

/** Saved views: named keyword filters shown as chips on the home screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewSection(
    note: TagNote,
    onFix: (NoteProblem) -> Unit,
    onDeleteProblem: (NoteProblem) -> Unit,
    onEdit: (TagNoteTerm?) -> Unit,
) {
    Text(text = stringResource(R.string.keyword_editor_views), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.keyword_editor_views_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ProblemCard(problems = note.problems, onFix = onFix, onDelete = onDeleteProblem)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        note.groups.flatMap { it.terms }.forEach { entry ->
            InputChip(
                selected = false,
                onClick = { onEdit(entry) },
                label = { Text(entry.term.label) },
            )
        }
        InputChip(
            selected = false,
            onClick = { onEdit(null) },
            label = { Text(stringResource(R.string.keyword_editor_add_view)) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSection(
    note: ProfileNote,
    labelFor: (String) -> String,
    onRemoveKeyword: (ProfileNoteKeyword) -> Unit,
    onRemoveRule: (com.isotjs.todosian.notifications.ProfileNoteRule) -> Unit,
    onSetRepeat: (ProfileNoteKeyword, ReminderRepeat?) -> Unit,
    onAddKeyword: () -> Unit,
    onAddRule: (ProfileNoteKeyword) -> Unit,
) {
    if (note.keywords.isEmpty()) {
        Text(
            text = stringResource(R.string.keyword_editor_profile_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    note.keywords.forEach { keyword ->
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = labelFor(keyword.tag), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveKeyword(keyword) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.keyword_editor_remove_keyword))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    keyword.rules.sortedWith(compareByDescending<com.isotjs.todosian.notifications.ProfileNoteRule> { it.rule.daysBefore }.thenBy { it.rule.time })
                        .forEach { rule ->
                            InputChip(
                                selected = false,
                                onClick = { onRemoveRule(rule) },
                                label = { Text(ruleLabel(rule.rule)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.keyword_editor_remove_rule),
                                        modifier = Modifier.size(InputChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    InputChip(
                        selected = false,
                        onClick = { onAddRule(keyword) },
                        label = { Text(stringResource(R.string.keyword_editor_add_rule)) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
                    )
                }
                Text(
                    text = stringResource(R.string.keyword_editor_repeat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<ReminderRepeat?>(null, ReminderRepeat.OFF, ReminderRepeat.HOURLY, ReminderRepeat.DAILY).forEach { option ->
                        FilterChip(
                            selected = keyword.repeat == option,
                            onClick = { if (keyword.repeat != option) onSetRepeat(keyword, option) },
                            label = { Text(repeatLabel(option)) },
                        )
                    }
                }
            }
        }
    }
    TextButton(onClick = onAddKeyword) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(text = stringResource(R.string.keyword_editor_add_profile_keyword), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ruleLabel(rule: ProfileRule): String {
    val time = String.format(Locale.ROOT, "%02d:%02d", rule.time.hour, rule.time.minute)
    return when (rule.daysBefore) {
        0 -> stringResource(R.string.keyword_editor_rule_same_day, time)
        else -> pluralStringResource(R.plurals.keyword_editor_rule_days_before, rule.daysBefore, rule.daysBefore, time)
    }
}

@Composable
private fun repeatLabel(repeat: ReminderRepeat?): String = when (repeat) {
    null -> stringResource(R.string.keyword_editor_repeat_default)
    ReminderRepeat.OFF -> stringResource(R.string.keyword_editor_repeat_off)
    ReminderRepeat.HOURLY -> stringResource(R.string.keyword_editor_repeat_hourly)
    ReminderRepeat.DAILY -> stringResource(R.string.keyword_editor_repeat_daily)
}

@Composable
private fun TextPrompt(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
    supportingText: String? = null,
    onDuplicate: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                supportingText = supportingText?.let { hint -> { Text(hint) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trimEnd()) }, enabled = text.isNotBlank()) {
                Text(text = stringResource(R.string.action_ok))
            }
        },
        dismissButton = { PromptButtons(onDelete = onDelete, onDuplicate = onDuplicate, onDismiss = onDismiss) },
    )
}

private const val MAX_AUTOMATIC = 10

@Composable
private fun TermPrompt(
    term: TagTerm?,
    isNew: Boolean,
    groupName: String?,
    onDismiss: () -> Unit,
    onConfirm: (TagTerm) -> Unit,
    onDelete: (() -> Unit)?,
    onDuplicate: (() -> Unit)?,
) {
    var label by remember { mutableStateOf(term?.label.orEmpty()) }
    var tags by remember { mutableStateOf(term?.tags?.joinToString(", ").orEmpty()) }
    var words by remember { mutableStateOf(term?.words?.joinToString(", ").orEmpty()) }
    val parsed = TagNoteEdits.parseTagInput(tags)
    val parsedWords = TagWords.parseInput(words)
    // What the app already recognizes without own words, so the user only adds what is missing.
    val automatic = remember(label, parsed, groupName) {
        if (parsed.isEmpty()) {
            emptyList()
        } else {
            val cleanLabel = label.replace(":", "").trim().ifEmpty { parsed.first().removePrefix("#") }
            TagRecognition.phrasesFor(TagGroup.fromHeading(groupName, emptyList()), TagTerm(cleanLabel, parsed))
                .distinctBy { it.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val title = if (isNew) R.string.keyword_editor_add_keyword else R.string.keyword_editor_edit_keyword
            Text(text = stringResource(title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.keyword_editor_keyword_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.keyword_editor_keyword_tags)) },
                    supportingText = {
                        Text(stringResource(R.string.keyword_editor_keyword_tags_hint))
                    },
                )
                OutlinedTextField(
                    value = words,
                    onValueChange = { words = it },
                    label = { Text(stringResource(R.string.keyword_editor_words)) },
                    placeholder = { Text(stringResource(R.string.keyword_editor_words_example)) },
                    supportingText = { Text(stringResource(R.string.keyword_editor_words_hint)) },
                )
                if (automatic.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.keyword_editor_words_automatic, automatic.take(MAX_AUTOMATIC).joinToString(", ")) +
                            if (automatic.size > MAX_AUTOMATIC) " …" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed.isNotEmpty(),
                onClick = {
                    val cleanLabel = label.replace(":", "").trim().ifEmpty { parsed.first().removePrefix("#") }
                    onConfirm(TagTerm(cleanLabel, parsed, parsedWords))
                },
            ) { Text(text = stringResource(R.string.action_ok)) }
        },
        dismissButton = { PromptButtons(onDelete = onDelete, onDuplicate = onDuplicate, onDismiss = onDismiss) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordPrompt(
    definitions: TagDefinitions,
    used: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val terms = definitions.groups.flatMap { it.terms }.filter { term -> used.none { term.matches(it) } }
    var custom by remember { mutableStateOf("") }
    val customTag = TagNoteEdits.parseTagInput(custom).firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.keyword_editor_add_profile_keyword)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    terms.forEach { term ->
                        FilterChip(selected = false, onClick = { onPick(term.primaryTag) }, label = { Text(term.label) })
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.keyword_editor_other_tag)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = customTag != null, onClick = { customTag?.let(onPick) }) {
                Text(text = stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DaysBeforePrompt(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var days by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.keyword_editor_add_rule)) },
        text = {
            Column {
                listOf(0, 1, 2, 3, 7).forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = days == option, role = Role.RadioButton, onClick = { days = option }),
                    ) {
                        RadioButton(selected = days == option, onClick = null)
                        Text(
                            text = if (option == 0) {
                                stringResource(R.string.keyword_editor_on_the_day)
                            } else {
                                pluralStringResource(R.plurals.keyword_editor_days_before, option, option)
                            },
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(days) }) { Text(text = stringResource(R.string.keyword_editor_pick_time)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        },
    )
}

/** Delete, duplicate and cancel of a prompt; they wrap onto a second row when space is short. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptButtons(onDelete: (() -> Unit)?, onDuplicate: (() -> Unit)?, onDismiss: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.End) {
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text(text = stringResource(R.string.keyword_editor_delete)) }
        }
        if (onDuplicate != null) {
            TextButton(onClick = onDuplicate) { Text(text = stringResource(R.string.keyword_editor_duplicate)) }
        }
        TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
    }
}

/**
 * A saved view, picked with chips: the selected tags of one box must all apply; "Or …" adds
 * another box that is enough on its own. In a group where a task has only one tag, picking one
 * replaces the other, since a task can never have both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewPrompt(
    isNew: Boolean,
    initialName: String,
    initialAlternatives: List<List<String>>,
    definitions: TagDefinitions,
    onDismiss: () -> Unit,
    onConfirm: (name: String, alternatives: List<List<String>>) -> Unit,
    onDelete: (() -> Unit)?,
    onDuplicate: ((name: String, alternatives: List<List<String>>) -> Unit)?,
) {
    var name by remember { mutableStateOf(initialName) }
    var alternatives by remember { mutableStateOf(initialAlternatives.ifEmpty { listOf(emptyList()) }) }
    val groups = definitions.groups.filter { it.terms.isNotEmpty() }
    val filled = alternatives.filter { it.isNotEmpty() }

    fun toggle(index: Int, group: TagGroup, term: TagTerm) {
        alternatives = alternatives.mapIndexed { i, tags ->
            if (i != index) return@mapIndexed tags
            if (tags.any { term.matches(it) }) {
                tags.filterNot { term.matches(it) }
            } else {
                val others = if (group.exclusive) group.terms.filter { it != term } else emptyList()
                tags.filterNot { tag -> others.any { it.matches(tag) } } + term.primaryTag
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(if (isNew) R.string.keyword_editor_view_title_add else R.string.keyword_editor_edit_view)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.keyword_editor_keyword_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(if (groups.isEmpty()) R.string.keyword_editor_view_no_tags else R.string.keyword_editor_view_choice_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                alternatives.forEachIndexed { index, tags ->
                    if (index > 0) {
                        Text(
                            text = stringResource(R.string.keyword_editor_view_or).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.keyword_editor_view_all),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                                )
                                if (alternatives.size > 1) {
                                    IconButton(onClick = { alternatives = alternatives.filterIndexed { i, _ -> i != index } }) {
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.keyword_editor_view_remove_or))
                                    }
                                }
                            }
                            groups.forEach { group ->
                                group.name?.let { groupName ->
                                    Text(
                                        text = "${TagDisplay.iconFor(group)} $groupName".trim(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    group.terms.forEach { term ->
                                        FilterChip(
                                            selected = tags.any { term.matches(it) },
                                            onClick = { toggle(index, group, term) },
                                            label = { Text(term.label) },
                                        )
                                    }
                                }
                            }
                            // Tags of the view that the tag note does not define: shown so they can be removed.
                            val unknown = tags.filter { definitions.termFor(it) == null }
                            if (unknown.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    unknown.forEach { tag ->
                                        FilterChip(
                                            selected = true,
                                            onClick = {
                                                alternatives = alternatives.mapIndexed { i, list -> if (i == index) list - tag else list }
                                            },
                                            label = { Text(tag) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { alternatives = alternatives + listOf(emptyList()) }, enabled = groups.isNotEmpty()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(text = stringResource(R.string.keyword_editor_view_add_or), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.replace(":", "").isNotBlank() && filled.isNotEmpty(),
                onClick = { onConfirm(name.trim(), filled) },
            ) { Text(text = stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            PromptButtons(
                onDelete = onDelete,
                onDuplicate = onDuplicate?.let { duplicate -> { duplicate(name.trim(), filled) } },
                onDismiss = onDismiss,
            )
        },
    )
}
