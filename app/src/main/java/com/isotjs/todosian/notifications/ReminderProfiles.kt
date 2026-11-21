package com.isotjs.todosian.notifications

import com.isotjs.todosian.data.settings.ReminderRepeat
import com.isotjs.todosian.utils.NoteLines
import com.isotjs.todosian.utils.NoteProblem
import com.isotjs.todosian.utils.NoteProblemKind
import com.isotjs.todosian.utils.TagDefinitions
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** A reminder [daysBefore] days before the task's date, at [time]. */
data class ProfileRule(val daysBefore: Int, val time: LocalTime)

/** The rules for one keyword; [repeat] overrides the "Remind again" setting for these reminders. */
data class KeywordRules(val tag: String, val rules: List<ProfileRule>, val repeat: ReminderRepeat?)

/** A reminder profile: one note in `Todosian/Reminders/`. */
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
    private val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*$""")
    private val tagHeadingRegex = Regex("""^#[^\s#]+$""")
    private val itemRegex = Regex("""^\s*[-*+]\s+(.*)$""")
    private val timeRegex = Regex("""(\d{1,2})[:.](\d{2})""")
    private val beforeRegex = Regex("""(\d+)\s*(?:days?|tage?n?|d)\s*(?:before|vorher|davor)""", RegexOption.IGNORE_CASE)
    private val repeatRegex = Regex("""^(?:repeat|erneut|wiederholen)\s*:\s*(\S+)""", RegexOption.IGNORE_CASE)

    fun parse(name: String, lines: List<String>): ReminderProfile = parseNote(name, lines).profile

    /** Reads the note with the line of every keyword, rule and repeat entry, and the lines it did not understand. */
    fun parseNote(name: String, lines: List<String>): ProfileNote {
        val keywords = mutableListOf<ProfileNoteKeyword>()
        val problems = mutableListOf<NoteProblem>()
        var tag: String? = null
        var headingLine = -1
        var rules = mutableListOf<ProfileNoteRule>()
        var repeat: ReminderRepeat? = null
        var repeatLine: Int? = null

        fun flush() {
            val current = tag
            if (current != null) keywords.add(ProfileNoteKeyword(current, headingLine, rules.toList(), repeat, repeatLine))
            tag = null
            rules = mutableListOf()
            repeat = null
            repeatLine = null
        }

        lines.forEachIndexed { index, line ->
            val heading = headingRegex.matchEntire(line.trimEnd())
            if (heading != null) {
                flush()
                val content = heading.groupValues[2]
                when {
                    tagHeadingRegex.matches(content) -> {
                        tag = content
                        headingLine = index
                    }
                    // A `# title` is fine; a deeper heading without a tag hides its rules.
                    heading.groupValues[1].length > 1 -> problems.add(NoteProblem(index, line, NoteProblemKind.BAD_HEADING))
                }
                return@forEachIndexed
            }
            if (tag == null) return@forEachIndexed
            val item = itemRegex.matchEntire(line)?.groupValues?.get(1)?.trim() ?: return@forEachIndexed
            val repeatMatch = repeatRegex.find(item)
            if (repeatMatch != null) {
                val value = parseRepeat(repeatMatch.groupValues[1])
                if (value == null) {
                    problems.add(NoteProblem(index, line, NoteProblemKind.BAD_REPEAT))
                } else {
                    repeat = value
                    repeatLine = index
                }
                return@forEachIndexed
            }
            val time = timeRegex.find(item)?.let { m ->
                val hour = m.groupValues[1].toInt()
                val minute = m.groupValues[2].toInt()
                if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
            }
            if (time == null) {
                problems.add(NoteProblem(index, line, NoteProblemKind.NO_TIME))
                return@forEachIndexed
            }
            val daysBefore = beforeRegex.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            rules.add(ProfileNoteRule(ProfileRule(daysBefore.coerceIn(0, 365), time), index))
        }
        flush()
        return ProfileNote(name, lines, keywords, problems)
    }

    private fun parseRepeat(value: String): ReminderRepeat? = when (value.lowercase().trimEnd('.', ',')) {
        "hourly", "stündlich", "stuendlich" -> ReminderRepeat.HOURLY
        "daily", "täglich", "taeglich" -> ReminderRepeat.DAILY
        "off", "never", "nie", "aus" -> ReminderRepeat.OFF
        else -> null
    }
}

