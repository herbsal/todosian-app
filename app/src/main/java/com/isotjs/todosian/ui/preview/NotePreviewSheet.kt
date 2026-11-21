package com.isotjs.todosian.ui.preview

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.data.VaultSync
import com.isotjs.todosian.data.listVaultFiles
import com.isotjs.todosian.ui.components.TodosianLoadingIndicator
import com.isotjs.todosian.utils.ObsidianLinks
import com.isotjs.todosian.utils.ObsidianMarkdown
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the preview shows of one note: read fresh from the file, never from the index. */
private data class PreviewContent(val title: String, val relPath: String?, val folder: Uri?, val markdown: String?)

/**
 * The whole note, nicely rendered, in a bottom sheet: images from the vault, wikilinks open the
 * linked note in the same sheet, and a button opens the note in Obsidian.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotePreviewSheet(
    noteUri: Uri,
    mainFolder: Uri?,
    vaultNameSetting: String,
    onDismiss: () -> Unit,
    onObsidianMissing: () -> Unit,
) {
    val context = LocalContext.current
    val stack = remember(noteUri) { mutableStateListOf(noteUri) }
    val current = stack.last()
    var content by remember { mutableStateOf<PreviewContent?>(null) }

    LaunchedEffect(current) {
        content = null
        content = withContext(Dispatchers.IO) { loadPreview(context, current) }
    }

    val defaultHandler = LocalUriHandler.current
    val handler = remember(current, content) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val linked = ObsidianMarkdown.noteOfLink(uri)
                if (linked != null) {
                    val folder = content?.folder ?: return
                    VaultSync.get(context).findNote(folder, linked)?.let { stack.add(it) }
                } else {
                    runCatching { defaultHandler.openUri(uri) }
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stack.size > 1) {
                    IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
                    }
                }
                Text(
                    text = content?.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val shown = content
                if (shown?.relPath != null && shown.folder != null) {
                    TextButton(onClick = {
                        val vault = ObsidianLinks.vaultName(context, shown.folder, mainFolder, vaultNameSetting)
                        if (!ObsidianLinks.launch(context, ObsidianLinks.openNoteUri(vault, shown.relPath + ".md"))) onObsidianMissing()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Text(stringResource(R.string.preview_open_in_obsidian), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            val markdown = content?.markdown
            when {
                content == null -> Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                    TodosianLoadingIndicator()
                }
                markdown == null -> Text(
                    text = stringResource(R.string.preview_unreadable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                else -> CompositionLocalProvider(LocalUriHandler provides handler) {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Markdown(
                            content = markdown,
                            imageTransformer = Coil3ImageTransformerImpl,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun loadPreview(context: android.content.Context, uri: Uri): PreviewContent {
    val info = VaultSync.get(context).noteInfo(uri)
    val title = info?.second?.substringAfterLast('/')
        ?: runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name?.removeSuffix(".md") }.getOrNull()
        ?: ""
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull() ?: return PreviewContent(title, info?.second, info?.first, null)
    val folder = info?.first
    val noteDir = info?.second?.substringBeforeLast('/', "").orEmpty()
    // The vault's files are listed only when the note embeds something.
    val files by lazy { folder?.let { runCatching { listVaultFiles(context, it) }.getOrNull() } }
    val markdown = ObsidianMarkdown.prepare(text) { link -> files?.find(link, noteDir)?.toString() }
    return PreviewContent(title, info?.second, folder, markdown)
}
