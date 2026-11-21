package com.isotjs.todosian.data

import android.content.Context
import com.isotjs.todosian.utils.MarkdownParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Last-modified time (ms) and size of a note, as the folder listing reports them. */
internal data class FileStamp(val lastModified: Long, val size: Long) {
    /** Some providers report no time; such files are always read. */
    val isUsable: Boolean get() = lastModified > 0L && size >= 0L
}

/**
 * Remembers the task lines of every note together with its [FileStamp], so that lists, reminders
 * and widgets read only the notes that changed since the last look (as Obsidian does). Only task
 * lines are kept, by their line number; all other lines come back empty, which is all the task
 * parser needs. The index is kept in memory and in a small file, so it also helps after a restart.
 *
 * Safety:
 * - A note whose stamp differs is read again.
 * - A note written less than [RACY_MS] before it was read is not remembered, since a second change
 *   within the same moment could keep the stamp (the "racy clean" problem known from git).
 * - The app's own writes forget the note, and a pull to refresh reads everything again.
 * - Editing and saving always read the file itself, never the index.
 */
internal class NoteIndex(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val stamp: FileStamp, val lineCount: Int, val indices: IntArray, val lines: Array<String>) {
        fun toLines(): List<String> {
            val result = ArrayList<String>(lineCount)
            repeat(lineCount) { result.add("") }
            indices.forEachIndexed { i, index -> if (index < lineCount) result[index] = lines[i] }
            return result
        }
    }

    private val entries = HashMap<String, Entry>()
    private var loaded = false
    private var dirty = false

    /** Number of reads the index answered and the number of notes read; for logs and tests. */
    @Volatile var hits = 0
        private set
    @Volatile var misses = 0
        private set

    /**
     * The lines of the note [key] as far as tasks go: from the index when [stamp] still matches,
     * else from [read], which is then remembered. [fresh] skips the index.
     */
    fun lines(key: String, stamp: FileStamp?, fresh: Boolean = false, read: () -> List<String>): List<String> {
        if (!fresh && stamp != null && stamp.isUsable) {
            synchronized(this) {
                ensureLoaded()
                entries[key]?.takeIf { it.stamp == stamp }?.let {
                    hits++
                    return it.toLines()
                }
            }
        }
        // Read outside the lock: reading may be slow and others may use the index meanwhile.
        val lines = read()
        misses++
        synchronized(this) {
            ensureLoaded()
            if (stamp != null && stamp.isUsable && clock() - stamp.lastModified >= RACY_MS) {
                entries[key] = entryOf(stamp, lines)
            } else {
                entries.remove(key)
            }
            dirty = true
        }
        return lines
    }

    /** The note [key] was written by the app: read it again next time. */
    @Synchronized
    fun forget(key: String) {
        ensureLoaded()
        if (entries.remove(key) != null) dirty = true
    }

    /** Drops notes that no longer exist; [keys] are all notes of all folders. */
    @Synchronized
    fun retainOnly(keys: Set<String>) {
        ensureLoaded()
        if (entries.keys.retainAll(keys)) dirty = true
    }

    @Synchronized
    fun clear() {
        entries.clear()
        loaded = true
        dirty = true
        save()
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return entries.size
    }

    /** Writes the index to its file if it changed. A failure only costs a slower next start. */
    @Synchronized
    fun save() {
        val file = file
        if (!dirty || file == null) return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeInt(entries.size)
                entries.forEach { (key, entry) ->
                    out.writeString(key)
                    out.writeLong(entry.stamp.lastModified)
                    out.writeLong(entry.stamp.size)
                    out.writeInt(entry.lineCount)
                    out.writeInt(entry.indices.size)
                    entry.indices.forEachIndexed { i, index ->
                        out.writeInt(index)
                        out.writeString(entry.lines[i])
                    }
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            dirty = false
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val file = file
        if (file == null || !file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != VERSION) return@use
                repeat(input.readInt()) {
                    val key = input.readString()
                    val stamp = FileStamp(input.readLong(), input.readLong())
                    val lineCount = input.readInt()
                    val count = input.readInt()
                    val indices = IntArray(count)
                    val lines = Array(count) { i ->
                        indices[i] = input.readInt()
                        input.readString()
                    }
                    entries[key] = Entry(stamp, lineCount, indices, lines)
                }
            }
        }.onFailure {
            // A broken index is simply rebuilt.
            entries.clear()
            file.delete()
        }
    }

    private fun entryOf(stamp: FileStamp, lines: List<String>): Entry {
        val indices = ArrayList<Int>()
        val kept = ArrayList<String>()
        lines.forEachIndexed { index, line ->
            if (MarkdownParser.isTaskLine(line)) {
                indices += index
                kept += line
            }
        }
        return Entry(stamp, lines.size, indices.toIntArray(), kept.toTypedArray())
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val bytes = ByteArray(readInt())
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        /** Notes changed this recently are read again next time, see the class comment. */
        const val RACY_MS = 3_000L
        private const val VERSION = 1
        private const val FILE_NAME = "note-index.bin"

        @Volatile private var instance: NoteIndex? = null

        /** One index per app process, shared by lists, reminders and widgets. */
        fun get(context: Context): NoteIndex =
            instance ?: synchronized(this) {
                instance ?: NoteIndex(File(context.applicationContext.noBackupFilesDir, FILE_NAME)).also { instance = it }
            }
    }
}
