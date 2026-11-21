package com.isotjs.todosian.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.isotjs.todosian.data.index.ConflictNames
import com.isotjs.todosian.data.index.IndexedNote
import com.isotjs.todosian.data.index.ListedNote
import com.isotjs.todosian.data.index.NoteIndexer
import com.isotjs.todosian.data.index.NoteState
import com.isotjs.todosian.data.index.ReconcileResult
import com.isotjs.todosian.data.index.Reconciler
import com.isotjs.todosian.data.index.VaultIndex
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import com.isotjs.todosian.data.settings.isInExcludedFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A note of the vault together with what the index knows about it. */
internal data class SyncedNote(val entry: MarkdownEntry<Uri>, val note: IndexedNote?) {
    /** The note's lines as far as tasks go (task lines at their place, other lines empty). */
    val lines: List<String> get() = note?.sparseLines().orEmpty()

    /** False when the note could not be read yet; its tasks are then unknown. */
    val isKnown: Boolean get() = note != null && note.hash.isNotEmpty()
}

/** A look at the folders in progress, for the loading indicator. */
internal data class SyncProgress(
    val done: Int,
    val total: Int,
    /** The index knew nothing about this folder yet (new install, rebuild, new folder). */
    val firstBuild: Boolean,
)

/** A Syncthing conflict: the note [relPath] of [folder] has a conflict copy [copyPath] next to it. */
internal data class NoteConflict(val folder: String, val relPath: String, val copyPath: String)

/** What the settings show about the note index. Times in ms since 1970. */
internal data class IndexStats(
    val notes: Int,
    val tasks: Int,
    val lastSync: Long?,
    val lastDeepCheck: Long?,
    val lastDeepCheckFixed: Int?,
)

/**
 * Lists a folder, brings the note index in line with it and hands out the notes. Lists,
 * reminders and widgets all go through here, so a note is read once per change, not once per
 * consumer. Editing and saving never use this: they always read the file itself.
 */
internal class VaultSync(private val context: Context, private val index: VaultIndex) {
    private val reconciler = Reconciler(index)
    private val lock = Any()

