package com.isotjs.todosian.notifications

import com.isotjs.todosian.data.settings.ReminderRepeat
import java.time.Duration
import java.time.LocalDateTime

/** An open todo with a ⏰ reminder, found in the note [fileKey] (its URI as a string). */
data class ReminderCandidate(
    val fileKey: String,
    val text: String,
    val reminder: LocalDateTime,
    val lineIndex: Int,
    /** True when the reminder comes from a reminder profile rather than a ⏰ in the line. */
    val fromProfile: Boolean = false,
    /** Overrides the "Remind again" setting, set by a profile rule. */
    val repeat: ReminderRepeat? = null,
    /** The task as the notification shows it, tags as symbols per the display setting. */
    val displayText: String? = null,
)

data class PlannedReminder(
    val key: String,
    val candidate: ReminderCandidate,
    val triggerAt: LocalDateTime,
)

/** Decides which reminder alarms should exist. Kept free of Android types so it can be unit tested. */
object TaskReminderPlanner {
    /** AlarmManager allows a few hundred alarms per app; the nearest ones are enough. */
    const val MAX_ALARMS = 100

    /** Reminders missed while the phone was off are still shown if they are at most this old. */
    val CATCH_UP: Duration = Duration.ofHours(24)

    private const val SEPARATOR = '\u001F'

    /** Identifies a reminder by note, text and time, so it survives lines moving around in the file. */
    fun keyOf(candidate: ReminderCandidate): String =
        "${candidate.fileKey}$SEPARATOR${candidate.text}$SEPARATOR${candidate.reminder}"

    /**
     * Keeps "already shown" across edits that Markdown cannot tell apart from a new task: a note
     * that was renamed ([renamedFiles], old to new key) and a task whose text changed while its
     * note and reminder time stayed. The second only counts when exactly one shown reminder and
     * one new reminder share note and time, so two tasks are never mixed up.
     */
    fun carryOverNotified(
        lastNotified: Map<String, LocalDateTime>,
        candidates: List<ReminderCandidate>,
        renamedFiles: Map<String, String> = emptyMap(),
    ): Map<String, LocalDateTime> {
        if (lastNotified.isEmpty()) return lastNotified
        val result = HashMap<String, LocalDateTime>()
        // Old keys under the note's current name.
        lastNotified.forEach { (key, at) ->
            val parts = key.split(SEPARATOR)
            val file = finalName(parts[0], renamedFiles)
            val moved = if (file != parts[0] && parts.size == 3) "$file$SEPARATOR${parts[1]}$SEPARATOR${parts[2]}" else key
            result[moved] = maxOf(at, result[moved] ?: at)
        }
        val keys = candidates.distinctBy { keyOf(it) }.associateBy { keyOf(it) }
        val unmatchedOld = result.keys.filter { it !in keys }
        val unmatchedNew = keys.keys.filter { it !in result }
        fun slot(key: String): String? = key.split(SEPARATOR).takeIf { it.size == 3 }?.let { "${it[0]}$SEPARATOR${it[2]}" }
        val oldBySlot = unmatchedOld.groupBy { slot(it) }
        val newBySlot = unmatchedNew.groupBy { slot(it) }
        oldBySlot.forEach { (slot, old) ->
            val new = newBySlot[slot] ?: return@forEach
            if (slot != null && old.size == 1 && new.size == 1) result[new.single()] = result.getValue(old.single())
        }
        return result.filterKeys { it in keys }
    }

    /** Follows a chain of renames (A to B, then B to C) to the last name. */
    private fun finalName(file: String, renamedFiles: Map<String, String>): String {
        var current = file
        repeat(renamedFiles.size) { current = renamedFiles[current] ?: return current }
        return current
    }

    /** What an alarm carries; an alarm whose signature did not change is left as it is. */
    fun signatureOf(planned: PlannedReminder): String =
        "${planned.triggerAt}$SEPARATOR${planned.candidate.lineIndex}$SEPARATOR${planned.candidate.fromProfile}$SEPARATOR" +
            "${planned.candidate.displayText}$SEPARATOR${planned.candidate.text}"

    fun plan(
        candidates: List<ReminderCandidate>,
        now: LocalDateTime,
        lastNotified: Map<String, LocalDateTime>,
        repeat: ReminderRepeat,
    ): List<PlannedReminder> {
        return candidates
            .distinctBy { keyOf(it) }
            .mapNotNull { candidate ->
                val key = keyOf(candidate)
                val trigger = triggerFor(candidate.reminder, lastNotified[key], now, candidate.repeat ?: repeat)
                    ?: return@mapNotNull null
                PlannedReminder(key, candidate, trigger)
            }
            .sortedBy { it.triggerAt }
            .take(MAX_ALARMS)
    }

    private fun triggerFor(
        reminder: LocalDateTime,
        lastNotified: LocalDateTime?,
        now: LocalDateTime,
        repeat: ReminderRepeat,
    ): LocalDateTime? {
        if (lastNotified == null) {
            if (reminder.isBefore(now.minus(CATCH_UP))) return null
            return maxOf(reminder, now)
        }
        val interval = repeat.interval ?: return null
        return maxOf(lastNotified.plus(interval), now)
    }
}

val ReminderRepeat.interval: Duration?
    get() = when (this) {
        ReminderRepeat.OFF -> null
        ReminderRepeat.HOURLY -> Duration.ofHours(1)
        ReminderRepeat.DAILY -> Duration.ofDays(1)
    }
