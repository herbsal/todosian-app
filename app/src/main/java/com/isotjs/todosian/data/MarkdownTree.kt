package com.isotjs.todosian.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Last-modified time (ms) and size of a note, as the folder listing reports them. */
internal data class FileStamp(val lastModified: Long, val size: Long)

internal data class DocumentEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val stamp: FileStamp? = null,
)

/** A Markdown note; [relativePath] is relative to the selected folder and has no `.md` suffix, e.g. `Projects/Move`. */
internal data class MarkdownEntry<T>(val ref: T, val relativePath: String, val stamp: FileStamp? = null)

/** [conflictPaths] are the paths (no `.md`) of Syncthing conflict copies; they deliver no tasks. */
internal data class MarkdownTree<T>(
    val files: List<MarkdownEntry<T>>,
    val folderIds: List<String>,
    val conflictPaths: List<String> = emptyList(),
)

/** Folder at the root of a notes folder that holds Todosian's own notes (keywords, reminder profiles). */
internal const val CONFIG_FOLDER_NAME = "Todosian"

/** Accepted names of the folder inside [CONFIG_FOLDER_NAME] that holds reminder profiles. */
internal val PROFILE_FOLDER_NAMES = listOf("Reminders")

/** Accepted names of the keyword note inside [CONFIG_FOLDER_NAME]. */
internal val TAG_NOTE_NAMES = listOf("Tags.md")

/** Names of the note with saved views (keyword filters with a name) in `Todosian/`. */
internal val VIEW_NOTE_NAMES = listOf("Views.md")

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
    val conflicts = mutableListOf<String>()
    val pending = ArrayDeque(listOf(rootId to ""))
    while (pending.isNotEmpty()) {
        val (folderId, prefix) = pending.removeFirst()
        folderIds.add(folderId)
        for (child in listChildren(folderId)) {
            if (child.isDirectory) {
                val isConfig = prefix.isEmpty() && child.name.equals(CONFIG_FOLDER_NAME, ignoreCase = true)
                if (recursive && !child.name.startsWith('.') && !isConfig) pending.add(child.id to "$prefix${child.name}/")
            } else if (isMarkdownNoteName(child.name)) {
                files.add(MarkdownEntry(child.id, prefix + child.name.dropLast(MD_SUFFIX_LENGTH), child.stamp))
            } else if (child.name.endsWith(".md", ignoreCase = true)) {
                conflicts.add(prefix + child.name.dropLast(MD_SUFFIX_LENGTH))
            }
        }
    }
    return MarkdownTree(files, folderIds, conflicts)
}

/** SAF version of [walkMarkdownTree]: one query per folder, instead of DocumentFile's several queries per child. */
internal fun scanMarkdownTree(context: Context, treeUri: Uri, recursive: Boolean): MarkdownTree<Uri> {
    val resolver = context.contentResolver
    val tree = walkMarkdownTree(DocumentsContract.getTreeDocumentId(treeUri), recursive) { folderId ->
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        // Time and size come with the listing at no extra cost; the note index compares them.
        resolver.query(childrenUri, STAMP_PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    val isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val stamp = if (isDirectory || cursor.isNull(3) || cursor.isNull(4)) null else FileStamp(cursor.getLong(3), cursor.getLong(4))
                    add(DocumentEntry(id = cursor.getString(0), name = name, isDirectory = isDirectory, stamp = stamp))
                }
            }
        }.orEmpty()
    }
    return MarkdownTree(
        files = tree.files.map { MarkdownEntry(DocumentsContract.buildDocumentUriUsingTree(treeUri, it.ref), it.relativePath, it.stamp) },
        folderIds = tree.folderIds,
        conflictPaths = tree.conflictPaths,
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
        children.firstOrNull { sameName(it.name, wanted) }
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
        .firstOrNull { child -> !child.isDirectory && fileNames.any { sameName(child.name, it) } }
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

private val STAMP_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    DocumentsContract.Document.COLUMN_SIZE,
)

/** File names compared case-insensitively and with umlauts in either Unicode form (ö or o + ¨). */
internal fun sameName(a: String, b: String): Boolean =
    java.text.Normalizer.normalize(a, java.text.Normalizer.Form.NFC)
        .equals(java.text.Normalizer.normalize(b, java.text.Normalizer.Form.NFC), ignoreCase = true)

/** All files of a note folder that are no notes (images, PDFs, …), by path and by name. */
internal class VaultFiles(private val byPath: Map<String, Uri>, private val byName: Map<String, Uri>) {
    /**
     * The file a note links to: [link] as written in the note, either a path from the folder, a
     * path from the note's own folder [noteDir], or a bare name as Obsidian allows.
     */
    fun find(link: String, noteDir: String): Uri? {
        val path = link.trim().removePrefix("./").removePrefix("/")
        return byPath[path.lowercase()]
            ?: (if (noteDir.isNotEmpty()) byPath["$noteDir/$path".lowercase()] else null)
            ?: byName[path.substringAfterLast('/').lowercase()]
    }
}

/** Lists every file below [treeUri] that is not a note, hidden folders left out. */
internal fun listVaultFiles(context: Context, treeUri: Uri, maxFolders: Int = 2_000): VaultFiles {
    val byPath = HashMap<String, Uri>()
    val byName = HashMap<String, Uri>()
    val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(treeUri) to ""))
    var folders = 0
    while (pending.isNotEmpty() && folders++ < maxFolders) {
        val (folderId, prefix) = pending.removeFirst()
        listChildren(context, treeUri, folderId).forEach { child ->
            when {
                child.isDirectory -> if (!child.name.startsWith('.')) pending.add(child.id to "$prefix${child.name}/")
                !child.name.endsWith(".md", ignoreCase = true) -> {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                    byPath["$prefix${child.name}".lowercase()] = uri
                    byName.putIfAbsent(child.name.lowercase(), uri)
                }
            }
        }
    }
    return VaultFiles(byPath, byName)
}
