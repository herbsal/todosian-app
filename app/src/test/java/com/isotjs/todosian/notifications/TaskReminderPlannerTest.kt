package com.isotjs.todosian.notifications

import com.isotjs.todosian.data.settings.ReminderRepeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TaskReminderPlannerTest {
    private val now = LocalDateTime.of(2026, 11, 20, 12, 0)

    private fun candidate(text: String, reminder: LocalDateTime, file: String = "content://notes/a.md") =
        ReminderCandidate(fileKey = file, text = text, reminder = reminder, lineIndex = 0)

    @Test
    fun future_reminder_is_planned_at_its_time() {
        val c = candidate("Arzt", now.plusHours(3))

        val plan = TaskReminderPlanner.plan(listOf(c), now, emptyMap(), ReminderRepeat.OFF)

        assertEquals(listOf(now.plusHours(3)), plan.map { it.triggerAt })
    }

    @Test
    fun missed_reminder_within_a_day_fires_now() {
        val plan = TaskReminderPlanner.plan(listOf(candidate("A", now.minusHours(5))), now, emptyMap(), ReminderRepeat.OFF)

        assertEquals(listOf(now), plan.map { it.triggerAt })
    }

    @Test
    fun reminder_older_than_a_day_is_skipped() {
        val plan = TaskReminderPlanner.plan(listOf(candidate("A", now.minusDays(2))), now, emptyMap(), ReminderRepeat.DAILY)

        assertTrue(plan.isEmpty())
    }

    @Test
    fun notified_reminder_is_not_repeated_when_repeat_is_off() {
        val c = candidate("A", now.minusHours(1))
        val notified = mapOf(TaskReminderPlanner.keyOf(c) to now.minusHours(1))

        assertTrue(TaskReminderPlanner.plan(listOf(c), now, notified, ReminderRepeat.OFF).isEmpty())
    }

    @Test
    fun notified_reminder_repeats_hourly_and_daily() {
        val c = candidate("A", now.minusMinutes(30))
        val notified = mapOf(TaskReminderPlanner.keyOf(c) to now.minusMinutes(30))

        assertEquals(
            now.plusMinutes(30),
            TaskReminderPlanner.plan(listOf(c), now, notified, ReminderRepeat.HOURLY).single().triggerAt,
        )
        assertEquals(
            now.minusMinutes(30).plusDays(1),
            TaskReminderPlanner.plan(listOf(c), now, notified, ReminderRepeat.DAILY).single().triggerAt,
        )
    }

    @Test
    fun overdue_repeat_fires_now() {
        val c = candidate("A", now.minusDays(3))
        val notified = mapOf(TaskReminderPlanner.keyOf(c) to now.minusDays(2))

        assertEquals(now, TaskReminderPlanner.plan(listOf(c), now, notified, ReminderRepeat.DAILY).single().triggerAt)
    }

    @Test
    fun changed_reminder_time_is_a_new_reminder() {
        val old = candidate("A", now.minusHours(1))
        val moved = candidate("A", now.plusHours(1))
        val notified = mapOf(TaskReminderPlanner.keyOf(old) to now.minusHours(1))

        val plan = TaskReminderPlanner.plan(listOf(moved), now, notified, ReminderRepeat.OFF)

        assertEquals(listOf(now.plusHours(1)), plan.map { it.triggerAt })
    }

    @Test
    fun same_text_in_two_notes_gives_two_reminders_and_duplicates_collapse() {
        val a = candidate("Müll", now.plusHours(1), file = "content://notes/a.md")
        val b = candidate("Müll", now.plusHours(1), file = "content://notes/b.md")

        val plan = TaskReminderPlanner.plan(listOf(a, a, b), now, emptyMap(), ReminderRepeat.OFF)

        assertEquals(2, plan.size)
    }

    @Test
    fun plan_is_sorted_and_capped() {
        val many = (1..150).map { candidate("T$it", now.plusMinutes(it.toLong())) }.shuffled()

        val plan = TaskReminderPlanner.plan(many, now, emptyMap(), ReminderRepeat.OFF)

        assertEquals(TaskReminderPlanner.MAX_ALARMS, plan.size)
        assertEquals(now.plusMinutes(1), plan.first().triggerAt)
        assertEquals(plan.sortedBy { it.triggerAt }, plan)
    }

    @Test
    fun shown_state_survives_a_text_edit_and_a_rename() {
        val at = now.minusHours(1)
        val old = candidate("Arzt anrufen", now.minusHours(2))
        val shown = mapOf(TaskReminderPlanner.keyOf(old) to at)

        // Text fixed in place, same note and same ⏰: still shown, so no second alarm.
        val edited = candidate("Arzt anrufen wegen Termin", now.minusHours(2))
        val afterEdit = TaskReminderPlanner.carryOverNotified(shown, listOf(edited))
        assertEquals(mapOf(TaskReminderPlanner.keyOf(edited) to at), afterEdit)
        assertTrue(TaskReminderPlanner.plan(listOf(edited), now, afterEdit, ReminderRepeat.OFF).isEmpty())

        // Note renamed (A to B to C): the state moves along.
        val renamed = candidate("Arzt anrufen", now.minusHours(2), file = "content://notes/c.md")
        val renames = mapOf("content://notes/a.md" to "content://notes/b.md", "content://notes/b.md" to "content://notes/c.md")
        assertEquals(mapOf(TaskReminderPlanner.keyOf(renamed) to at), TaskReminderPlanner.carryOverNotified(shown, listOf(renamed), renames))
    }

    @Test
    fun shown_state_is_not_guessed_when_tasks_could_be_mixed_up() {
        val time = now.minusHours(2)
        val shown = mapOf(
            TaskReminderPlanner.keyOf(candidate("A", time)) to now,
            TaskReminderPlanner.keyOf(candidate("B", time)) to now,
        )
        val changed = listOf(candidate("A2", time), candidate("B2", time))
        assertTrue(TaskReminderPlanner.carryOverNotified(shown, changed).isEmpty())

        // A new ⏰ time is a new reminder.
        val later = candidate("A", now.plusHours(1))
        assertTrue(TaskReminderPlanner.carryOverNotified(mapOf(TaskReminderPlanner.keyOf(candidate("A", time)) to now), listOf(later)).isEmpty())
    }
}
