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

/** Accepted names of the folder inside [CONFIG_FOLDER_NAME] that holds reminder profiles. */
internal val PROFILE_FOLDER_NAMES = listOf("Reminders", "Erinnerungen")

/** Accepted names of the keyword note inside [CONFIG_FOLDER_NAME]. */
internal val TAG_NOTE_NAMES = listOf("Tags.md", "Schlagwörter.md", "Schlagwoerter.md")

/** Names of the note with saved views (keyword filters with a name) in `Todosian/`. */
internal val VIEW_NOTE_NAMES = listOf("Views.md", "Ansichten.md")

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
internal fun findTagNote(context: Context, treeUri: Uri): Uri? = findConfigNote(context, treeUri, TAG_NOTE_NAMES)

/** The first note in `Todosian/` of [treeUri] named like one of [names], or null. */
internal fun findConfigNote(context: Context, treeUri: Uri, names: List<String>): Uri? {
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val configId = listChildren(context, treeUri, rootId)
        .firstOrNull { it.isDirectory && it.name.equals(CONFIG_FOLDER_NAME, ignoreCase = true) }
        ?.id ?: return null
    val children = listChildren(context, treeUri, configId).filter { !it.isDirectory }
    val note = names.firstNotNullOfOrNull { wanted ->
        children.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    } ?: return null
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, note.id)
}

/** The reminder profiles of the folder [treeUri]: note name without `.md` to its URI, sorted by name. */
internal fun findReminderProfiles(context: Context, treeUri: Uri): List<Pair<String, Uri>> {
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val configId = listChildren(context, treeUri, rootId)
        .firstOrNull { it.isDirectory && it.name.equals(CONFIG_FOLDER_NAME, ignoreCase = true) }
        ?.id ?: return emptyList()
    val configChildren = listChildren(context, treeUri, configId)
    val profilesId = PROFILE_FOLDER_NAMES.firstNotNullOfOrNull { wanted ->
        configChildren.firstOrNull { it.isDirectory && it.name.equals(wanted, ignoreCase = true) }
    }?.id ?: return emptyList()
    return listChildren(context, treeUri, profilesId)
        .filter { !it.isDirectory && isMarkdownNoteName(it.name) }
        .map { it.name.dropLast(MD_SUFFIX_LENGTH) to DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id) }
        .sortedBy { it.first.lowercase() }
}

/**
 * Finds or creates a note below the config folder: `Todosian/<file>` or `Todosian/<sub>/<file>`.
 * Existing folders and files are matched by any of the given names, ignoring case; new ones get
 * the first name. Returns the note's URI and whether it was just created.
 */
internal fun ensureConfigNote(
    context: Context,
    treeUri: Uri,
    subfolderNames: List<String>?,
    fileNames: List<String>,
): Pair<Uri, Boolean> {
    val resolver = context.contentResolver
    fun ensureDir(parentId: String, names: List<String>): String =
        listChildren(context, treeUri, parentId)
            .firstOrNull { child -> child.isDirectory && names.any { child.name.equals(it, ignoreCase = true) } }
            ?.id
            ?: DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId),
                DocumentsContract.Document.MIME_TYPE_DIR,
                names.first(),
            )?.let(DocumentsContract::getDocumentId)
            ?: throw IllegalStateException("Unable to create folder ${names.first()}")

    var parentId = ensureDir(DocumentsContract.getTreeDocumentId(treeUri), listOf(CONFIG_FOLDER_NAME))
    if (subfolderNames != null) parentId = ensureDir(parentId, subfolderNames)
    val existing = listChildren(context, treeUri, parentId)
        .firstOrNull { child -> !child.isDirectory && fileNames.any { child.name.equals(it, ignoreCase = true) } }
    if (existing != null) return DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.id) to false
    val created = DocumentsContract.createDocument(
        resolver,
        DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId),
        "text/markdown",
        fileNames.first(),
    ) ?: throw IllegalStateException("Unable to create ${fileNames.first()}")
    return created to true
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
