package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.Todo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Parts of the agenda, in the order they are shown. */
enum class AgendaBucket { OVERDUE, TODAY, TOMORROW, THIS_WEEK, LATER }

/** Dates for the calendar tab: which day a task belongs to and how the agenda groups them. */
object CalendarAgenda {
    /** The day a task shows on: due date, else the day of its ⏰, else its scheduled date. */
    fun dateOf(todo: Todo): LocalDate? =
        parse(todo.dueDate) ?: todo.reminder?.let { parse(it.take(10)) } ?: parse(todo.scheduledDate)

    fun bucketOf(date: LocalDate, today: LocalDate): AgendaBucket {
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return when {
            date < today -> AgendaBucket.OVERDUE
            date == today -> AgendaBucket.TODAY
            date == today.plusDays(1) -> AgendaBucket.TOMORROW
            date <= endOfWeek -> AgendaBucket.THIS_WEEK
            else -> AgendaBucket.LATER
        }
    }

    /** Items with a date, grouped by [AgendaBucket] and sorted by date; items without a date are left out. */
    fun <T> agenda(items: List<T>, today: LocalDate, todoOf: (T) -> Todo): Map<AgendaBucket, List<T>> =
        items.mapNotNull { item -> dateOf(todoOf(item))?.let { it to item } }
            .sortedBy { it.first }
            .groupBy({ bucketOf(it.first, today) }, { it.second })
            .toSortedMap()

    /** The 6×7 days of the month grid that contains [month]'s first day, starting on Monday. */
    fun monthGrid(month: LocalDate): List<LocalDate> {
        val first = month.withDayOfMonth(1)
        val start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until 42).map { start.plusDays(it.toLong()) }
    }

    private fun parse(raw: String?): LocalDate? = raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
