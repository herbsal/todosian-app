package com.isotjs.todosian.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Makes saving a note robust although the storage access framework cannot replace a file
 * atomically. Before a note is overwritten, the old and the new content are put in a journal
 * entry in app-private storage (written to a temporary file and renamed, which is atomic there).
 * After the write the note is read back and compared; only then is the entry removed.
 *
 * If the app is killed in between, [recover] finds the entry on the next start. A note that holds
 * the old or the new content is fine. A note that holds a cut-off part of the new content was
 * written halfway, and the write is completed. Anything else means another app wrote the note
 * meanwhile, so it is left as it is.
 */
class WriteJournal(private val dir: File) {

    data class Entry(val uri: String, val old: ByteArray, val new: ByteArray)

    enum class Recovery { NOTHING, COMPLETE }

    fun begin(uri: String, old: ByteArray, new: ByteArray) {
        dir.mkdirs()
        val target = fileFor(uri)
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            DataOutputStream(out).apply {
                writeInt(VERSION)
                writeUTF(uri)
                writeInt(old.size)
                write(old)
                writeInt(new.size)
                write(new)
                flush()
            }
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("Unable to write the save journal")
        }
    }

    fun finish(uri: String) {
        fileFor(uri).delete()
    }

    fun pending(): List<Entry> =
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty().mapNotNull { file ->
            runCatching { read(file) }.getOrElse {
                file.delete()
                null
            }
        }

    private fun read(file: File): Entry = DataInputStream(file.inputStream().buffered()).use { input ->
        check(input.readInt() == VERSION)
        val uri = input.readUTF()
        val old = ByteArray(input.readInt()).also { input.readFully(it) }
        val new = ByteArray(input.readInt()).also { input.readFully(it) }
        Entry(uri, old, new)
    }

    private fun fileFor(uri: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        return File(dir, digest.take(16).joinToString("") { "%02x".format(it) } + SUFFIX)
    }

    companion object {
        private const val VERSION = 1
        private const val SUFFIX = ".journal"

        /** What to do with a note whose save was interrupted; [current] is null if it cannot be read. */
        fun recoveryFor(current: ByteArray?, entry: Entry): Recovery {
            if (current == null) return Recovery.NOTHING
            if (current.contentEquals(entry.new) || current.contentEquals(entry.old)) return Recovery.NOTHING
            val cutOff = current.size < entry.new.size &&
                current.indices.all { current[it] == entry.new[it] }
            return if (cutOff) Recovery.COMPLETE else Recovery.NOTHING
        }
    }
}
