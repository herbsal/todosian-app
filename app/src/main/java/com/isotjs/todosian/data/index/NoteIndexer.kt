package com.isotjs.todosian.data.index

import com.isotjs.todosian.utils.MarkdownParser
import java.security.MessageDigest

/** What reading one note yields for the index. */
data class NoteContent(val hash: String, val lines: List<String>, val lineHashes: LongArray, val taskLines: List<TaskLine>)

/** Turns a note's bytes into its index entry: content hash, line hashes and task lines. */
object NoteIndexer {
    fun read(bytes: ByteArray): NoteContent {
        val lines = splitLines(bytes.toString(Charsets.UTF_8))
        return NoteContent(
            hash = sha256(bytes),
            lines = lines,
            lineHashes = LongArray(lines.size) { lineHash(lines[it]) },
            taskLines = lines.mapIndexedNotNull { index, line -> if (MarkdownParser.isTaskLine(line)) TaskLine(index, line) else null },
        )
    }

    /** Lines as `BufferedReader.readLines()` yields them: split at \n, \r\n or \r, no empty last line. */
    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                result.add(text.substring(start, i))
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < text.length) result.add(text.substring(start))
        return result
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 64-bit FNV-1a of a line; enough to tell lines apart for merging. */
    fun lineHash(line: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis
        for (b in line.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash
    }
}
