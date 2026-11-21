package com.isotjs.todosian.data.index

/** A Markdown note as the folder listing reports it. [mtime] is 0 when the provider reports no time. */
data class ListedNote(val docId: String, val uri: String, val relPath: String, val mtime: Long, val size: Long) {
    val hasUsableStamp: Boolean get() = mtime > 0L && size >= 0L
}

/** What one pass over a folder changed in the index. Lists of URIs. */
data class ReconcileResult(
    val added: List<String> = emptyList(),
    /** Content changed by someone else (Syncthing, Obsidian, …). */
    val changed: List<String> = emptyList(),
    /** Content changed to what the app wrote itself; no need to react. */
    val ownWrites: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    /** Old URI to new URI; the note keeps its id. */
    val renamed: Map<String, String> = emptyMap(),
    val unreadable: List<String> = emptyList(),
    /** Number of notes whose content was read in this pass. */
    val read: Int = 0,
) {
    /** Whether anything that lists, reminders or widgets show may differ now. */
    val hasChanges: Boolean
        get() = added.isNotEmpty() || changed.isNotEmpty() || ownWrites.isNotEmpty() || removed.isNotEmpty() || renamed.isNotEmpty()
}

/**
 * Brings the index of one folder in line with the folder listing. Only notes whose time or size
 * differ (or that were not settled or readable last time) are read; a note read with the same
 * content hash only gets its new time and size. The vault stays the truth: whatever the listing
 * and the files say wins over the index.
 */
class Reconciler(
    private val index: VaultIndex,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * [listed] are all Markdown notes of [folder]; [read] returns a note's bytes or null when it
     * cannot be read. [conflictPaths] are the paths of Syncthing conflict copies in the folder.
     * [onProgress] gets (read so far, to read) for the loading indicator.
     *
     * With [scope], only the notes with these URIs are looked at (the folder watch reports single
     * files): a URI of [scope] missing from [listed] is gone, all other notes stay as they are.
     */
    fun reconcile(
        folder: String,
        listed: List<ListedNote>,
        read: (ListedNote) -> ByteArray?,
        conflictPaths: Collection<String> = emptyList(),
        scope: Set<String>? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ReconcileResult {
        val known = index.notes().filter { it.folder == folder && (scope == null || it.uri in scope) }.associateBy { it.uri }
        val listedUris = listed.mapTo(HashSet()) { it.uri }
        // Notes that are gone from their place; one of them may turn up under a new name below.
        val missing = known.values.filter { it.uri !in listedUris }.toMutableList()
        val toRead = listed.filter { entry -> known[entry.uri]?.let { !isCurrent(it, entry) } ?: true }

        val added = ArrayList<String>()
        val changed = ArrayList<String>()
        val ownWrites = ArrayList<String>()
        val unreadable = ArrayList<String>()
        val renamed = LinkedHashMap<String, String>()
        val conflictOriginals = (conflictPaths.asSequence() + listed.asSequence().map { it.relPath })
            .mapNotNullTo(HashSet()) { ConflictNames.originalOf(it) }

        onProgress(0, toRead.size)
        toRead.forEachIndexed { i, entry ->
            val old = known[entry.uri]
            val now = clock()
            val bytes = runCatching { read(entry) }.getOrNull()
            if (bytes == null) {
                // Keep what was known, so lists do not lose tasks for a moment; try again next time.
                val base = old ?: IndexedNote(
                    folder = folder, docId = entry.docId, uri = entry.uri, relPath = entry.relPath,
                    mtime = 0, size = -1, hash = "", lineCount = 0, state = NoteState.UNREADABLE, indexedAt = now,
                )
                index.put(base.copy(docId = entry.docId, relPath = entry.relPath, state = NoteState.UNREADABLE, indexedAt = now))
                unreadable += entry.uri
            } else {
                val content = NoteIndexer.read(bytes)
                val settled = entry.hasUsableStamp && now - entry.mtime >= RACY_MS
                val previous = old ?: missing.firstOrNull { it.hash == content.hash && it.hash.isNotEmpty() }
                    ?.also { missing.remove(it); renamed[it.uri] = entry.uri }
                val note = IndexedNote(
                    id = previous?.id ?: 0,
                    folder = folder,
                    docId = entry.docId,
                    uri = entry.uri,
                    relPath = entry.relPath,
                    mtime = entry.mtime,
                    size = entry.size,
                    hash = content.hash,
                    lineCount = content.lines.size,
                    state = if (settled) NoteState.OK else NoteState.UNSETTLED,
                    indexedAt = now,
                    ownWriteHash = previous?.ownWriteHash,
                    lineHashes = content.lineHashes,
                    baseLineHashes = previous?.baseLineHashes,
                    taskLines = content.taskLines,
                )
                index.put(note)
                when {
                    previous == null -> added += entry.uri
                    previous.uri != entry.uri -> Unit // renamed, already noted
                    previous.hash == content.hash -> Unit // only time or size moved
                    content.hash == previous.ownWriteHash -> ownWrites += entry.uri
                    else -> changed += entry.uri
                }
            }
            onProgress(i + 1, toRead.size)
        }

        // Keep the state before a change while a Syncthing conflict copy exists: it is the common
        // base for merging the copy back. Without a copy the base is dropped. A check of single
        // files does not see the copies, so it leaves the base alone.
        if (scope == null) index.notes().filter { it.folder == folder && it.uri in listedUris }.forEach { note ->
            val conflicted = note.relPath in conflictOriginals
            val base = when {
                !conflicted -> null
                note.baseLineHashes != null -> note.baseLineHashes
                else -> known[note.uri]?.lineHashes ?: note.lineHashes
            }
            val same = (base == null && note.baseLineHashes == null) ||
                (base != null && note.baseLineHashes != null && base.contentEquals(note.baseLineHashes))
            if (!same) index.put(note.copy(baseLineHashes = base))
        }

        missing.forEach { index.remove(it.uri) }
        return ReconcileResult(
            added = added,
            changed = changed,
            ownWrites = ownWrites,
            removed = missing.map { it.uri },
            renamed = renamed,
            unreadable = unreadable,
            read = toRead.size,
        )
    }

    /** Drops the notes of folders that are no longer selected. */
    fun retainFolders(folders: Set<String>): List<String> {
        val gone = index.notes().filter { it.folder !in folders }
        gone.forEach { index.remove(it.uri) }
        return gone.map { it.uri }
    }

    /** Whether the index entry still describes the listed file without reading it. */
    private fun isCurrent(note: IndexedNote, entry: ListedNote): Boolean =
        note.state == NoteState.OK && entry.hasUsableStamp && note.mtime == entry.mtime && note.size == entry.size

    companion object {
        /** A note changed this recently is read again on the next pass (racy clean, as in git). */
        const val RACY_MS = 3_000L
    }
}

/** Names Syncthing gives conflict copies: `Note.sync-conflict-20261121-142233-ABCDEF7`. */
object ConflictNames {
    private val PATTERN = Regex("""^(.*)\.sync-conflict-\d{8}-\d{6}-[A-Z0-9]{7}$""")

    /** The path of the note a conflict copy belongs to, or null when [relPath] is no copy. */
    fun originalOf(relPath: String): String? = PATTERN.find(relPath)?.groupValues?.get(1)

    fun isConflictCopy(relPath: String): Boolean = originalOf(relPath) != null
}
