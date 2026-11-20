package com.isotjs.todosian.utils

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** How a date appears in a task row: as a word for nearby days, else as a short date. */
enum class RelativeDay { YESTERDAY, TODAY, TOMORROW, OTHER }

/** How urgent a date is, which picks its colour in the row. */
enum class DateUrgency { OVERDUE, TODAY, SOON, LATER }

object TodoDates {
    private val reminderFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Days up to this many days ahead count as [DateUrgency.SOON]. */
    const val SOON_DAYS = 2L

    fun parseDate(value: String?): LocalDate? = value?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun parseReminder(value: String?): LocalDateTime? =
        value?.trim()?.let { runCatching { LocalDateTime.parse(it, reminderFormat) }.getOrNull() }

    fun relativeDay(date: LocalDate, today: LocalDate): RelativeDay = when (date) {
        today -> RelativeDay.TODAY
        today.plusDays(1) -> RelativeDay.TOMORROW
        today.minusDays(1) -> RelativeDay.YESTERDAY
        else -> RelativeDay.OTHER
    }

    fun urgency(date: LocalDate, today: LocalDate): DateUrgency = when {
        date.isBefore(today) -> DateUrgency.OVERDUE
        date == today -> DateUrgency.TODAY
        !date.isAfter(today.plusDays(SOON_DAYS)) -> DateUrgency.SOON
        else -> DateUrgency.LATER
    }
}
