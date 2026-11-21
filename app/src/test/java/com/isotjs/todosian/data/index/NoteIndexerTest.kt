package com.isotjs.todosian.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteIndexerTest {
    @Test
    fun splitsLinesLikeReadLines() {
        assertEquals(listOf("a", "b", "", "c"), NoteIndexer.splitLines("a\r\nb\n\nc\n"))
        assertEquals(listOf("a", "b"), NoteIndexer.splitLines("a\rb"))
        assertEquals(emptyList<String>(), NoteIndexer.splitLines(""))
        assertEquals(listOf(""), NoteIndexer.splitLines("\n"))
    }

    @Test
    fun keepsTaskLinesWithTheirNumbersAndHashesEveryLine() {
        val content = NoteIndexer.read("# Haushalt\n- [ ] Müll 📅 2026-11-21\nText\n  - [x] Tüten\n".toByteArray())
        assertEquals(listOf(TaskLine(1, "- [ ] Müll 📅 2026-11-21"), TaskLine(3, "  - [x] Tüten")), content.taskLines)
        assertEquals(4, content.lineHashes.size)
        assertEquals(64, content.hash.length)
        assertNotEquals(content.lineHashes[0], content.lineHashes[2])
        assertEquals(NoteIndexer.lineHash("Text"), content.lineHashes[2])
    }

    @Test
    fun vaultIndexStoresReplacesAndRebuilds() {
        val store = InMemoryIndexStore()
        val index = VaultIndex(store)
        val content = NoteIndexer.read("- [ ] A\nfrei\n- [x] B".toByteArray())
        val note = index.put(
            IndexedNote(
                folder = "tree", docId = "d1", uri = "u1", relPath = "Inbox", mtime = 1, size = 20,
                hash = content.hash, lineCount = content.lines.size, state = NoteState.OK, indexedAt = 5,
                lineHashes = content.lineHashes, taskLines = content.taskLines,
            ),
        )
        assertEquals(listOf("- [ ] A", "", "- [x] B"), index.note("u1")!!.sparseLines())

        // Reloaded from the store, as after a restart.
        val reloaded = VaultIndex(store)
        assertEquals(note, reloaded.note("u1"))

        // A rename keeps the id and frees the old URI.
        reloaded.put(note.copy(uri = "u2", relPath = "Eingang"))
        assertNull(reloaded.note("u1"))
        assertEquals(note.id, reloaded.note("u2")!!.id)

        reloaded.rebuild()
        assertEquals(0, VaultIndex(store).notes().size)
    }
}