    /** All Markdown notes of [folder]. Throws when the folder itself cannot be listed. */
    fun notes(
        folder: Uri,
        recursive: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedNote> = sync(folder, recursive, readAll = false, onProgress = onProgress).first

    /**
     * The deep check: reads every note of [folders] again and returns how many the index had
     * wrong (or that changed without being noticed). Folders that cannot be listed are skipped.
     */
    fun deepCheck(folders: Collection<Uri>, recursive: Boolean): Int {
        var fixed = 0
        folders.forEach { folder ->
            runCatching { sync(folder, recursive, readAll = true).second }
                .onSuccess { fixed += it.added.size + it.changed.size + it.removed.size + it.renamed.size }
                .onFailure { Log.w(TAG, "Deep check of $folder failed", it) }
        }
        index.setMeta(KEY_LAST_DEEP_CHECK, "${System.currentTimeMillis()}:$fixed")
        return fixed
    }

    /** Throws the index away; the next look reads every note again. */
    fun rebuild() {
        synchronized(lock) { index.rebuild() }
    }

    fun stats(): IndexStats {
        val notes = index.notes()
        val deep = index.meta(KEY_LAST_DEEP_CHECK)?.split(':')
        return IndexStats(
            notes = notes.size,
            tasks = notes.sumOf { it.taskLines.size },
            lastSync = lastSync.takeIf { it > 0 },
            lastDeepCheck = deep?.getOrNull(0)?.toLongOrNull(),
            lastDeepCheckFixed = deep?.getOrNull(1)?.toIntOrNull(),
        )
    }

    @Volatile private var lastSync = 0L

    private fun updateConflicts(folder: String, copies: List<String>) {
        val found = copies.mapNotNull { copy ->
            ConflictNames.originalOf(copy)?.let { NoteConflict(folder, it, copy) }
        }
        val others = _conflicts.value.filter { it.folder != folder }
        if (others.size + found.size != _conflicts.value.size || !_conflicts.value.containsAll(found)) {
            _conflicts.value = (others + found).sortedBy { it.relPath.lowercase() }
        }
    }

    /** Conflicts of folders that are no longer selected go away with them. */
    private fun retainConflicts(folders: Set<String>) {
        if (_conflicts.value.any { it.folder !in folders }) _conflicts.value = _conflicts.value.filter { it.folder in folders }
    }

    /** Folder and path of a note as the index knows them, or null. */
    fun noteInfo(uri: Uri): Pair<Uri, String>? = index.note(uri.toString())?.let { Uri.parse(it.folder) to it.relPath }

    /**
     * The note a wikilink [name] (`Kinder` or `Familie/Kinder`) points to in [folder]: same path,
     * else the same name anywhere, as Obsidian resolves it.
     */
    fun findNote(folder: Uri, name: String): Uri? {
        val wanted = name.trim().removeSuffix(".md").removePrefix("/")
        val notes = index.notes().filter { it.folder == folder.toString() }
        val match = notes.firstOrNull { it.relPath.equals(wanted, ignoreCase = true) }
            ?: notes.firstOrNull { it.relPath.substringAfterLast('/').equals(wanted.substringAfterLast('/'), ignoreCase = true) }
        return match?.let { Uri.parse(it.uri) }
    }

    /**
     * The notes of [folder] as the index knows them, without looking at the folder: what the home
     * screen shows right away while the look runs.
     */
    fun indexedNotes(folder: Uri): List<SyncedNote> {
        val key = folder.toString()
        val excluded = SharedPrefsAppSettingsRepository.excludedFolders(context)
        return index.notes()
            .filter { it.folder == key && !isInExcludedFolder(it.relPath, excluded) }
            .map { SyncedNote(MarkdownEntry(Uri.parse(it.uri), it.relPath), it) }
    }

    private fun sync(
        folder: Uri,
        recursive: Boolean,
        readAll: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<List<SyncedNote>, ReconcileResult> {
        val started = System.currentTimeMillis()
        val key = folder.toString()
        val excluded = SharedPrefsAppSettingsRepository.excludedFolders(context)
        val scanned = scanMarkdownTree(context, folder, recursive)
        // Notes in excluded folders do not exist for the app: not listed, not indexed, no reminders.
        val tree = scanned.copy(
            files = scanned.files.filter { !isInExcludedFolder(it.relativePath, excluded) },
            conflictPaths = scanned.conflictPaths.filter { !isInExcludedFolder(it, excluded) },
        )
        val listed = tree.files.map { it.toListed() }
        val result: ReconcileResult
        synchronized(lock) {
            if (readAll) invalidateFolder(key)
            val before = index.notes().filter { it.folder == key }.associate { it.uri to it.relPath }
            val firstBuild = before.isEmpty()
            result = try {
                reconciler.reconcile(key, listed, ::readBytes, conflictPaths = tree.conflictPaths) { done, total ->
                    // Shown only while there is something to read; a look without changes shows nothing.
                    _progress.value = if (total == 0 || done >= total) null else SyncProgress(done, total, firstBuild)
                    onProgress(done, total)
                }
            } finally {
                _progress.value = null
            }
            noteRenames(result.renamed)
            // Gone from the vault, not just left out by an excluded folder.
            val excludedUris = scanned.files.filter { isInExcludedFolder(it.relativePath, excluded) }.mapTo(HashSet()) { it.ref.toString() }
            noteDeletions(result.removed.filter { it !in excludedUris }.associateWith { before[it].orEmpty() })
        }
        updateConflicts(key, tree.conflictPaths)
        lastSync = System.currentTimeMillis()
        if (result.read > 0) {
            Log.d(
                TAG,
                "sync $key: ${listed.size} notes, read ${result.read} in ${lastSync - started} ms, " +
                    "+${result.added.size} ~${result.changed.size} -${result.removed.size}",
            )
        }
        return tree.files.map { SyncedNote(it, index.note(it.ref.toString())) } to result
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
        val keys = folders.mapTo(HashSet()) { it.toString() }
        synchronized(lock) { reconciler.retainFolders(keys) }
        retainConflicts(keys)
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

    /**
     * Notes deleted outside the app since the last call, URI to path, and forgets them. Reminders
     * use it to say which note's reminders were cancelled.
     */
    fun takeDeletions(): Map<String, String> = synchronized(lock) {
        val deletions = decodeRenames(index.meta(KEY_DELETIONS))
        if (deletions.isNotEmpty()) index.setMeta(KEY_DELETIONS, null)
        deletions
    }

    private fun noteDeletions(deleted: Map<String, String>) {
        if (deleted.isEmpty()) return
        val all = decodeRenames(index.meta(KEY_DELETIONS)) + deleted
        index.setMeta(KEY_DELETIONS, all.entries.toList().takeLast(MAX_RENAMES).joinToString("\n") { "${it.key}\u001F${it.value}" })
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
        val excluded = SharedPrefsAppSettingsRepository.excludedFolders(context)
        val notes = paths.filter { path ->
            val folders = path.split('/').dropLast(1)
            (recursive || folders.isEmpty()) && folders.none { it.startsWith('.') } &&
                folders.firstOrNull()?.equals(CONFIG_FOLDER_NAME, ignoreCase = true) != true &&
                !isInExcludedFolder(path, excluded)
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
            val names = scope.associateWith { index.note(it)?.relPath.orEmpty() }
            reconciler.reconcile(key, listed, ::readBytes, scope = scope).also { result ->
                noteRenames(result.renamed)
                noteDeletions(result.removed.associateWith { names[it].orEmpty() })
            }
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

    /** The app is about to write [uri]: should the write fail half way, the next look reads it. */
    fun invalidate(uri: Uri) {
        index.note(uri.toString())?.let { index.put(it.copy(state = NoteState.UNSETTLED)) }
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

        private val _progress = MutableStateFlow<SyncProgress?>(null)

        /** The look at the folders that is running now, or null. */
        val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

        private val _conflicts = MutableStateFlow<List<NoteConflict>>(emptyList())

        /** Notes with a Syncthing conflict copy, as of the last look at their folder. */
        val conflicts: StateFlow<List<NoteConflict>> = _conflicts.asStateFlow()
        private const val OLD_INDEX_FILE = "note-index.bin"
        private const val KEY_RENAMES = "pending_renames"
        private const val KEY_LAST_DEEP_CHECK = "last_deep_check"
        private const val KEY_DELETIONS = "pending_deletions"
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
