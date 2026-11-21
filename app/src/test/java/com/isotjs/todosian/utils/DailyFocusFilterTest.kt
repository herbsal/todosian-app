package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.DailyFocusMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyFocusFilterTest {
    private fun todo(due: String?) = Todo(id = "", text = "x", isDone = false, lineIndex = 0, dueDate = due)

    @Test
    fun modesPickTheirDueDates() {
        val today = "2026-11-21"
        val tasks = listOf(todo("2026-11-20"), todo(today), todo("2026-11-22"), todo(null))
        fun pick(mode: DailyFocusMode) = tasks.filter { DailyFocusFilter.matches(it, mode, today) }.map { it.dueDate }
        assertEquals(listOf(today), pick(DailyFocusMode.TODAY))
        assertEquals(listOf("2026-11-20"), pick(DailyFocusMode.OVERDUE))
        assertEquals(listOf("2026-11-20", today), pick(DailyFocusMode.TODAY_AND_OVERDUE))
    }
}
