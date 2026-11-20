package com.isotjs.todosian.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WriteJournalTest {
    private val old = "- [ ] Milch\n- [ ] Brot\n".toByteArray()
    private val new = "- [x] Milch\n- [ ] Brot\n- [ ] Käse\n".toByteArray()
    private val entry = WriteJournal.Entry("content://note", old, new)

    @Test
    fun oldOrNewContentNeedsNothing() {
        assertEquals(WriteJournal.Recovery.NOTHING, WriteJournal.recoveryFor(old, entry))
        assertEquals(WriteJournal.Recovery.NOTHING, WriteJournal.recoveryFor(new, entry))
    }

    @Test
    fun cutOffWriteIsCompleted() {
        assertEquals(WriteJournal.Recovery.COMPLETE, WriteJournal.recoveryFor(new.copyOf(10), entry))
        assertEquals(WriteJournal.Recovery.COMPLETE, WriteJournal.recoveryFor(ByteArray(0), entry))
    }

    @Test
    fun foreignContentIsLeftAlone() {
        assertEquals(WriteJournal.Recovery.NOTHING, WriteJournal.recoveryFor("- [ ] Anderes\n".toByteArray(), entry))
        assertEquals(WriteJournal.Recovery.NOTHING, WriteJournal.recoveryFor(null, entry))
    }

    @Test
    fun entriesSurviveUntilFinished() {
        val dir = Files.createTempDirectory("journal").toFile()
        val journal = WriteJournal(dir)
        journal.begin("content://a", old, new)
        val pending = journal.pending().single()
        assertEquals("content://a", pending.uri)
        assertTrue(pending.old.contentEquals(old))
        assertTrue(pending.new.contentEquals(new))
        journal.finish("content://a")
        assertTrue(journal.pending().isEmpty())
        dir.deleteRecursively()
    }
}
