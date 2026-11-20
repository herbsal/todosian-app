package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.TasksPriority
import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.TodoSort
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TodoSortingTest {
    private val defs = TagDefinitionParser.parse(
        listOf("## Aufwand", "- 5 Min: #5min", "- 30 Min: #30min", "- 2 Std: #2h", "## Person", "- Alex: #alex"),
    )

    private fun todo(line: Int, text: String, indent: Int = 0, due: String? = null, reminder: String? = null, prio: TasksPriority? = null) =
        Todo(id = "$line", text = text, isDone = false, lineIndex = line, indentLevel = indent, dueDate = due, reminder = reminder, priority = prio)

    private fun texts(list: List<Todo>) = list.map { it.text }

    @Test
    fun `file order sorts by line`() {
        val list = listOf(todo(2, "b"), todo(0, "a"))
        assertEquals(listOf("a", "b"), texts(TodoSorting.sort(list, TodoSort.FILE_ORDER)))
    }

    @Test
    fun `tag group follows the keyword note and keeps subtasks`() {
        val list = listOf(
            todo(0, "lang #2h"),
            todo(1, "schritt", indent = 1),
            todo(2, "ohne"),
            todo(3, "kurz #5min"),
            todo(4, "mittel #30min"),
        )
        assertEquals(
            listOf("kurz #5min", "mittel #30min", "lang #2h", "schritt", "ohne"),
            texts(TodoSorting.sort(list, TodoSort.TAG_GROUP, "aufwand", defs)),
        )
    }

    @Test
    fun `unknown group keeps file order`() {
        val list = listOf(todo(1, "b #5min"), todo(0, "a #2h"))
        assertEquals(listOf("a #2h", "b #5min"), texts(TodoSorting.sort(list, TodoSort.TAG_GROUP, "Energie", defs)))
    }

    @Test
    fun `reminder and due put missing values last`() {
        val list = listOf(todo(0, "none"), todo(1, "late", reminder = "2026-11-21 08:00", due = "2026-11-30"), todo(2, "early", reminder = "2026-11-20 10:45", due = "2026-11-21"))
        assertEquals(listOf("early", "late", "none"), texts(TodoSorting.sort(list, TodoSort.REMINDER_EARLIEST_FIRST)))
        assertEquals(listOf("early", "late", "none"), texts(TodoSorting.sort(list, TodoSort.DUE_DATE_EARLIEST_FIRST)))
    }

    @Test
    fun `title sorts alphabetically with umlauts`() {
        val list = listOf(todo(0, "Zahnarzt"), todo(1, "Ärztin anrufen"), todo(2, "bank"))
        assertEquals(listOf("Ärztin anrufen", "bank", "Zahnarzt"), texts(TodoSorting.sort(list, TodoSort.TITLE_A_Z, locale = Locale.GERMAN)))
    }

    @Test
    fun `priority is stable for ties`() {
        val list = listOf(todo(0, "a"), todo(1, "b", prio = TasksPriority.HIGH), todo(2, "c"))
        assertEquals(listOf("b", "a", "c"), texts(TodoSorting.sort(list, TodoSort.PRIORITY_HIGH_TO_LOW)))
    }
}
