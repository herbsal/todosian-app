package com.isotjs.todosian.data.index

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A synthetic vault in memory: path to (content, mtime). */
private class FakeVault(var now: Long) {
    val files = LinkedHashMap<String, Pair<String, Long>>()
    val unreadable = HashSet<String>()
    var reads = 0

    fun write(path: String, text: String, at: Long = now) {
        files[path] = text to at
    }

    fun listing(): List<ListedNote> = files.map { (path, file) ->
        ListedNote(docId = "doc:$path", uri = "uri:$path", relPath = path, mtime = file.second, size = file.first.toByteArray().size.toLong())
    }

    fun read(entry: ListedNote): ByteArray? {
        reads++
        if (entry.relPath in unreadable) return null
        return files[entry.relPath]?.first?.toByteArray()
    }
}

class ReconcilerTest {
    private val start = 1_800_000_000_000L
    private val vault = FakeVault(start)
    private val index = VaultIndex(InMemoryIndexStore())
    private val reconciler = Reconciler(index) { vault.now }

    private fun pass(): ReconcileResult = reconciler.reconcile("tree", vault.listing(), vault::read)

    private fun settle() {
        vault.now += 10_000
    }

    @Test
    fun thousandNotesAreReadOnceAndThenOnlyWhatChanged() {
        repeat(1000) { vault.write("Notes/N$it", "# N$it\n- [ ] Aufgabe $it\nText\n", at = start - 60_000) }
        val first = pass()
        assertEquals(1000, first.added.size)
        assertEquals(1000, vault.reads)
        assertEquals(1000, index.notes().count { it.state == NoteState.OK })

        vault.reads = 0
        val second = pass()
        assertEquals(0, vault.reads)
        assertFalse(second.hasChanges)

        vault.write("Notes/N5", "- [x] Aufgabe 5\n", at = start - 30_000)
        vault.write("Notes/N6", "- [ ] Neu\n- [ ] Zwei\n", at = start - 30_000)
        vault.files.remove("Notes/N7")
        vault.reads = 0
        val third = pass()
        assertEquals(2, vault.reads)
        assertEquals(listOf("uri:Notes/N5", "uri:Notes/N6"), third.changed)
        assertEquals(listOf("uri:Notes/N7"), third.removed)
        assertEquals(999, index.notes().size)
        assertEquals(listOf(TaskLine(0, "- [ ] Neu"), TaskLine(1, "- [ ] Zwei")), index.note("uri:Notes/N6")!!.taskLines)
    }

    @Test
    fun sameContentWithNewTimeOnlyUpdatesTheStamp() {
        vault.write("A", "- [ ] A\n", at = start - 60_000)
        pass()
        val before = index.note("uri:A")!!
        vault.write("A", "- [ ] A\n", at = start - 20_000)
        val result = pass()
        assertFalse(result.hasChanges)
        val after = index.note("uri:A")!!
        assertEquals(before.id, after.id)
        assertEquals(start - 20_000, after.mtime)
    }

    @Test
    fun noteChangedRightBeforeReadingIsReadAgain() {
        vault.write("A", "- [ ] A\n", at = start - 1_000)
        pass()
        assertEquals(NoteState.UNSETTLED, index.note("uri:A")!!.state)
        vault.reads = 0
        // Same time and size, but a second change in the same moment could hide behind them.
        vault.write("A", "- [ ] B\n", at = start - 1_000)
        settle()
        val result = pass()
        assertEquals(1, vault.reads)
        assertEquals(listOf("uri:A"), result.changed)
        assertEquals(NoteState.OK, index.note("uri:A")!!.state)
        assertEquals("- [ ] B", index.note("uri:A")!!.taskLines.single().raw)
    }

    @Test
    fun notesWithoutTimeAreAlwaysRead() {
        vault.write("A", "- [ ] A\n", at = 0)
        pass()
        vault.reads = 0
        pass()
        assertEquals(1, vault.reads)
        assertEquals(NoteState.UNSETTLED, index.note("uri:A")!!.state)
    }

