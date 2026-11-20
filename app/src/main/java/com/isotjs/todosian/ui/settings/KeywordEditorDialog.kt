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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.isotjs.todosian.R
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.settings.ReminderRepeat
import com.isotjs.todosian.notifications.KeywordRules
import com.isotjs.todosian.notifications.ProfileRule
import com.isotjs.todosian.notifications.ReminderProfile
import com.isotjs.todosian.utils.TagDefinitionWriter
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagGroup
import com.isotjs.todosian.utils.TagTerm
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.launch

private data class EditorFolder(val uri: Uri, val name: String)

/** Which small dialog of the editor is open. */
private sealed interface EditorPrompt {
    data object NewGroup : EditorPrompt
    data class RenameGroup(val index: Int) : EditorPrompt
    data class Term(val groupIndex: Int, val termIndex: Int?) : EditorPrompt
    data object NewProfile : EditorPrompt
    data object AddKeyword : EditorPrompt
    data class AddRule(val keywordIndex: Int) : EditorPrompt
    data object DeleteProfile : EditorPrompt
}

/**
 * Edits the keyword note and the reminder profiles of a folder. Every change is written to the
 * folder at once, so the notes stay the single source and sync to other devices.
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
    val noteTitle = stringResource(R.string.keyword_editor_note_title)

    val folders by produceState<List<EditorFolder>?>(initialValue = null) {
        value = (listOfNotNull(fileRepository.getFolderUri()) + fileRepository.getExtraFolderUris()).distinct().map { uri ->
            EditorFolder(uri, fileRepository.getFolderDisplayName(uri).getOrNull() ?: uri.toString())
        }
    }
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(folders) {
        if (folderUri == null) folderUri = folders?.firstOrNull()?.uri
    }

    var definitions by remember { mutableStateOf<TagDefinitions?>(null) }
    var profileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var openProfile by remember { mutableStateOf<ReminderProfile?>(null) }
    var prompt by remember { mutableStateOf<EditorPrompt?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(folderUri) {
        val uri = folderUri ?: return@LaunchedEffect
        definitions = null
        openProfile = null
        val loaded = fileRepository.getFolderTagDefinitions(uri)
        definitions = loaded
        profileNames = fileRepository.getReminderProfileNames(uri)
        if (loaded.isEmpty) showHelp = true
    }

    fun reportFailure(result: Result<Unit>) {
        if (result.isFailure) Toast.makeText(context, saveFailed, Toast.LENGTH_LONG).show()
    }

    fun saveDefinitions(updated: TagDefinitions) {
        val uri = folderUri ?: return
        definitions = updated
        scope.launch { reportFailure(fileRepository.saveTagDefinitions(uri, updated, noteTitle)) }
    }

    fun saveProfile(updated: ReminderProfile) {
        val uri = folderUri ?: return
        openProfile = updated
        scope.launch {
            val result = fileRepository.saveReminderProfile(uri, updated)
            reportFailure(result)
            if (result.isSuccess) profileNames = fileRepository.getReminderProfileNames(uri)
        }
    }

    val labelFor: (String) -> String = { tag -> definitions?.termFor(tag)?.label?.let { "$it ($tag)" } ?: tag }

    Dialog(
        onDismissRequest = { if (openProfile != null) openProfile = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = openProfile?.name ?: stringResource(R.string.keyword_editor_title))
                    },
                    navigationIcon = {
                        if (openProfile != null) {
                            IconButton(onClick = { openProfile = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.keyword_editor_back))
                            }
                        } else {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.keyword_editor_close))
                            }
                        }
                    },
                    actions = {
                        if (openProfile != null) {
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
                    HelpCard(profileHelp = openProfile != null)
                }

                val loadedFolders = folders
                if (loadedFolders != null && loadedFolders.size > 1 && openProfile == null) {
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

                val defs = definitions
                val profile = openProfile
                when {
                    folderUri != null && defs == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    defs == null -> Unit
                    profile != null -> ProfileSection(
                        profile = profile,
                        labelFor = labelFor,
                        onChange = ::saveProfile,
                        onAddKeyword = { prompt = EditorPrompt.AddKeyword },
                        onAddRule = { index -> prompt = EditorPrompt.AddRule(index) },
                    )
                    else -> {
                        KeywordSection(
                            definitions = defs,
                            onEditGroup = { prompt = EditorPrompt.RenameGroup(it) },
                            onEditTerm = { group, term -> prompt = EditorPrompt.Term(group, term) },
                            onAddGroup = { prompt = EditorPrompt.NewGroup },
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
                                        openProfile = fileRepository.getReminderProfile(uri, name) ?: ReminderProfile(name, emptyList())
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

    val defs = definitions ?: TagDefinitions.EMPTY
    when (val current = prompt) {
        null -> Unit
        EditorPrompt.NewGroup -> TextPrompt(
            title = stringResource(R.string.keyword_editor_add_group),
            label = stringResource(R.string.keyword_editor_group_name),
            initial = "",
            onDismiss = { prompt = null },
            onConfirm = { name ->
                prompt = null
                saveDefinitions(TagDefinitions(defs.groups + TagGroup(name, emptyList())))
            },
        )
        is EditorPrompt.RenameGroup -> {
            val group = defs.groups.getOrNull(current.index)
            TextPrompt(
                title = stringResource(R.string.keyword_editor_edit_group),
                label = stringResource(R.string.keyword_editor_group_name),
                initial = group?.name.orEmpty(),
                onDismiss = { prompt = null },
                onConfirm = { name ->
                    prompt = null
                    saveDefinitions(TagDefinitions(defs.groups.mapIndexed { i, g -> if (i == current.index) g.copy(name = name) else g }))
                },
                onDelete = {
                    prompt = null
                    saveDefinitions(TagDefinitions(defs.groups.filterIndexed { i, _ -> i != current.index }))
                },
            )
        }
        is EditorPrompt.Term -> {
            val group = defs.groups.getOrNull(current.groupIndex)
            val term = current.termIndex?.let { group?.terms?.getOrNull(it) }
            TermPrompt(
                term = term,
                onDismiss = { prompt = null },
                onConfirm = { updated ->
                    prompt = null
                    val groups = defs.groups.toMutableList()
                    if (group == null) {
                        groups.add(0, TagGroup(null, listOf(updated)))
                    } else {
                        val terms = group.terms.toMutableList()
                        if (current.termIndex == null) terms.add(updated) else terms[current.termIndex] = updated
                        groups[current.groupIndex] = group.copy(terms = terms)
                    }
                    saveDefinitions(TagDefinitions(groups))
                },
                onDelete = if (term != null && group != null) {
                    {
                        prompt = null
                        val terms = group.terms.filterIndexed { i, _ -> i != current.termIndex }
                        saveDefinitions(TagDefinitions(defs.groups.mapIndexed { i, g -> if (i == current.groupIndex) g.copy(terms = terms) else g }))
                    }
                } else {
                    null
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
                if (name.isNotEmpty()) saveProfile(ReminderProfile(name, emptyList()))
            },
        )
        EditorPrompt.AddKeyword -> {
            val profile = openProfile
            KeywordPrompt(
                definitions = defs,
                used = profile?.keywords?.map { it.tag }.orEmpty(),
                onDismiss = { prompt = null },
                onPick = { tag ->
                    prompt = null
                    if (profile != null) saveProfile(profile.copy(keywords = profile.keywords + KeywordRules(tag, emptyList(), null)))
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
                        val latest = openProfile
                        if (latest != null) {
                            val keywords = latest.keywords.mapIndexed { i, k ->
                                if (i == current.keywordIndex) k.copy(rules = (k.rules + ProfileRule(days, LocalTime.of(hour, minute))).distinct()) else k
                            }
                            saveProfile(latest.copy(keywords = keywords))
                        }
                    },
                    if (days == 0) 9 else 18,
                    0,
                    DateFormat.is24HourFormat(context),
                ).show()
            },
        )
        EditorPrompt.DeleteProfile -> AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(text = stringResource(R.string.keyword_editor_delete_profile)) },
            text = { Text(text = stringResource(R.string.keyword_editor_delete_profile_text, openProfile?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    prompt = null
                    val uri = folderUri
                    val name = openProfile?.name
                    openProfile = null
                    if (uri != null && name != null) {
                        scope.launch {
                            reportFailure(fileRepository.deleteReminderProfile(uri, name))
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
                    R.string.keyword_editor_help_4,
                )
            }
            lines.forEach { res ->
                Text(text = "• " + stringResource(res), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordSection(
    definitions: TagDefinitions,
    onEditGroup: (Int) -> Unit,
    onEditTerm: (groupIndex: Int, termIndex: Int?) -> Unit,
    onAddGroup: () -> Unit,
) {
    Text(text = stringResource(R.string.keyword_editor_keywords), style = MaterialTheme.typography.titleMedium)
    if (definitions.groups.none { it.name == null }) {
        // Keywords without a group: add one to a fresh ungrouped block.
        TextButton(onClick = { onEditTerm(-1, null) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(text = stringResource(R.string.keyword_editor_add_keyword), modifier = Modifier.padding(start = 8.dp))
        }
    }
    definitions.groups.forEachIndexed { groupIndex, group ->
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
                                if (group.exclusive) R.string.keyword_editor_group_exclusive else R.string.keyword_editor_group_free,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (group.name != null) {
                        IconButton(onClick = { onEditGroup(groupIndex) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.keyword_editor_edit_group))
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.terms.forEachIndexed { termIndex, term ->
                        InputChip(
                            selected = false,
                            onClick = { onEditTerm(groupIndex, termIndex) },
                            label = { Text(term.label) },
                        )
                    }
                    InputChip(
                        selected = false,
                        onClick = { onEditTerm(groupIndex, null) },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSection(
    profile: ReminderProfile,
    labelFor: (String) -> String,
    onChange: (ReminderProfile) -> Unit,
    onAddKeyword: () -> Unit,
    onAddRule: (Int) -> Unit,
) {
    if (profile.keywords.isEmpty()) {
        Text(
            text = stringResource(R.string.keyword_editor_profile_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    profile.keywords.forEachIndexed { index, keyword ->
        fun update(transform: (KeywordRules) -> KeywordRules?) {
            val keywords = profile.keywords.mapIndexedNotNull { i, k -> if (i == index) transform(k) else k }
            onChange(profile.copy(keywords = keywords))
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = labelFor(keyword.tag), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { update { null } }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.keyword_editor_remove_keyword))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    keyword.rules.sortedWith(compareByDescending<ProfileRule> { it.daysBefore }.thenBy { it.time }).forEach { rule ->
                        InputChip(
                            selected = false,
                            onClick = { update { k -> k.copy(rules = k.rules - rule) } },
                            label = { Text(ruleLabel(rule)) },
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
                        onClick = { onAddRule(index) },
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
                            onClick = { update { k -> k.copy(repeat = option) } },
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
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
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
    onDismiss: () -> Unit,
    onConfirm: (TagTerm) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember { mutableStateOf(term?.label.orEmpty()) }
    var tags by remember { mutableStateOf(term?.tags?.joinToString(", ").orEmpty()) }
    val parsed = TagDefinitionWriter.parseTagInput(tags)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(if (term == null) R.string.keyword_editor_add_keyword else R.string.keyword_editor_edit_keyword))
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
                    supportingText = { Text(stringResource(R.string.keyword_editor_keyword_tags_hint)) },
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
    val customTag = TagDefinitionWriter.parseTagInput(custom).firstOrNull()
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
