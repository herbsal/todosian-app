package com.isotjs.todosian.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.isotjs.todosian.data.index.IndexedNote
import com.isotjs.todosian.data.index.ListedNote
import com.isotjs.todosian.data.index.NoteIndexer
import com.isotjs.todosian.data.index.NoteState
import com.isotjs.todosian.data.index.ReconcileResult
import com.isotjs.todosian.data.index.Reconciler
import com.isotjs.todosian.data.index.VaultIndex
import java.io.File

/** A note of the vault together with what the index knows about it. */
internal data class SyncedNote(val entry: MarkdownEntry<Uri>, val note: IndexedNote?) {
    /** The note's lines as far as tasks go (task lines at their place, other lines empty). */
    val lines: List<String> get() = note?.sparseLines().orEmpty()

    /** False when the note could not be read yet; its tasks are then unknown. */
    val isKnown: Boolean get() = note != null && note.hash.isNotEmpty()
}

/**
 * Lists a folder, brings the note index in line with it and hands out the notes. Lists,
 * reminders and widgets all go through here, so a note is read once per change, not once per
 * consumer. Editing and saving never use this: they always read the file itself.
 */
internal class VaultSync(private val context: Context, private val index: VaultIndex) {
    private val reconciler = Reconciler(index)
    private val lock = Any()

