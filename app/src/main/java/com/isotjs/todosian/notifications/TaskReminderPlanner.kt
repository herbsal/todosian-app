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
