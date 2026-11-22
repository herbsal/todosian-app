package com.isotjs.todosian.utils

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/** How often a task repeats in the picker; [RecurrenceRule.text] is the Obsidian Tasks text. */
enum class RecurrenceFrequency { DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY }

/** Which day of the month a monthly rule falls on. */
sealed interface MonthlyDay {
    /** The same day as the date it starts from: "every month". */
    data object SameDay : MonthlyDay
    data class Day(val day: Int) : MonthlyDay
    data class NthWeekday(val nth: Int, val dayOfWeek: DayOfWeek) : MonthlyDay
    data object LastDay : MonthlyDay
    data class LastWeekday(val dayOfWeek: DayOfWeek) : MonthlyDay
}

/**
 * A recurrence the picker can show, read from and written as Obsidian Tasks text, e.g.
 * "every 2 weeks on Monday, Friday when done". Rules the picker can't show ([parse] gives null)
 * stay as they are and are edited as text.
 */
data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthly: MonthlyDay = MonthlyDay.SameDay,
    /** For yearly rules every year: the month and day ([yearDay] null = the last day). */
    val yearMonth: Month? = null,
    val yearDay: Int? = null,
    val whenDone: Boolean = false,
) {
    val text: String
        get() {
            val n = interval.coerceAtLeast(1)
            val base = when (frequency) {
                RecurrenceFrequency.DAILY -> if (n == 1) "every day" else "every $n days"
                RecurrenceFrequency.WEEKDAYS -> "every weekday"
                RecurrenceFrequency.WEEKLY -> {
                    val every = if (n == 1) "every week" else "every $n weeks"
                    if (weekdays.isEmpty()) every else "$every on " + weekdays.sorted().joinToString(", ") { dayName(it) }
                }
                RecurrenceFrequency.MONTHLY -> {
                    val every = if (n == 1) "every month" else "every $n months"
                    when (val m = monthly) {
                        MonthlyDay.SameDay -> every
                        is MonthlyDay.Day -> "$every on the ${ordinal(m.day)}"
                        is MonthlyDay.NthWeekday -> "$every on the ${ordinal(m.nth)} ${dayName(m.dayOfWeek)}"
                        MonthlyDay.LastDay -> "$every on the last"
                        is MonthlyDay.LastWeekday -> "$every on the last ${dayName(m.dayOfWeek)}"
                    }
                }
                RecurrenceFrequency.YEARLY -> {
                    val month = yearMonth
                    when {
                        month != null && n == 1 -> "every ${monthName(month)} on the " + (yearDay?.let { ordinal(it) } ?: "last")
                        n == 1 -> "every year"
                        else -> "every $n years"
                    }
                }
            }
            return if (whenDone) "$base when done" else base
        }

    companion object {
        private val WHEN_DONE = Regex("""\s+when\s+done\s*$""", RegexOption.IGNORE_CASE)
        private val DAYS = Regex("""^every\s+(?:(\d+)\s+)?days?$""", RegexOption.IGNORE_CASE)
        private val WEEKDAY = Regex("""^every\s+weekday$""", RegexOption.IGNORE_CASE)
        private val WEEKS = Regex("""^every\s+(?:(\d+)\s+)?weeks?(?:\s+on\s+(.+))?$""", RegexOption.IGNORE_CASE)
        private val MONTHS = Regex("""^every\s+(?:(\d+)\s+)?months?(?:\s+on\s+the\s+(.+))?$""", RegexOption.IGNORE_CASE)
        private val YEARS = Regex("""^every\s+(?:(\d+)\s+)?years?$""", RegexOption.IGNORE_CASE)
        private val NAMED_MONTH = Regex("""^every\s+(\w+)\s+on\s+the\s+(?:(\d+)(?:st|nd|rd|th)|(last))$""", RegexOption.IGNORE_CASE)
        private val NTH = Regex("""^(\d+)(?:st|nd|rd|th)$""", RegexOption.IGNORE_CASE)
        private val NTH_WEEKDAY = Regex("""^([1-4])(?:st|nd|rd|th)\s+(\w+)$""", RegexOption.IGNORE_CASE)
        private val LAST = Regex("""^last(?:\s+(\w+))?$""", RegexOption.IGNORE_CASE)

        private val dayNames: Map<String, DayOfWeek> = DayOfWeek.entries.flatMap { day ->
            val name = day.name.lowercase()
            listOf(name to day, name.take(3) to day)
        }.toMap()
        private val monthNames: Map<String, Month> = Month.entries.flatMap { month ->
            val name = month.name.lowercase()
            listOf(name to month, name.take(3) to month)
        }.toMap()

        /** The picker's rule for [text], or null when the picker can't show it. */
        fun parse(text: String?): RecurrenceRule? {
            val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val whenDone = WHEN_DONE.containsMatchIn(raw)
            val rule = WHEN_DONE.replace(raw, "").trim()
            fun count(value: String): Int? = if (value.isEmpty()) 1 else value.toIntOrNull()?.takeIf { it >= 1 }

            DAYS.matchEntire(rule)?.let { m ->
                return RecurrenceRule(RecurrenceFrequency.DAILY, count(m.groupValues[1]) ?: return null, whenDone = whenDone)
            }
            if (WEEKDAY.matches(rule)) return RecurrenceRule(RecurrenceFrequency.WEEKDAYS, whenDone = whenDone)
            WEEKS.matchEntire(rule)?.let { m ->
                val days = m.groupValues[2].takeIf { it.isNotBlank() }?.split(",")?.map { name ->
                    dayNames[name.trim().lowercase()] ?: return null
                }?.toSet() ?: emptySet()
                return RecurrenceRule(RecurrenceFrequency.WEEKLY, count(m.groupValues[1]) ?: return null, weekdays = days, whenDone = whenDone)
            }
            MONTHS.matchEntire(rule)?.let { m ->
                val on = m.groupValues[2].trim()
                val monthly = when {
                    on.isEmpty() -> MonthlyDay.SameDay
                    NTH.matches(on) -> MonthlyDay.Day(NTH.matchEntire(on)!!.groupValues[1].toInt().takeIf { it in 1..31 } ?: return null)
                    NTH_WEEKDAY.matches(on) -> NTH_WEEKDAY.matchEntire(on)!!.let { w ->
                        MonthlyDay.NthWeekday(w.groupValues[1].toInt(), dayNames[w.groupValues[2].lowercase()] ?: return null)
                    }
                    LAST.matches(on) -> {
                        val day = LAST.matchEntire(on)!!.groupValues[1].lowercase()
                        if (day.isEmpty() || day == "day") MonthlyDay.LastDay else MonthlyDay.LastWeekday(dayNames[day] ?: return null)
                    }
                    else -> return null
                }
                return RecurrenceRule(RecurrenceFrequency.MONTHLY, count(m.groupValues[1]) ?: return null, monthly = monthly, whenDone = whenDone)
            }
            YEARS.matchEntire(rule)?.let { m ->
                return RecurrenceRule(RecurrenceFrequency.YEARLY, count(m.groupValues[1]) ?: return null, whenDone = whenDone)
            }
            NAMED_MONTH.matchEntire(rule)?.let { m ->
                val month = monthNames[m.groupValues[1].lowercase()] ?: return null
                val day = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..31 }
                if (day == null && m.groupValues[3].isEmpty()) return null
                return RecurrenceRule(RecurrenceFrequency.YEARLY, yearMonth = month, yearDay = day, whenDone = whenDone)
            }
            return null
        }

        /** A new rule of [frequency], set up from [start] (the task's date): its weekday, its day. */
        fun startingFrom(frequency: RecurrenceFrequency, start: LocalDate): RecurrenceRule = when (frequency) {
            RecurrenceFrequency.WEEKLY -> RecurrenceRule(frequency, weekdays = setOf(start.dayOfWeek))
            RecurrenceFrequency.MONTHLY -> RecurrenceRule(frequency, monthly = MonthlyDay.Day(start.dayOfMonth))
            RecurrenceFrequency.YEARLY -> RecurrenceRule(frequency, yearMonth = start.month, yearDay = start.dayOfMonth)
            else -> RecurrenceRule(frequency)
        }

        /** Which week of its month [date] is in, 1 to 4; null in the fifth week (then "last" fits). */
        fun weekOfMonth(date: LocalDate): Int? = ((date.dayOfMonth - 1) / 7 + 1).takeIf { it <= 4 }

        fun isLastWeekOfMonth(date: LocalDate): Boolean = date.plusWeeks(1).month != date.month

        private fun dayName(day: DayOfWeek): String = day.name.lowercase().replaceFirstChar { it.uppercase() }
        private fun monthName(month: Month): String = month.name.lowercase().replaceFirstChar { it.uppercase() }

        internal fun ordinal(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
            return "$n$suffix"
        }
    }
}