    /**
     * All Markdown notes of [folder]. [fresh] reads every note again (pull to refresh).
     * Throws when the folder itself cannot be listed.
     */
    fun notes(
        folder: Uri,
        recursive: Boolean,
        fresh: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedNote> {
        val tree = scanMarkdownTree(context, folder, recursive)
        val key = folder.toString()
        val listed = tree.files.map { it.toListed() }
        val result: ReconcileResult
        synchronized(lock) {
            if (fresh) invalidateFolder(key)
            result = reconciler.reconcile(key, listed, ::readBytes, conflictPaths = tree.conflictPaths, onProgress = onProgress)
            noteRenames(result.renamed)
        }
        if (result.read > 0) {
            Log.d(TAG, "sync $key: ${listed.size} notes, read ${result.read}, +${result.added.size} ~${result.changed.size} -${result.removed.size}")
        }
        return tree.files.map { SyncedNote(it, index.note(it.ref.toString())) }
    }

    /**
     * A number that changes whenever a note of [folders] is added, removed, renamed or gets other
     * content, as far as the index knows. Reads nothing.
     */
    fun fingerprint(folders: Collection<Uri>): Long {
        val keys = folders.mapTo(HashSet()) { it.toString() }
        return index.notes()
            .filter { it.folder in keys }
            .sortedBy { it.uri }
            .fold(keys.size.toLong()) { acc, note -> acc * 31 + (note.uri.hashCode().toLong() shl 32 xor note.hash.hashCode().toLong()) }
    }

    /** Drops notes of folders that are no longer selected. */
    fun retainFolders(folders: Collection<Uri>) {
        synchronized(lock) { reconciler.retainFolders(folders.mapTo(HashSet()) { it.toString() }) }
    }

    /**
     * The app wrote [bytes] to [uri]: the index takes them over directly, with time and size from
     * a query of this one file. Its hash is kept as our own write, so the change report that
     * follows causes nothing. A note the index does not know yet is left to the next look.
     */
    fun recordOwnWrite(uri: Uri, bytes: ByteArray) {
        val old = index.note(uri.toString()) ?: return
        val content = NoteIndexer.read(bytes)
        val stamp = runCatching { queryStamp(uri) }.getOrNull()
        val now = System.currentTimeMillis()
        val settled = stamp != null && stamp.lastModified > 0 && now - stamp.lastModified >= Reconciler.RACY_MS
        index.put(
            old.copy(
                mtime = stamp?.lastModified ?: 0L,
                size = stamp?.size ?: -1L,
                hash = content.hash,
                lineCount = content.lines.size,
                // Just written, so the time is fresh: read once more on the next look (racy clean).
                state = if (settled) NoteState.OK else NoteState.UNSETTLED,
                indexedAt = now,
                ownWriteHash = content.hash,
                lineHashes = content.lineHashes,
                taskLines = content.taskLines,
            ),
        )
    }

    /**
     * Notes renamed since the last call, old URI to new URI, and forgets them. Reminders use it to
     * keep "already shown" with a renamed note.
     */
    fun takeRenames(): Map<String, String> = synchronized(lock) {
        val renames = decodeRenames(index.meta(KEY_RENAMES))
        if (renames.isNotEmpty()) index.setMeta(KEY_RENAMES, null)
        renames
    }

    private fun noteRenames(renamed: Map<String, String>) {
        if (renamed.isEmpty()) return
        val all = decodeRenames(index.meta(KEY_RENAMES)) + renamed
        // Only the latest matter; a rename nobody asked for in a while is dropped.
        index.setMeta(KEY_RENAMES, all.entries.toList().takeLast(MAX_RENAMES).joinToString("\n") { "${it.key}\u001F${it.value}" })
    }

    private fun decodeRenames(value: String?): Map<String, String> =
        value.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\u001F')
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap(LinkedHashMap())

    /** The app renamed a note to [newName] (no `.md`): it keeps its entry under the new URI. */
    fun recordRename(oldUri: Uri, newUri: Uri, newName: String) {
        synchronized(lock) { noteRenames(mapOf(oldUri.toString() to newUri.toString())) }
        val old = index.note(oldUri.toString()) ?: return
        index.remove(oldUri.toString())
        index.put(
            old.copy(
                uri = newUri.toString(),
                docId = newUri.toString().substringAfterLast("/document/"),
                relPath = old.relPath.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" },
                state = NoteState.UNSETTLED,
            ),
        )
    }

    /** The app deleted a note. */
    fun recordDelete(uri: Uri) {
        index.remove(uri.toString())
    }

    /**
     * Checks only the notes [paths] (relative to [folder], with `.md`) that the folder watch
     * reported, without listing the folder. Returns null when a full look is needed instead.
     */
    fun checkFiles(folder: Uri, paths: Collection<String>, recursive: Boolean): ReconcileResult? {
        if (paths.any { it.contains("sync-conflict", ignoreCase = true) }) return null
        // The same notes a folder listing would yield: no hidden folders, not Todosian's own notes.
        val notes = paths.filter { path ->
            val folders = path.split('/').dropLast(1)
            (recursive || folders.isEmpty()) && folders.none { it.startsWith('.') } &&
                folders.firstOrNull()?.equals(CONFIG_FOLDER_NAME, ignoreCase = true) != true
        }
        if (notes.isEmpty()) return ReconcileResult()
        val treeId = DocumentsContract.getTreeDocumentId(folder)
        val key = folder.toString()
        val listed = ArrayList<ListedNote>()
        val scope = HashSet<String>()
        notes.forEach { path ->
            val docId = if (treeId.endsWith(":") || treeId.endsWith("/")) treeId + path else "$treeId/$path"
            val uri = DocumentsContract.buildDocumentUriUsingTree(folder, docId)
            scope += uri.toString()
            val stamp = runCatching { queryStamp(uri) }.getOrNull() ?: return@forEach
            listed += ListedNote(uri.toString().substringAfterLast("/document/"), uri.toString(), path.dropLast(3), stamp.lastModified, stamp.size)
        }
        return synchronized(lock) {
            reconciler.reconcile(key, listed, ::readBytes, scope = scope).also { noteRenames(it.renamed) }
        }
    }

    /** Time and size of one file; throws when it does not exist (any more). */
    private fun queryStamp(uri: Uri): FileStamp {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                return FileStamp(if (c.isNull(0)) 0L else c.getLong(0), if (c.isNull(1)) -1L else c.getLong(1))
            }
        }
        throw java.io.FileNotFoundException(uri.toString())
    }

    private fun invalidateFolder(folder: String) {
        index.notes().filter { it.folder == folder && it.state == NoteState.OK }
            .forEach { index.put(it.copy(state = NoteState.UNSETTLED)) }
    }

    private fun readBytes(note: ListedNote): ByteArray? =
        context.contentResolver.openInputStream(Uri.parse(note.uri))?.use { it.readBytes() }

    private fun MarkdownEntry<Uri>.toListed(): ListedNote {
        val uri = ref.toString()
        return ListedNote(
            docId = uri.substringAfterLast("/document/"),
            uri = uri,
            relPath = relativePath,
            mtime = stamp?.lastModified ?: 0L,
            size = stamp?.size ?: -1L,
        )
    }

    companion object {
        private const val TAG = "VaultSync"
        private const val OLD_INDEX_FILE = "note-index.bin"
        private const val KEY_RENAMES = "pending_renames"
        private const val MAX_RENAMES = 200

        @Volatile private var instance: VaultSync? = null

        fun get(context: Context): VaultSync =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    // The file cache of the first index version is replaced by the database.
                    runCatching { File(app.noBackupFilesDir, OLD_INDEX_FILE).delete() }
                    VaultSync(app, VaultIndex.get(app))
                }.also { instance = it }
            }
    }
}
