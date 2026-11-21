package com.isotjs.todosian.ui.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.utils.TagDefinitions

/**
 * The tag definitions of the folders of [uris], for the symbols in task rows.
 *
 * The tags last read are taken in the same frame, also when [uris] change (more lists loaded,
 * another view), so a row never shows `#tag` first and its symbol a frame later. The tag notes
 * are read again in the background and replace the cached ones only when they changed.
 */
@Composable
fun rememberTagDefinitions(fileRepository: FileRepository, uris: List<Uri>): Map<Uri, TagDefinitions> {
    val cached = remember(uris) { uris.associateWith { fileRepository.cachedTagDefinitions(it) ?: TagDefinitions.EMPTY } }
    // Kept across changes of [uris], so lists read before stay fresh.
    var fresh by remember { mutableStateOf(emptyMap<Uri, TagDefinitions>()) }
    LaunchedEffect(uris) {
        val read = uris.associateWith { fileRepository.getTagDefinitions(it) }
        if (read.any { (uri, definitions) -> fresh[uri] != definitions }) fresh = fresh + read
    }
    return remember(cached, fresh) { cached + fresh.filterKeys { it in cached } }
}

/** The tag definitions of the folder of [uri] (null: the main folder), cached ones at once. */
@Composable
fun rememberTagDefinitions(fileRepository: FileRepository, uri: Uri?): TagDefinitions {
    val cached = remember(uri) { fileRepository.cachedTagDefinitions(uri) ?: TagDefinitions.EMPTY }
    var fresh by remember { mutableStateOf<Pair<Uri?, TagDefinitions>?>(null) }
    LaunchedEffect(uri) {
        val read = fileRepository.getTagDefinitions(uri)
        if (fresh != (uri to read)) fresh = uri to read
    }
    return fresh?.takeIf { it.first == uri }?.second ?: cached
}
