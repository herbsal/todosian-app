package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MarkdownParserLocateTest {
    private val shown = listOf(
        "# Groceries",
        "- [ ] Milk",
        "- [ ] Bread",
        "- [ ] Eggs",
    )

    @Test
    fun locate_returns_same_index_when_file_is_unchanged() {
        assertEquals(2, MarkdownParser.locateTodoLine(shown, 2, "- [ ] Bread"))
    }

    @Test
    fun locate_follows_line_that_moved_because_of_external_insert() {
        val fresh = listOf(
            "# Groceries",
            "- [ ] Butter",
            "- [ ] Milk",
            "- [ ] Bread",
            "- [ ] Eggs",
        )

        assertEquals(3, MarkdownParser.locateTodoLine(fresh, 2, "- [ ] Bread"))
    }

    @Test
    fun locate_returns_null_when_line_was_edited_elsewhere() {
        val fresh = listOf(
            "# Groceries",
            "- [ ] Milk",
            "- [x] Bread ✅ 2026-11-19",
            "- [ ] Eggs",
        )

        assertNull(MarkdownParser.locateTodoLine(fresh, 2, "- [ ] Bread"))
    }

    @Test
    fun locate_returns_null_when_line_was_deleted_elsewhere() {
        val fresh = listOf("# Groceries", "- [ ] Milk", "- [ ] Eggs")

        assertNull(MarkdownParser.locateTodoLine(fresh, 2, "- [ ] Bread"))
    }

    @Test
    fun locate_returns_null_for_empty_file() {
        assertNull(MarkdownParser.locateTodoLine(emptyList(), 0, "- [ ] Bread"))
    }

    @Test
    fun locate_rejects_non_todo_expected_line() {
        assertNull(MarkdownParser.locateTodoLine(shown, 0, "# Groceries"))
    }

    @Test
    fun locate_picks_nearest_duplicate() {
        val fresh = listOf(
            "- [ ] Call 📞",
            "text",
            "text",
            "text",
            "- [ ] Call 📞",
            "- [ ] Other",
        )

        assertEquals(4, MarkdownParser.locateTodoLine(fresh, 5, "- [ ] Call 📞"))
        assertEquals(0, MarkdownParser.locateTodoLine(fresh, 1, "- [ ] Call 📞"))
    }

    @Test
    fun toggle_on_fresh_lines_keeps_external_changes() {
        // The app still shows `shown`, while Syncthing already delivered a new todo and an edit.
        val fresh = listOf(
            "# Groceries",
            "- [ ] Butter",
            "- [ ] Milk 2 l",
            "- [ ] Bread",
            "- [ ] Eggs",
            "",
        )

        val index = MarkdownParser.locateTodoLine(fresh, 2, shown[2])!!
        val result = MarkdownParser.tryToggleLine(
            lines = fresh,
            lineIndex = index,
            enableTasksPlugin = true,
            today = LocalDate.of(2026, 11, 19),
        )

        assertEquals(
            listOf(
                "# Groceries",
                "- [ ] Butter",
                "- [ ] Milk 2 l",
                "- [x] Bread ✅ 2026-11-19",
                "- [ ] Eggs",
                "",
            ),
            result,
        )
    }
}