    @Test
    fun renamedNoteKeepsItsId() {
        vault.write("Alt", "- [ ] Arzt anrufen\n", at = start - 60_000)
        pass()
        val id = index.note("uri:Alt")!!.id
        val text = vault.files.remove("Alt")!!
        vault.files["Ordner/Neu"] = text
        val result = pass()
        assertEquals(mapOf("uri:Alt" to "uri:Ordner/Neu"), result.renamed)
        assertTrue(result.removed.isEmpty())
        assertNull(index.note("uri:Alt"))
        assertEquals(id, index.note("uri:Ordner/Neu")!!.id)
        assertEquals("Ordner/Neu", index.note("uri:Ordner/Neu")!!.relPath)
    }

    @Test
    fun unreadableNoteKeepsItsTasksAndIsTriedAgain() {
        vault.write("A", "- [ ] A\n", at = start - 60_000)
        pass()
        vault.write("A", "- [ ] B\n", at = start - 30_000)
        vault.unreadable += "A"
        val result = pass()
        assertEquals(listOf("uri:A"), result.unreadable)
        assertEquals(NoteState.UNREADABLE, index.note("uri:A")!!.state)
        assertEquals("- [ ] A", index.note("uri:A")!!.taskLines.single().raw)

        vault.unreadable.clear()
        val again = pass()
        assertEquals(listOf("uri:A"), again.changed)
        assertEquals("- [ ] B", index.note("uri:A")!!.taskLines.single().raw)
    }

    @Test
    fun ownWriteIsToldApartFromForeignChanges() {
        vault.write("A", "- [ ] A\n", at = start - 60_000)
        pass()
        val written = "- [x] A ✅ 2026-11-21\n"
        index.put(index.note("uri:A")!!.copy(ownWriteHash = NoteIndexer.sha256(written.toByteArray())))
        vault.write("A", written, at = start - 30_000)
        val result = pass()
        assertEquals(listOf("uri:A"), result.ownWrites)
        assertTrue(result.changed.isEmpty())
    }

    @Test
    fun conflictCopyKeepsTheBaseUntilItIsGone() {
        vault.write("Einkauf", "- [ ] Milch\n- [ ] Brot\n", at = start - 60_000)
        pass()
        val base = index.note("uri:Einkauf")!!.lineHashes
        // Syncthing brings one side as the note and the other as a copy.
        vault.write("Einkauf", "- [x] Milch\n- [ ] Brot\n", at = start - 30_000)
        vault.write("Einkauf.sync-conflict-20261121-142233-ABCDEF7", "- [ ] Milch\n- [ ] Brot\n- [ ] Eier\n", at = start - 30_000)
        pass()
        assertArrayEquals(base, index.note("uri:Einkauf")!!.baseLineHashes)
        assertNotNull(index.note("uri:Einkauf.sync-conflict-20261121-142233-ABCDEF7"))

        // A later change keeps the base from before the conflict.
        vault.write("Einkauf", "- [x] Milch\n- [x] Brot\n", at = start - 20_000)
        pass()
        assertArrayEquals(base, index.note("uri:Einkauf")!!.baseLineHashes)

        vault.files.remove("Einkauf.sync-conflict-20261121-142233-ABCDEF7")
        pass()
        assertNull(index.note("uri:Einkauf")!!.baseLineHashes)
    }

    @Test
    fun foldersNoLongerSelectedAreDropped() {
        vault.write("A", "- [ ] A\n", at = start - 60_000)
        pass()
        reconciler.reconcile("other", listOf(ListedNote("d", "uri:other", "B", start - 60_000, 3)), { "x\n".toByteArray() })
        assertEquals(listOf("uri:A"), reconciler.retainFolders(setOf("other")))
        assertEquals(listOf("uri:other"), index.notes().map { it.uri })
    }

    @Test
    fun recognizesSyncthingConflictNames() {
        assertEquals("Ordner/Notiz", ConflictNames.originalOf("Ordner/Notiz.sync-conflict-20261121-142233-ABCDEF7"))
        assertNull(ConflictNames.originalOf("Ordner/Notiz"))
        assertNull(ConflictNames.originalOf("Notiz.sync-conflict-2026-ABC"))
    }

    @Test
    fun reportsProgress() {
        repeat(3) { vault.write("N$it", "- [ ] $it\n", at = start - 60_000) }
        val steps = ArrayList<Pair<Int, Int>>()
        reconciler.reconcile("tree", vault.listing(), vault::read) { done, total -> steps += done to total }
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), steps)
    }
}
