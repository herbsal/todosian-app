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
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagNote
import com.isotjs.todosian.utils.TagNoteEdits
import com.isotjs.todosian.utils.TagNoteGroup
import com.isotjs.todosian.utils.TagNoteTerm
import com.isotjs.todosian.utils.TagTerm
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
    data class Term(val note: TagNote, val group: TagNoteGroup?, val term: TagNoteTerm?, val view: Boolean = false) : EditorPrompt
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
                            onEdit = { term -> prompt = EditorPrompt.Term(viewNote, viewNote.editableGroups.lastOrNull(), term, view = true) },
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
        )
        is EditorPrompt.Term -> {
            val edit: ((List<String>) -> List<String>?) -> Unit = if (current.view) ::editViews else ::editTags
            TermPrompt(
                term = current.term?.term,
                view = current.view,
                onDismiss = { prompt = null },
                onConfirm = { updated ->
                    prompt = null
                    val term = current.term
                    if (term == null) {
                        edit { TagNoteEdits.addTerm(it, current.note, current.group, updated) }
                    } else {
                        edit { TagNoteEdits.replaceTerm(it, current.note, term, updated) }
                    }
                },
                onDelete = current.term?.let { term ->
                    {
                        prompt = null
                        edit { TagNoteEdits.removeTerm(it, current.note, term) }
                    }
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
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(text = stringResource(R.string.keyword_editor_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun TermPrompt(
    term: TagTerm?,
    view: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (TagTerm) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember { mutableStateOf(term?.label.orEmpty()) }
    var tags by remember { mutableStateOf(term?.tags?.joinToString(", ").orEmpty()) }
    val parsed = TagNoteEdits.parseTagInput(tags)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val title = when {
                view && term == null -> R.string.keyword_editor_add_view
                view -> R.string.keyword_editor_edit_view
                term == null -> R.string.keyword_editor_add_keyword
                else -> R.string.keyword_editor_edit_keyword
            }
            Text(text = stringResource(title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Text(stringResource(if (view) R.string.keyword_editor_view_tags_hint else R.string.keyword_editor_keyword_tags_hint))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed.isNotEmpty(),
                onClick = {
                    val cleanLabel = label.replace(":", "").trim().ifEmpty { parsed.first().removePrefix("#") }
                    onConfirm(TagTerm(cleanLabel, parsed))
                },
            ) { Text(text = stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(text = stringResource(R.string.keyword_editor_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
            }
        },
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
