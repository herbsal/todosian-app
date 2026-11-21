package com.isotjs.todosian.utils

import com.isotjs.todosian.data.settings.TaskIdMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskIdsTest {
    private fun ids(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirst() }
    }

    @Test
    fun onlyChangedTasksWithAReminderGetAnIdByDefault() {
        val before = listOf("- [ ] Alt ⏰ 2026-11-24 19:00", "- [ ] Neu")
        val after = listOf("- [ ] Alt ⏰ 2026-11-24 19:00", "- [ ] Neu ⏰ 2026-11-25 08:00", "- [ ] Ohne")
        assertEquals(
            listOf("- [ ] Alt ⏰ 2026-11-24 19:00", "- [ ] Neu ⏰ 2026-11-25 08:00 🆔 abc123", "- [ ] Ohne"),
            TaskIds.assign(before, after, TaskIdMode.REMINDERS, ids("abc123")),
        )
    }

    @Test
    fun allModeNeverModeAndDoneTasks() {
        val after = listOf("- [ ] Eins", "- [x] Fertig", "Text")
        assertEquals(listOf("- [ ] Eins 🆔 aaaaaa", "- [x] Fertig", "Text"), TaskIds.assign(emptyList(), after, TaskIdMode.ALL, ids("aaaaaa")))
        assertEquals(after, TaskIds.assign(emptyList(), after, TaskIdMode.NEVER, ids()))
    }

    @Test
    fun existingAndBlockIdsAreKept() {
        val after = listOf("- [ ] A 🆔 x1 #tag", "- [ ] B ^block-1")
        assertEquals(after, TaskIds.assign(emptyList(), after, TaskIdMode.ALL, ids()))
    }

    @Test
    fun aDuplicateIdInTheSameNoteIsReplaced() {
        val before = listOf("- [ ] A 🆔 abc")
        val after = listOf("- [ ] A 🆔 abc", "- [ ] A2 🆔 abc")
        assertEquals(listOf("- [ ] A 🆔 abc", "- [ ] A2 🆔 abc"), after)
        assertEquals(listOf("- [ ] A 🆔 abc", "- [ ] A2 🆔 zzz"), TaskIds.assign(before, after, TaskIdMode.ALL, ids("abc", "zzz")))
        assertEquals(listOf("- [ ] A 🆔 abc", "- [ ] A2"), TaskIds.assign(before, after, TaskIdMode.REMINDERS, ids()))
    }

    @Test
    fun theIdIsHiddenKeptOnEditAndDroppedOnCopy() {
        val line = "- [ ] Zahnarzt ⏰ 2026-11-24 19:00 📅 2026-11-25 🆔 abc123"
        val todo = MarkdownParser.parse(listOf(line)).single()
        assertEquals("Zahnarzt", todo.text)
        assertEquals("abc123", todo.taskId)
        assertEquals(
            "- [ ] Zahnarzt neu ⏰ 2026-11-24 19:00 📅 2026-11-25 🆔 abc123",
            MarkdownParser.editTodoText(listOf(line), 0, "Zahnarzt neu").single(),
        )
        val edited = MarkdownParser.editTodo(listOf(line), 0, "Anders", MarkdownParser.TasksMeta(dueDate = "2026-11-26"), true)
        assertEquals("- [ ] Anders 📅 2026-11-26 🆔 abc123", edited.single())
        val copied = MarkdownParser.tryCopyTodoLine(listOf(line), 0, emptyList())!!.second.single()
        assertNull(MarkdownParser.taskIdOf(copied))
        assertEquals(0, MarkdownParser.locateOpenTodoById(listOf("x", line).drop(1), "abc123"))
        assertEquals("^b1", MarkdownParser.parse(listOf("- [ ] Mit Block ^b1")).single().taskId)
    }
}
