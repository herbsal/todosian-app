package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MarkdownParserReminderTest {

    @Test
    fun parse_reads_reminder_after_text_and_keeps_tasks_fields() {
        val todo = MarkdownParser.parse(
            listOf("- [ ] Kinderarzt anrufen ⏰ 2026-11-24 08:30 ⏫ 📅 2026-11-24"),
        ).single()

        assertEquals("Kinderarzt anrufen", todo.text)
        assertEquals("2026-11-24 08:30", todo.reminder)
        assertEquals("2026-11-24", todo.dueDate)
    }

    @Test
    fun parse_reads_reminder_typed_after_the_fields() {
        val todo = MarkdownParser.parse(listOf("- [ ] Müll 🔁 every week ⏰ 2026-11-24 19:00")).single()

        assertEquals("Müll", todo.text)
        assertEquals("every week", todo.recurrence)
        assertEquals("2026-11-24 19:00", todo.reminder)
    }

    @Test
    fun parse_normalises_single_digit_hour_and_t_separator() {
        assertEquals("2026-11-24 09:05", MarkdownParser.parse(listOf("- [ ] A ⏰ 2026-11-24 9:05")).single().reminder)
        assertEquals("2026-11-24 19:00", MarkdownParser.parse(listOf("- [ ] A ⏰ 2026-11-24T19:00")).single().reminder)
    }

    @Test
    fun parse_leaves_invalid_reminder_in_text() {
        val todo = MarkdownParser.parse(listOf("- [ ] Wecker ⏰ 2026-13-40 25:99")).single()

        assertNull(todo.reminder)
        assertEquals("Wecker ⏰ 2026-13-40 25:99", todo.text)
    }

    @Test
    fun parse_keeps_tags_around_reminder_in_text() {
        val todo = MarkdownParser.parse(listOf("- [ ] Rezept #kinder ⏰ 2026-11-20 10:00 📅 2026-11-20 #apotheke")).single()

        assertEquals("Rezept #kinder #apotheke", todo.text)
        assertEquals("2026-11-20 10:00", todo.reminder)
        assertEquals("2026-11-20", todo.dueDate)
    }

    @Test
    fun toggle_keeps_reminder_in_the_done_line() {
        val lines = listOf("- [ ] A ⏰ 2026-11-24 19:00 📅 2026-11-24")

        val result = MarkdownParser.toggleLine(lines, 0, enableTasksPlugin = true, today = LocalDate.of(2026, 11, 24))

        assertEquals(listOf("- [x] A ⏰ 2026-11-24 19:00 📅 2026-11-24 ✅ 2026-11-24"), result)
    }

    @Test
    fun recurring_task_moves_reminder_with_due_date() {
        val lines = listOf("- [ ] Müll rausstellen ⏰ 2026-11-24 19:00 🔁 every week 📅 2026-11-24")

        val result = MarkdownParser.toggleLine(lines, 0, enableTasksPlugin = true, today = LocalDate.of(2026, 11, 24))

        assertEquals("- [ ] Müll rausstellen ⏰ 2026-12-01 19:00 📅 2026-12-01 🔁 every week", result[0])
        assertEquals("- [x] Müll rausstellen ⏰ 2026-11-24 19:00 🔁 every week 📅 2026-11-24 ✅ 2026-11-24", result[1])
    }

    @Test
    fun recurring_task_with_only_a_reminder_follows_the_rule() {
        val lines = listOf("- [ ] Pflanzen gießen ⏰ 2026-11-23 18:00 🔁 every week on Monday")

        val result = MarkdownParser.toggleLine(lines, 0, enableTasksPlugin = true, today = LocalDate.of(2026, 11, 23))

        assertEquals("- [ ] Pflanzen gießen ⏰ 2026-11-30 18:00 🔁 every week on Monday", result[0])
    }

    @Test
    fun recurring_when_done_moves_reminder_from_today() {
        val lines = listOf("- [ ] Filter wechseln ⏰ 2026-11-01 10:00 🔁 every month when done 📅 2026-11-01")

        val result = MarkdownParser.toggleLine(lines, 0, enableTasksPlugin = true, today = LocalDate.of(2026, 11, 20))

        assertEquals("- [ ] Filter wechseln ⏰ 2026-12-20 10:00 📅 2026-12-20 🔁 every month when done", result[0])
    }

    @Test
    fun editTodoText_keeps_reminder() {
        val lines = listOf("- [ ] Alt ⏰ 2026-11-24 19:00 📅 2026-11-24")

        val result = MarkdownParser.editTodoText(lines, 0, "Neu")

        assertEquals(listOf("- [ ] Neu ⏰ 2026-11-24 19:00 📅 2026-11-24"), result)
    }

    @Test
    fun editTodo_with_meta_writes_reminder_before_fields() {
        val lines = listOf("- [ ] Alt")

        val result = MarkdownParser.editTodo(
            lines = lines,
            lineIndex = 0,
            newText = "Neu",
            meta = MarkdownParser.TasksMeta(dueDate = "2026-11-24", reminder = "2026-11-24 19:00"),
            enableTasksPlugin = true,
        )

        assertEquals(listOf("- [ ] Neu ⏰ 2026-11-24 19:00 📅 2026-11-24"), result)
    }

    @Test
    fun parseReminder_and_format_round_trip() {
        val value = LocalDateTime.of(2026, 1, 2, 3, 4)
        assertEquals(value, MarkdownParser.parseReminder(MarkdownParser.formatReminder(value)))
        assertNull(MarkdownParser.parseReminder("morgen"))
        assertNull(MarkdownParser.parseReminder(null))
    }

    @Test
    fun locateReminderTodo_finds_open_todo_by_text_and_time() {
        val lines = listOf(
            "# Liste",
            "- [x] Arzt ⏰ 2026-11-24 08:30",
            "- [ ] Neu von Syncthing",
            "- [ ] Arzt ⏰ 2026-11-24 08:30",
            "- [ ] Arzt ⏰ 2026-11-25 08:30",
        )

        assertEquals(3, MarkdownParser.locateReminderTodo(lines, "Arzt", "2026-11-24 08:30", lineIndexHint = 1))
        assertNull(MarkdownParser.locateReminderTodo(lines, "Arzt", "2026-11-26 08:30"))
    }

    @Test
    fun replaceReminder_changes_only_the_reminder() {
        val lines = listOf("- [ ] Arzt ⏰ 2026-11-24 08:30 ⏫ 📅 2026-11-24")

        val result = MarkdownParser.replaceReminder(lines, 0, LocalDateTime.of(2026, 11, 24, 9, 30))

        assertEquals(listOf("- [ ] Arzt ⏰ 2026-11-24 09:30 ⏫ 📅 2026-11-24"), result)
        assertNull(MarkdownParser.replaceReminder(listOf("- [ ] Ohne"), 0, LocalDateTime.of(2026, 1, 1, 0, 0)))
    }

    @Test
    fun `setReminder inserts the reminder before the Tasks fields`() {
        val lines = listOf("- [ ] Tidy up #energie/niedrig 📅 2026-11-24")
        val result = MarkdownParser.setReminder(lines, 0, LocalDateTime.of(2026, 11, 24, 9, 0))
        assertEquals(listOf("- [ ] Tidy up #energie/niedrig ⏰ 2026-11-24 09:00 📅 2026-11-24"), result)
        val todo = MarkdownParser.parse(result!!).single()
        assertEquals("2026-11-24", todo.dueDate)
        assertEquals("2026-11-24 09:00", todo.reminder)
    }

    @Test
    fun `setReminder replaces an existing reminder`() {
        val lines = listOf("  - [ ] Call ⏰ 2026-11-24 09:00 📅 2026-11-24")
        assertEquals(
            listOf("  - [ ] Call ⏰ 2026-11-25 10:30 📅 2026-11-24"),
            MarkdownParser.setReminder(lines, 0, LocalDateTime.of(2026, 11, 25, 10, 30)),
        )
    }

    @Test
    fun `locateOpenTodo finds the open todo by text only`() {
        val lines = listOf("- [x] Call", "- [ ] Other", "- [ ] Call 📅 2026-11-24")
        assertEquals(2, MarkdownParser.locateOpenTodo(lines, "Call", lineIndexHint = 0))
        assertNull(MarkdownParser.locateOpenTodo(lines, "Missing"))
    }
}
