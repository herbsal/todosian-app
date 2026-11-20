package com.isotjs.todosian.utils

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** A date and optional time found in a task, with the task [text] without them. */
data class DateMatch(val text: String, val date: LocalDate, val time: LocalTime?) {
    val dateTime: LocalDateTime? get() = time?.let { date.atTime(it) }
}

/**
 * Finds dates and times written in words, e.g. dictated with the keyboard microphone:
 * "morgen 17 Uhr Käse kaufen", "Freitag um 9:30 Zahnarzt", "in 2 Stunden Wäsche", "tomorrow 5 pm".
 * German and English. Only the first date and the first time are used.
 */
object NaturalDates {
    private const val B = """(?<![\p{L}\d])"""
    private const val E = """(?![\p{L}\d])"""
    private val options = setOf(RegexOption.IGNORE_CASE)

    private val weekdays = mapOf(
        "montag" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY, "mittwoch" to DayOfWeek.WEDNESDAY,
        "donnerstag" to DayOfWeek.THURSDAY, "freitag" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY,
        "sonnabend" to DayOfWeek.SATURDAY, "sonntag" to DayOfWeek.SUNDAY,
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )
    private val dayParts = mapOf(
        "früh" to LocalTime.of(8, 0), "morgens" to LocalTime.of(8, 0), "vormittag" to LocalTime.of(10, 0),
        "vormittags" to LocalTime.of(10, 0), "mittag" to LocalTime.of(12, 0), "mittags" to LocalTime.of(12, 0),
        "nachmittag" to LocalTime.of(15, 0), "nachmittags" to LocalTime.of(15, 0), "abend" to LocalTime.of(19, 0),
        "abends" to LocalTime.of(19, 0), "morning" to LocalTime.of(8, 0), "afternoon" to LocalTime.of(15, 0),
        "evening" to LocalTime.of(19, 0), "night" to LocalTime.of(21, 0),
    )
    private val dayPartPattern = dayParts.keys.sortedByDescending { it.length }.joinToString("|")
    private val weekdayPattern = weekdays.keys.joinToString("|")

    private val relativeTime = Regex("""${B}in\s+(\d{1,3})\s+(minuten|minute|min|stunden|stunde|std|minutes|hours|hour)$E""", options)
    private val relativeDays = Regex("""${B}in\s+(\d{1,3}|einer|einem|a|one)\s+(tagen|tag|wochen|woche|days|day|weeks|week)$E""", options)
    private val isoDate = Regex("""${B}(?:am\s+|on\s+)?(\d{4})-(\d{2})-(\d{2})$E""", options)
    private val germanDate = Regex("""${B}(?:am\s+|on\s+)?(\d{1,2})\.(\d{1,2})\.(\d{2,4})?(?![\p{L}\d.])""", options)
    private val namedDay = Regex(
        """${B}(?:(?:am|on|this|next|nächsten|nächste|kommenden|kommender)\s+)?(übermorgen|day after tomorrow|heute|today|tonight|morgen|tomorrow|$weekdayPattern)""" +
            """(?:\s+(?:in the\s+)?($dayPartPattern))?$E""",
        options,
    )
    private val nextWeek = Regex("""${B}(nächste woche|nächster woche|next week)$E""", options)
    private val clockTime = Regex("""${B}(?:um\s+|at\s+|gegen\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*uhr$E""", options)
    private val clockTimeAmPm = Regex("""${B}(?:at\s+|um\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)(?![\p{L}\d])""", options)
    private val clockTimeColon = Regex("""${B}(?:um\s+|at\s+|gegen\s+)?(\d{1,2}):(\d{2})$E""", options)
    private val clockTimeUm = Regex("""${B}(?:um|at)\s+(\d{1,2})$E""", options)

