package com.isotjs.todosian.data

import com.isotjs.todosian.utils.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NoteIndexTest {
    private val now = 1_000_000_000L
    private val old = FileStamp(lastModified = now - 60_000, size = 120)
    private val note = listOf(
        "# Haushalt",
        "Freier Text",
        "- [ ] Müll rausbringen 📅 2026-11-21",
        "  - [x] Tüten kaufen",
        "",
        "- [ ] Fenster putzen #30min",
    )

    private fun index(file: File? = null) = NoteIndex(file, clock = { now })

    @Test
    fun unchangedNoteIsNotReadAgainAndParsesTheSame() {
        val index = index()
        var reads = 0
        val first = index.lines("a", old) { reads++; note }
        val second = index.lines("a", old) { reads++; note }
        assertEquals(1, reads)
        assertEquals(note, first)
        assertEquals(note.size, second.size)
        assertEquals("", second[0])
        assertEquals(MarkdownParser.parse(note).map { it.text to it.lineIndex }, MarkdownParser.parse(second).map { it.text to it.lineIndex })
        assertEquals(MarkdownParser.parse(note).map { it.isDone }, MarkdownParser.parse(second).map { it.isDone })
    }

    @Test
    fun changedStampFreshForgetAndRecentWritesReadAgain() {
        val index = index()
        var reads = 0
        index.lines("a", old) { reads++; note }
        index.lines("a", old.copy(size = 121)) { reads++; note }
        assertEquals(2, reads)
        index.lines("a", old.copy(size = 121), fresh = true) { reads++; note }
        assertEquals(3, reads)
        index.forget("a")
        index.lines("a", old.copy(size = 121)) { reads++; note }
        assertEquals(4, reads)

        // Written a moment ago: a second change could keep size and time, so it is not kept.
        val recent = FileStamp(lastModified = now - 500, size = 120)
        index.lines("b", recent) { reads++; note }
        index.lines("b", recent) { reads++; note }
        assertEquals(6, reads)

        // No time from the provider: always read.
        val unknown = FileStamp(lastModified = 0, size = 120)
        index.lines("c", unknown) { reads++; note }
        index.lines("c", unknown) { reads++; note }
        index.lines("d", null) { reads++; note }
        assertEquals(9, reads)
    }

    @Test
    fun indexSurvivesARestartAndDropsRemovedNotes() {
        val dir = Files.createTempDirectory("note-index").toFile()
        val file = File(dir, "index.bin")
        val first = index(file)
        first.lines("a", old) { note }
        first.lines("b", old) { listOf("- [ ] Ümläut ✅ 2026-11-20") }
        first.retainOnly(setOf("a"))
        first.save()

        val second = index(file)
        assertEquals(1, second.size())
        var reads = 0
        val lines = second.lines("a", old) { reads++; note }
        assertEquals(0, reads)
        assertEquals(note[2], lines[2])
        dir.deleteRecursively()
    }

    @Test
    fun brokenIndexFileIsRebuilt() {
        val dir = Files.createTempDirectory("note-index").toFile()
        val file = File(dir, "index.bin").apply { writeBytes(byteArrayOf(0, 0, 0, 1, 0x7f, 1, 2)) }
        val index = index(file)
        var reads = 0
        index.lines("a", old) { reads++; note }
        assertEquals(1, reads)
        dir.deleteRecursively()
    }
}
