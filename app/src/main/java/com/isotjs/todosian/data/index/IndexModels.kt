package com.isotjs.todosian.data.index

/**
 * The note index: what the app knows about every Markdown note of the vault, kept in a small
 * database. The vault always stays the truth. Everything here is derived from a file and can be
 * thrown away and rebuilt at any time. Writes never use the index as a template.
 */

/** State of a note in the index. */
enum class NoteState {
    /** Read and up to date as far as size, time and content hash go. */
    OK,

    /** Changed right before it was read; read again on the next look (racy clean). */
    UNSETTLED,

    /** Could not be read; tried again on the next look. */
    UNREADABLE,
}

/** A task line of a note, by line number and word for word. */
data class TaskLine(val line: Int, val raw: String)

/**
 * One Markdown note. [lineHashes] holds a short hash per line; with [baseLineHashes] (the state
 * before the last change, kept while a sync conflict is open) it allows a three-way merge without
 * storing the text.
 */
data class IndexedNote(
    val id: Long = 0,
    /** The folder (SAF tree URI) the note belongs to. */
    val folder: String,
    val docId: String,
    val uri: String,
    /** Path in the vault without `.md`, e.g. `Family/Kids`. */
    val relPath: String,
    val mtime: Long,
    val size: Long,
    /** SHA-256 of the content, hex. Empty when not read yet. */
    val hash: String,
    val lineCount: Int,
    val state: NoteState,
    val indexedAt: Long,
    /** Hash of the content this app wrote last; a change report with this hash is our own write. */
    val ownWriteHash: String? = null,
    val lineHashes: LongArray = LongArray(0),
    val baseLineHashes: LongArray? = null,
    val taskLines: List<TaskLine> = emptyList(),
) {
    /** The note's lines as far as tasks go: task lines at their place, all other lines empty. */
    fun sparseLines(): List<String> {
        val result = MutableList(lineCount) { "" }
        taskLines.forEach { if (it.line in result.indices) result[it.line] = it.raw }
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IndexedNote) return false
        return id == other.id && folder == other.folder && docId == other.docId && uri == other.uri &&
            relPath == other.relPath && mtime == other.mtime && size == other.size && hash == other.hash &&
            lineCount == other.lineCount && state == other.state && indexedAt == other.indexedAt &&
            ownWriteHash == other.ownWriteHash && lineHashes.contentEquals(other.lineHashes) &&
            (baseLineHashes?.contentEquals(other.baseLineHashes ?: LongArray(0)) ?: (other.baseLineHashes == null)) &&
            taskLines == other.taskLines
    }

    override fun hashCode(): Int = uri.hashCode() * 31 + hash.hashCode()
}

/** Persistence of the index. The app uses SQLite, tests an in-memory store. */
interface IndexStore {
    fun loadAll(): List<IndexedNote>

    /** Inserts or replaces a note with its task lines in one transaction; returns it with its id. */
    fun upsert(note: IndexedNote): IndexedNote

    fun delete(id: Long)

    fun meta(key: String): String?

    fun setMeta(key: String, value: String?)

    /** Drops everything; the next look rebuilds the index from the vault. */
    fun clear()

    /** Remembers the line hashes [hashes] a note had until [at]; old ones are dropped per [NoteVersions]. */
    fun addVersion(noteId: Long, at: Long, hashes: LongArray)

    /** The oldest remembered line hashes of a note: the base for merging a conflict copy. */
    fun oldestVersion(noteId: Long): LongArray?
}

/**
 * Which earlier states of a note the index keeps, as line hashes only. A conflict copy is merged
 * against the oldest one: a base older than the true common state is safe (changes made on both
 * sides look like the same change), a newer one could undo changes. So the index keeps every
 * state of the last [WINDOW_MS] plus the newest one before, at most [MAX] in all.
 */
object NoteVersions {
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX = 40

    /** Indexes into [times] (oldest first) of the versions to keep at [now]. */
    fun keep(times: List<Long>, now: Long): Set<Int> {
        if (times.isEmpty()) return emptySet()
        val recent = times.indices.filter { now - times[it] < WINDOW_MS }
        val anchor = times.indices.lastOrNull { now - times[it] >= WINDOW_MS }
        // The oldest (the anchor, or the oldest recent one) always stays, then the newest recent ones.
        val oldest = anchor ?: recent.first()
        return (recent.filter { it != oldest }.takeLast(MAX - 1) + oldest).toSet()
    }
}

class InMemoryIndexStore : IndexStore {
    private val notes = LinkedHashMap<Long, IndexedNote>()
    private val metas = HashMap<String, String>()
    private var nextId = 1L

    @Synchronized override fun loadAll(): List<IndexedNote> = notes.values.toList()

    @Synchronized override fun upsert(note: IndexedNote): IndexedNote {
        val stored = if (note.id == 0L) note.copy(id = nextId++) else note
        // A URI belongs to one note only.
        notes.values.firstOrNull { it.uri == stored.uri && it.id != stored.id }?.let { notes.remove(it.id) }
        notes[stored.id] = stored
        return stored
    }

    @Synchronized override fun delete(id: Long) {
        notes.remove(id)
        versions.remove(id)
    }

    @Synchronized override fun meta(key: String): String? = metas[key]

    @Synchronized override fun setMeta(key: String, value: String?) {
        if (value == null) metas.remove(key) else metas[key] = value
    }

    private val versions = HashMap<Long, MutableList<Pair<Long, LongArray>>>()
    var clock: () -> Long = System::currentTimeMillis

    @Synchronized override fun clear() {
        notes.clear()
        metas.clear()
        versions.clear()
    }

    @Synchronized override fun addVersion(noteId: Long, at: Long, hashes: LongArray) {
        val list = versions.getOrPut(noteId) { mutableListOf() }
        list.add(at to hashes)
        list.sortBy { it.first }
        val keep = NoteVersions.keep(list.map { it.first }, clock())
        val kept = list.filterIndexed { index, _ -> index in keep }
        list.clear()
        list.addAll(kept)
    }

    @Synchronized override fun oldestVersion(noteId: Long): LongArray? = versions[noteId]?.firstOrNull()?.second
}