data class ProfileNoteRule(val rule: ProfileRule, val line: Int)

/** A keyword heading of a profile note with its rules and repeat entry, by line. */
data class ProfileNoteKeyword(
    val tag: String,
    val headingLine: Int,
    val rules: List<ProfileNoteRule>,
    val repeat: ReminderRepeat?,
    val repeatLine: Int?,
) {
    val lastLine: Int get() = (listOf(headingLine) + rules.map { it.line } + listOfNotNull(repeatLine)).max()
}

/** A profile note as read: its lines, keywords with line numbers, and lines with wrong syntax. */
data class ProfileNote(
    val name: String,
    val lines: List<String>,
    val keywords: List<ProfileNoteKeyword>,
    val problems: List<NoteProblem>,
) {
    val profile: ReminderProfile
        get() = ReminderProfile(
            name,
            keywords.filter { it.rules.isNotEmpty() || it.repeat != null }.map { k -> KeywordRules(k.tag, k.rules.map { it.rule }, k.repeat) },
        )
}

/** What the profile planner needs to know about an open todo. */
data class ProfileTodo(
    val text: String,
    val lineIndex: Int,
    val tags: List<String>,
    val date: LocalDate?,
    val hasOwnReminder: Boolean,
    val taskId: String? = null,
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
                        taskId = todo.taskId,
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

/**
 * Line edits on a profile note for the in-app editor. Each returns the new lines, or null when
 * the line to change is no longer in the note; everything else in the note stays unchanged.
 */
object ProfileNoteEdits {
    fun addKeyword(lines: List<String>, tag: String): List<String> = NoteLines.append(lines, listOf("## $tag"))

    /** Removes the keyword's heading, rules and repeat entry; other text below it stays. */
    fun removeKeyword(lines: List<String>, note: ProfileNote, keyword: ProfileNoteKeyword): List<String>? {
        val targets = listOf(keyword.headingLine) + keyword.rules.map { it.line } + listOfNotNull(keyword.repeatLine)
        return NoteLines.remove(lines, targets.map { it to note.lines[it] })
    }

    fun addRule(lines: List<String>, note: ProfileNote, keyword: ProfileNoteKeyword, rule: ProfileRule, german: Boolean): List<String>? {
        val anchor = keyword.lastLine
        return NoteLines.insertAfter(lines, anchor, note.lines[anchor], listOf("- " + describe(rule, german)))
    }

    fun removeRule(lines: List<String>, note: ProfileNote, rule: ProfileNoteRule): List<String>? =
        NoteLines.remove(lines, listOf(rule.line to note.lines[rule.line]))

    /** Sets the repeat entry of [keyword]; null removes it, so the app setting applies. */
    fun setRepeat(lines: List<String>, note: ProfileNote, keyword: ProfileNoteKeyword, repeat: ReminderRepeat?, german: Boolean): List<String>? {
        val existing = keyword.repeatLine
        return when {
            existing != null && repeat == null -> NoteLines.remove(lines, listOf(existing to note.lines[existing]))
            existing != null && repeat != null ->
                NoteLines.replace(lines, existing, note.lines[existing], NoteLines.withItemPrefix(note.lines[existing], repeatText(repeat, german)))
            repeat != null -> NoteLines.insertAfter(lines, keyword.lastLine, note.lines[keyword.lastLine], listOf("- " + repeatText(repeat, german)))
            else -> lines
        }
    }

    fun describe(rule: ProfileRule, german: Boolean): String {
        val time = String.format(Locale.ROOT, "%02d:%02d", rule.time.hour, rule.time.minute)
        return when {
            rule.daysBefore == 0 -> time
            german -> "${rule.daysBefore} ${if (rule.daysBefore == 1) "Tag" else "Tage"} vorher $time"
            else -> "${rule.daysBefore} ${if (rule.daysBefore == 1) "day" else "days"} before $time"
        }
    }

    fun repeatText(repeat: ReminderRepeat, german: Boolean): String = if (german) {
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
