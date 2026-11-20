package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.Todo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class CalendarAgendaTest {
    private val today = LocalDate.of(2026, 11, 20) // Friday

    private fun todo(text: String, due: String? = null, reminder: String? = null, scheduled: String? = null) =
        Todo(id = text, text = text, isDone = false, lineIndex = 0, dueDate = due, reminder = reminder, scheduledDate = scheduled)

    @Test
    fun dateComesFromDueThenReminderThenScheduled() {
        assertEquals(LocalDate.of(2026, 11, 21), CalendarAgenda.dateOf(todo("a", due = "2026-11-21", reminder = "2026-11-25 09:00")))
        assertEquals(LocalDate.of(2026, 11, 25), CalendarAgenda.dateOf(todo("b", reminder = "2026-11-25 09:00")))
        assertEquals(LocalDate.of(2026, 11, 30), CalendarAgenda.dateOf(todo("c", scheduled = "2026-11-30")))
        assertNull(CalendarAgenda.dateOf(todo("d")))
    }

    @Test
    fun agendaBuckets() {
        val items = listOf(
            todo("later", due = "2026-12-01"),
            todo("overdue", due = "2026-11-18"),
            todo("today", reminder = "2026-11-20 17:00"),
            todo("tomorrow", due = "2026-11-21"),
            todo("sunday", due = "2026-11-22"),
            todo("none"),
        )
        val agenda = CalendarAgenda.agenda(items, today) { it }
        assertEquals(
            listOf(AgendaBucket.OVERDUE, AgendaBucket.TODAY, AgendaBucket.TOMORROW, AgendaBucket.THIS_WEEK, AgendaBucket.LATER),
            agenda.keys.toList(),
        )
        assertEquals(listOf("sunday"), agenda.getValue(AgendaBucket.THIS_WEEK).map { it.text })
        assertEquals(5, agenda.values.sumOf { it.size })
    }

    @Test
    fun monthGridStartsOnMonday() {
        val grid = CalendarAgenda.monthGrid(today)
        assertEquals(42, grid.size)
        assertEquals(DayOfWeek.MONDAY, grid.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 10, 26), grid.first())
    }
}
