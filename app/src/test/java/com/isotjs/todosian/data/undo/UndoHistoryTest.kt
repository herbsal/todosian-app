package com.isotjs.todosian.data.undo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoHistoryTest {
    private fun entry(id: Long) = UndoEntry(id, UndoKind.CHECK, "Task $id", id, listOf(FileChange("a", listOf("- [ ] x"), listOf("- [x] x"))))

    @Test
    fun aNewActionClearsRedoAndOnlyTheLastAreKept() {
        val stack = UndoStack(max = 3)
        (1L..5L).forEach { stack.push(entry(it)) }
        assertEquals(listOf(5L, 4L, 3L), stack.undo.map { it.id })
        stack.undone(stack.undo.first())
        assertEquals(listOf(4L, 3L), stack.undo.map { it.id })
        assertEquals(listOf(5L), stack.redo.map { it.id })
        stack.redone(stack.redo.first())
        assertEquals(listOf(5L, 4L, 3L), stack.undo.map { it.id })
        stack.undone(stack.undo.first())
        stack.push(entry(6))
        assertTrue(stack.redo.isEmpty())
    }

    @Test
    fun changesOfOneNoteMergeAndTaskIdsDoNotCount() {
        val merged = UndoChanges.merge(
            listOf(
                FileChange("a", listOf("1"), listOf("2")),
                FileChange("b", listOf("x"), listOf("y")),
                FileChange("a", listOf("2"), listOf("3")),
            ),
        )
        assertEquals(listOf(FileChange("a", listOf("1"), listOf("3")), FileChange("b", listOf("x"), listOf("y"))), merged)
        assertTrue(UndoChanges.same(listOf("- [ ] Müll 🆔 abc123"), listOf("- [ ] Müll")))
        assertFalse(UndoChanges.same(listOf("- [ ] Müll"), listOf("- [x] Müll")))
    }

    @Test
    fun recordUndoCollectsTheWritesOfOneAction() = runBlocking {
        val result = recordUndo(UndoKind.DELETE, "Müll") {
            UndoRecorder.current()!!.add("a", listOf("- [ ] Müll", "Rest"), listOf("Rest"))
            42
        }
        assertEquals(42, result)
        val last = UndoHistory.entries.value.first.first()
        assertEquals(UndoKind.DELETE, last.kind)
        assertEquals(listOf("Rest"), last.changes.single().after)
        // Nothing written (or only a 🆔 added): no entry.
        assertNull(UndoHistory.record(UndoKind.EDIT, "x", listOf(FileChange("a", listOf("- [ ] x"), listOf("- [ ] x 🆔 abc")))))
    }
}
