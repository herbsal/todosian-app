package com.isotjs.todosian.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

internal data class DocumentEntry(val id: String, val name: String, val isDirectory: Boolean)

/** A Markdown note; [relativePath] is relative to the selected folder and has no `.md` suffix, e.g. `Projects/Move`. */
internal data class MarkdownEntry<T>(val ref: T, val relativePath: String)

internal data class MarkdownTree<T>(val files: List<MarkdownEntry<T>>, val folderIds: List<String>)

/** Folder at the root of a notes folder that holds Todosian's own notes (keywords, reminder profiles). */
internal const val CONFIG_FOLDER_NAME = "Todosian"

/** Accepted names of the keyword note inside [CONFIG_FOLDER_NAME]. */
internal val TAG_NOTE_NAMES = listOf("Tags.md", "Schlagwörter.md", "Schlagwoerter.md")

/**
 * Picks the folder a document belongs to: the tree whose document id is the longest prefix of
 * [documentId] (`primary:Vault` holds `primary:Vault/Inbox.md`). Null when none does.
 */
internal fun treeIdForDocument(documentId: String, treeIds: List<String>): String? =
    treeIds.filter { treeId ->
        documentId == treeId ||
            (treeId.endsWith(":") || treeId.endsWith("/")) && documentId.startsWith(treeId) ||
            documentId.startsWith("$treeId/")
    }.maxByOrNull { it.length }

internal fun isMarkdownNoteName(name: String): Boolean =
    name.endsWith(".md", ignoreCase = true) && !name.contains("sync-conflict", ignoreCase = true)

/**
 * Collects the Markdown notes below [rootId], descending into subfolders when [recursive].
 * Hidden folders such as `.obsidian` and `.trash` are skipped.
 */
internal fun walkMarkdownTree(
    rootId: String,
    recursive: Boolean,
    listChildren: (folderId: String) -> List<DocumentEntry>,
): MarkdownTree<String> {
    val files = mutableListOf<MarkdownEntry<String>>()
    val folderIds = mutableListOf<String>()
    val pending = ArrayDeque(listOf(rootId to ""))
    while (pending.isNotEmpty()) {
        val (folderId, prefix) = pending.removeFirst()
        folderIds.add(folderId)
        for (child in listChildren(folderId)) {
            if (child.isDirectory) {
                val isConfig = prefix.isEmpty() && child.name.equals(CONFIG_FOLDER_NAME, ignoreCase = true)
                if (recursive && !child.name.startsWith('.') && !isConfig) pending.add(child.id to "$prefix${child.name}/")
            } else if (isMarkdownNoteName(child.name)) {
                files.add(MarkdownEntry(child.id, prefix + child.name.dropLast(MD_SUFFIX_LENGTH)))
            }
        }
    }
    return MarkdownTree(files, folderIds)
}

/** SAF version of [walkMarkdownTree]: one query per folder, instead of DocumentFile's several queries per child. */
internal fun scanMarkdownTree(context: Context, treeUri: Uri, recursive: Boolean): MarkdownTree<Uri> {
    val resolver = context.contentResolver
    val tree = walkMarkdownTree(DocumentsContract.getTreeDocumentId(treeUri), recursive) { folderId ->
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    add(
                        DocumentEntry(
                            id = cursor.getString(0),
                            name = name,
                            isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        }.orEmpty()
    }
    return MarkdownTree(
        files = tree.files.map { MarkdownEntry(DocumentsContract.buildDocumentUriUsingTree(treeUri, it.ref), it.relativePath) },
        folderIds = tree.folderIds,
    )
}

/** The keyword note of the folder [treeUri], or null if it has none. */
internal fun findTagNote(context: Context, treeUri: Uri): Uri? {
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val configId = listChildren(context, treeUri, rootId)
        .firstOrNull { it.isDirectory && it.name.equals(CONFIG_FOLDER_NAME, ignoreCase = true) }
        ?.id ?: return null
    val children = listChildren(context, treeUri, configId).filter { !it.isDirectory }
    val note = TAG_NOTE_NAMES.firstNotNullOfOrNull { wanted ->
        children.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    } ?: return null
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, note.id)
}

private fun listChildren(context: Context, treeUri: Uri, folderId: String): List<DocumentEntry> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
    return context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                add(
                    DocumentEntry(
                        id = cursor.getString(0),
                        name = name,
                        isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    ),
                )
            }
        }
    }.orEmpty()
}

private const val MD_SUFFIX_LENGTH = 3

private val PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
)

/**
 * A cheap fingerprint of the notes below [treeUri]: path, last-modified time and size of every
 * Markdown file, without reading any content. It changes when a note is added, removed, renamed
 * or written.
 */
internal fun markdownTreeFingerprint(context: Context, treeUri: Uri, recursive: Boolean): Long {
    val resolver = context.contentResolver
    val stamps = HashMap<String, String>()
    val tree = walkMarkdownTree(DocumentsContract.getTreeDocumentId(treeUri), recursive) { folderId ->
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        resolver.query(childrenUri, STAMP_PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    if (!isDirectory) {
                        val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                        val size = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                        stamps[id] = "$modified:$size"
                    }
                    add(DocumentEntry(id = id, name = name, isDirectory = isDirectory))
                }
            }
        }.orEmpty()
    }
    return fingerprintOf(tree.files.map { it.relativePath to stamps[it.ref].orEmpty() })
}

private val STAMP_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    DocumentsContract.Document.COLUMN_SIZE,
)