    fun find(input: String, now: LocalDateTime): DateMatch? {
        val removed = mutableListOf<IntRange>()
        fun take(match: MatchResult) { removed += match.range }
        fun free(match: MatchResult) = removed.none { it.first <= match.range.last && match.range.first <= it.last }
        fun firstFree(regex: Regex) = regex.findAll(input).firstOrNull { free(it) }

        var date: LocalDate? = null
        var time: LocalTime? = null

        firstFree(relativeTime)?.let { m ->
            val amount = m.groupValues[1].toLong()
            val unit = m.groupValues[2].lowercase()
            val at = if (unit.startsWith("min")) now.plusMinutes(amount) else now.plusHours(amount)
            date = at.toLocalDate()
            time = at.toLocalTime().withSecond(0).withNano(0)
            take(m)
        }

        if (date == null) {
            firstFree(isoDate)?.let { m ->
                runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
                    .getOrNull()?.let { date = it; take(m) }
            }
        }
        if (date == null) {
            firstFree(germanDate)?.let { m ->
                val day = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                val yearText = m.groupValues[3]
                val parsed = runCatching {
                    if (yearText.isNotEmpty()) {
                        val year = yearText.toInt().let { if (it < 100) 2000 + it else it }
                        LocalDate.of(year, month, day)
                    } else {
                        LocalDate.of(now.year, month, day).let { if (it.isBefore(now.toLocalDate())) it.plusYears(1) else it }
                    }
                }.getOrNull()
                if (parsed != null) { date = parsed; take(m) }
            }
        }
        if (date == null) {
            firstFree(namedDay)?.let { m ->
                val word = m.groupValues[1].lowercase()
                // "am Morgen" or "Guten Morgen" mean the morning, not tomorrow.
                val before = input.substring(0, m.range.first).trimEnd().lowercase()
                val isNoun = (word == "morgen") && m.value.first().isUpperCase() && m.range.first > 0 &&
                    (before.endsWith(" am") || before == "am" || before.endsWith("guten"))
                if (!isNoun) {
                    val today = now.toLocalDate()
                    date = when (word) {
                        "heute", "today", "tonight" -> today
                        "morgen", "tomorrow" -> today.plusDays(1)
                        "übermorgen", "day after tomorrow" -> today.plusDays(2)
                        else -> today.with(TemporalAdjusters.next(weekdays.getValue(word)))
                    }
                    val part = m.groupValues[2].lowercase()
                    if (part.isNotEmpty()) time = dayParts[part]
                    if (word == "tonight" && time == null) time = LocalTime.of(19, 0)
                    take(m)
                }
            }
        }
        if (date == null) {
            firstFree(relativeDays)?.let { m ->
                val raw = m.groupValues[1].lowercase()
                val amount = raw.toLongOrNull() ?: 1L
                val unit = m.groupValues[2].lowercase()
                date = if (unit.startsWith("tag") || unit.startsWith("day")) now.toLocalDate().plusDays(amount)
                else now.toLocalDate().plusWeeks(amount)
                take(m)
            }
        }
        if (date == null) {
            firstFree(nextWeek)?.let { m ->
                date = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                take(m)
            }
        }

        if (time == null) {
            for (regex in listOf(clockTime, clockTimeAmPm, clockTimeColon, clockTimeUm)) {
                val m = firstFree(regex) ?: continue
                var hour = m.groupValues[1].toInt()
                val minute = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
                if (regex == clockTimeAmPm) {
                    val pm = m.groupValues[3].lowercase().startsWith("p")
                    if (hour == 12) hour = 0
                    if (pm) hour += 12
                }
                if (hour in 0..23 && minute in 0..59) {
                    time = LocalTime.of(hour, minute)
                    take(m)
                    break
                }
            }
        }

        if (date == null && time == null) return null
        val resolvedDate = date ?: if (time!!.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)

        val builder = StringBuilder()
        var last = 0
        removed.sortedBy { it.first }.forEach { range ->
            builder.append(input, last, range.first).append(' ')
            last = range.last + 1
        }
        builder.append(input.substring(last))
        val text = builder.toString()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s+([,.;:!?])"""), "$1")
            .trim()
            .trim(',', ';', ':', '-', ' ')
        if (text.isEmpty()) return null
        return DateMatch(text, resolvedDate, time)
    }
}
