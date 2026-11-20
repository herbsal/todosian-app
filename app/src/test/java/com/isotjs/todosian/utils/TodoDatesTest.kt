package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TodoDatesTest {
    private val today = LocalDate.of(2026, 11, 20)

    @Test
    fun relativeDays() {
        assertEquals(RelativeDay.TODAY, TodoDates.relativeDay(today, today))
        assertEquals(RelativeDay.TOMORROW, TodoDates.relativeDay(today.plusDays(1), today))
        assertEquals(RelativeDay.YESTERDAY, TodoDates.relativeDay(today.minusDays(1), today))
        assertEquals(RelativeDay.OTHER, TodoDates.relativeDay(today.plusDays(4), today))
    }

    @Test
    fun urgency() {
        assertEquals(DateUrgency.OVERDUE, TodoDates.urgency(today.minusDays(3), today))
        assertEquals(DateUrgency.TODAY, TodoDates.urgency(today, today))
        assertEquals(DateUrgency.SOON, TodoDates.urgency(today.plusDays(2), today))
        assertEquals(DateUrgency.LATER, TodoDates.urgency(today.plusDays(3), today))
    }

    @Test
    fun parsing() {
        assertEquals(LocalDate.of(2026, 11, 24), TodoDates.parseDate("2026-11-24"))
        assertNull(TodoDates.parseDate("24.11."))
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 45), TodoDates.parseReminder("2026-11-20 10:45"))
        assertNull(TodoDates.parseReminder("morgen"))
    }
}
