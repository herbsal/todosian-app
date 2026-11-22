package com.isotjs.todosian.notifications

import com.isotjs.todosian.data.settings.ReminderRepeat
import com.isotjs.todosian.utils.TagDefinitionParser
import com.isotjs.todosian.utils.TagDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderProfilesTest {
    private val profile = ReminderProfileParser.parse(
        "Michael",
        """
        # Michael

        Text before the first keyword is ignored.
        - 07:00

        ## #energie/niedrig
        - 09:00
        - 1 Tag vorher 18:00
        - erneut: stündlich

        ## #2h
        - 2 days before 8.30
        - repeat: daily
        - no time here
        """.trimIndent().lines(),
    )

    private val definitions = TagDefinitionParser.parse(listOf("## Energie", "- Niedrig: #energie/niedrig, #energy/low"))

    @Test
    fun `headings and rules are read in German and English`() {
        assertEquals(listOf("#energie/niedrig", "#2h"), profile.keywords.map { it.tag })
        val low = profile.keywords[0]
        assertEquals(listOf(ProfileRule(0, LocalTime.of(9, 0)), ProfileRule(1, LocalTime.of(18, 0))), low.rules)
        assertEquals(ReminderRepeat.HOURLY, low.repeat)
        assertEquals(listOf(ProfileRule(2, LocalTime.of(8, 30))), profile.keywords[1].rules)
        assertEquals(ReminderRepeat.DAILY, profile.keywords[1].repeat)
    }

    @Test
    fun `a todo with the keyword gets one reminder per rule`() {
        val todo = ProfileTodo("Tidy up", 3, listOf("#energie/niedrig"), LocalDate.of(2026, 11, 24), hasOwnReminder = false)
        val result = ProfileReminderPlanner.candidates("file", listOf(todo), profile, TagDefinitions.EMPTY)
        assertEquals(
            listOf(LocalDateTime.of(2026, 11, 24, 9, 0), LocalDateTime.of(2026, 11, 23, 18, 0)),
            result.map { it.reminder },
        )
        assertTrue(result.all { it.fromProfile && it.repeat == ReminderRepeat.HOURLY && it.lineIndex == 3 })
    }

    @Test
    fun `synonyms from the keyword note match`() {
        val todo = ProfileTodo("Tidy up", 0, listOf("#Energy/Low"), LocalDate.of(2026, 11, 24), hasOwnReminder = false)
        assertEquals(2, ProfileReminderPlanner.candidates("file", listOf(todo), profile, definitions).size)
        assertEquals(0, ProfileReminderPlanner.candidates("file", listOf(todo), profile, TagDefinitions.EMPTY).size)
    }

    @Test
    fun `own reminder, missing date or other keywords give nothing`() {
        val date = LocalDate.of(2026, 11, 24)
        val todos = listOf(
            ProfileTodo("Own", 0, listOf("#2h"), date, hasOwnReminder = true),
            ProfileTodo("No date", 1, listOf("#2h"), null, hasOwnReminder = false),
            ProfileTodo("Other", 2, listOf("#kinder"), date, hasOwnReminder = false),
        )
        assertTrue(ProfileReminderPlanner.candidates("file", todos, profile, definitions).isEmpty())
    }

    @Test
    fun `profile repeat overrides the setting in the planner`() {
        val candidate = ReminderCandidate("f", "t", LocalDateTime.of(2026, 11, 20, 9, 0), 0, fromProfile = true, repeat = ReminderRepeat.HOURLY)
        val key = TaskReminderPlanner.keyOf(candidate)
        val now = LocalDateTime.of(2026, 11, 20, 9, 30)
        val plan = TaskReminderPlanner.plan(listOf(candidate), now, mapOf(key to LocalDateTime.of(2026, 11, 20, 9, 0)), ReminderRepeat.OFF)
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), plan.single().triggerAt)
    }
}
