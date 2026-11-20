package com.isotjs.todosian.notifications

import com.isotjs.todosian.data.settings.ReminderRepeat
import com.isotjs.todosian.utils.TagDefinitions
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A reminder [daysBefore] days before the task's date, at [time]. */
data class ProfileRule(val daysBefore: Int, val time: LocalTime)

/** The rules for one keyword; [repeat] overrides the "Remind again" setting for these reminders. */
data class KeywordRules(val tag: String, val rules: List<ProfileRule>, val repeat: ReminderRepeat?)

/** A reminder profile: one note in `Todosian/Reminders/` (or `Todosian/Erinnerungen/`). */
data class ReminderProfile(val name: String, val keywords: List<KeywordRules>)

/**
 * Reads a reminder profile note. Each heading names a keyword, each list item below is a rule:
 *
 * ```
 * ## #energy/low
 * - 09:00                    on the task's date at 9:00
 * - 1 day before 18:00       (also: "1 Tag vorher 18:00")
 * - repeat: hourly           (hourly/daily/off, also: "erneut: stündlich/täglich/nie")
 * ```
 */
object ReminderProfileParser {
    private val headingRegex = Regex("""^\s{0,3}#{1,6}\s+(#[^\s#]+)\s*$""")
    private val itemRegex = Regex("""^\s*[-*+]\s+(.*)$""")
    private val timeRegex = Regex("""(\d{1,2})[:.](\d{2})""")
    private val beforeRegex = Regex("""(\d+)\s*(?:days?|tage?n?|d)\s*(?:before|vorher|davor)""", RegexOption.IGNORE_CASE)
    private val repeatRegex = Regex("""^(?:repeat|erneut|wiederholen)\s*:\s*(\S+)""", RegexOption.IGNORE_CASE)

    fun parse(name: String, lines: List<String>): ReminderProfile {
        val keywords = mutableListOf<KeywordRules>()
        var tag: String? = null
        var rules = mutableListOf<ProfileRule>()
        var repeat: ReminderRepeat? = null

        fun flush() {
            val current = tag
            if (current != null && (rules.isNotEmpty() || repeat != null)) {
                keywords.add(KeywordRules(current, rules.toList(), repeat))
            }
            rules = mutableListOf()
            repeat = null
        }

        for (line in lines) {
            val heading = headingRegex.matchEntire(line.trimEnd())
            if (heading != null) {
                flush()
                tag = heading.groupValues[1]
                continue
            }
            if (tag == null) continue
            val item = itemRegex.matchEntire(line)?.groupValues?.get(1)?.trim() ?: continue
            val repeatMatch = repeatRegex.find(item)
            if (repeatMatch != null) {
                repeat = parseRepeat(repeatMatch.groupValues[1]) ?: repeat
                continue
            }
            val time = timeRegex.find(item)?.let { m ->
                val hour = m.groupValues[1].toInt()
                val minute = m.groupValues[2].toInt()
                if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
            } ?: continue
            val daysBefore = beforeRegex.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            rules.add(ProfileRule(daysBefore.coerceIn(0, 365), time))
        }
        flush()
        return ReminderProfile(name, keywords)
    }

    private fun parseRepeat(value: String): ReminderRepeat? = when (value.lowercase().trimEnd('.', ',')) {
        "hourly", "stündlich", "stuendlich" -> ReminderRepeat.HOURLY
        "daily", "täglich", "taeglich" -> ReminderRepeat.DAILY
        "off", "never", "nie", "aus" -> ReminderRepeat.OFF
        else -> null
    }
}

/** What the profile planner needs to know about an open todo. */
data class ProfileTodo(
    val text: String,
    val lineIndex: Int,
    val tags: List<String>,
    val date: LocalDate?,
    val hasOwnReminder: Boolean,
)

/** Turns a profile into reminders for the todos of one note. Free of Android types for tests. */
object ProfileReminderPlanner {
    fun candidates(
        fileKey: String,
        todos: List<ProfileTodo>,
        profile: ReminderProfile,
        definitions: TagDefinitions,
    ): List<ReminderCandidate> = todos.flatMap { todo ->
        // A ⏰ written on the task wins over the profile.
        if (todo.hasOwnReminder) return@flatMap emptyList()
        val date = todo.date ?: return@flatMap emptyList()
        profile.keywords
            .filter { keyword -> todo.tags.any { tag -> sameKeyword(tag, keyword.tag, definitions) } }
            .flatMap { keyword ->
                keyword.rules.map { rule ->
                    ReminderCandidate(
                        fileKey = fileKey,
                        text = todo.text,
                        reminder = LocalDateTime.of(date.minusDays(rule.daysBefore.toLong()), rule.time),
                        lineIndex = todo.lineIndex,
                        fromProfile = true,
                        repeat = keyword.repeat,
                    )
                }
            }
    }

    private fun sameKeyword(tag: String, ruleTag: String, definitions: TagDefinitions): Boolean {
        if (tag.equals(ruleTag, ignoreCase = true)) return true
        val term = definitions.termFor(ruleTag) ?: return false
        return term.matches(tag)
    }
}

/** Writes a reminder profile note in German or English wording; the parser reads both. */
object ReminderProfileWriter {
    fun write(profile: ReminderProfile, german: Boolean): List<String> = buildList {
        add("# ${profile.name}")
        profile.keywords.forEach { keyword ->
            add("")
            add("## ${keyword.tag}")
            keyword.rules.sortedWith(compareByDescending<ProfileRule> { it.daysBefore }.thenBy { it.time }).forEach { rule ->
                add("- " + describe(rule, german))
            }
            keyword.repeat?.let { add("- " + repeatLine(it, german)) }
        }
    }

    fun describe(rule: ProfileRule, german: Boolean): String {
        val time = "%02d:%02d".format(rule.time.hour, rule.time.minute)
        return when {
            rule.daysBefore == 0 -> time
            german -> "${rule.daysBefore} ${if (rule.daysBefore == 1) "Tag" else "Tage"} vorher $time"
            else -> "${rule.daysBefore} ${if (rule.daysBefore == 1) "day" else "days"} before $time"
        }
    }

    private fun repeatLine(repeat: ReminderRepeat, german: Boolean): String = if (german) {
        "erneut: " + when (repeat) {
            ReminderRepeat.OFF -> "nie"
            ReminderRepeat.HOURLY -> "stündlich"
            ReminderRepeat.DAILY -> "täglich"
        }
    } else {
        "repeat: " + when (repeat) {
            ReminderRepeat.OFF -> "off"
            ReminderRepeat.HOURLY -> "hourly"
            ReminderRepeat.DAILY -> "daily"
        }
    }
}
