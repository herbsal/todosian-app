package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

class RecurrenceRuleTest {

    private fun roundTrip(text: String) = assertEquals(text, RecurrenceRule.parse(text)?.text)

    @Test
    fun `rules the picker shows read back the same`() {
        listOf(
            "every day",
            "every 3 days",
            "every weekday",
            "every week",
            "every 2 weeks",
            "every week on Monday",
            "every 2 weeks on Monday, Friday",
            "every month",
            "every month on the 24th",
            "every 3 months on the 1st",
            "every month on the 2nd Wednesday",
            "every month on the last",
            "every month on the last Friday",
            "every year",
            "every 2 years",
            "every November on the 24th",
            "every February on the last",
            "every week on Monday when done",
        ).forEach(::roundTrip)
    }

    @Test
    fun `written rules are normalized`() {
        assertEquals("every day", RecurrenceRule.parse("Every 1 day")?.text)
        assertEquals("every week on Monday, Friday", RecurrenceRule.parse("every week on fri, mon")?.text)
    }

    @Test
    fun `rules the picker can't show stay text`() {
        assertNull(RecurrenceRule.parse("every weekend"))
        assertNull(RecurrenceRule.parse("every April and December on the 1st and 24th"))
        assertNull(RecurrenceRule.parse("every week on Funday"))
        assertNull(RecurrenceRule.parse(""))
    }

    @Test
    fun `parsed parts`() {
        val rule = RecurrenceRule.parse("every 2 months on the 4th Monday when done")
        assertEquals(RecurrenceFrequency.MONTHLY, rule?.frequency)
        assertEquals(2, rule?.interval)
        assertEquals(MonthlyDay.NthWeekday(4, DayOfWeek.MONDAY), rule?.monthly)
        assertEquals(true, rule?.whenDone)
    }

    @Test
    fun `new rules start from the task's date`() {
        val start = LocalDate.of(2026, 11, 24)
        assertEquals("every week on Tuesday", RecurrenceRule.startingFrom(RecurrenceFrequency.WEEKLY, start).text)
        assertEquals("every month on the 24th", RecurrenceRule.startingFrom(RecurrenceFrequency.MONTHLY, start).text)
        assertEquals("every November on the 24th", RecurrenceRule.startingFrom(RecurrenceFrequency.YEARLY, start).text)
        assertEquals(4, RecurrenceRule.weekOfMonth(start))
        assertEquals(true, RecurrenceRule.isLastWeekOfMonth(start))
        assertEquals(Month.NOVEMBER, RecurrenceRule.startingFrom(RecurrenceFrequency.YEARLY, start).yearMonth)
    }

    @Test
    fun `ordinals`() {
        assertEquals("1st", RecurrenceRule.ordinal(1))
        assertEquals("2nd", RecurrenceRule.ordinal(2))
        assertEquals("3rd", RecurrenceRule.ordinal(3))
        assertEquals("11th", RecurrenceRule.ordinal(11))
        assertEquals("22nd", RecurrenceRule.ordinal(22))
    }
}
