package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewTasksTest {
    private val milk = SeenTask("Milch kaufen", "- [ ] Milch kaufen")
    private val dentist = SeenTask("Zahnarzt anrufen #alex", "- [ ] Zahnarzt anrufen #alex 📅 2026-11-21")

    @Test
    fun firstLookFindsNothing() {
        assertTrue(NewTasks.detect(null, listOf(milk, dentist), emptySet()).isEmpty())
    }

    @Test
    fun addedTaskIsNew() {
        val previous = setOf(milk.key)
        assertEquals(listOf(dentist), NewTasks.detect(previous, listOf(milk, dentist), emptySet()))
    }

    @Test
    fun ownLinesAreNotNew() {
        val previous = setOf(milk.key)
        val own = setOf(NewTasks.lineKey(dentist.line))
        assertTrue(NewTasks.detect(previous, listOf(milk, dentist), own).isEmpty())
    }

    @Test
    fun changedDateOrCaseIsNotNew() {
        val previous = setOf(dentist.key)
        val moved = SeenTask("zahnarzt anrufen #alex ", "- [ ] zahnarzt anrufen #alex 📅 2026-11-25")
        assertTrue(NewTasks.detect(previous, listOf(moved), emptySet()).isEmpty())
    }
}
