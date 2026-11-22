package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TagTextSplitTest {
    private val definitions = TagDefinitions(
        listOf(
            TagGroup("Dauer", listOf(TagTerm("5 Min", listOf("#5min")), TagTerm("30 Min", listOf("#30min")))),
            TagGroup(null, listOf(TagTerm("Telefon", listOf("#telefon")))),
        ),
    )

    @Test
    fun `known tags leave the words`() {
        val split = TagText.split("Zahnarzt #5min anrufen #telefon", 0, definitions)
        assertEquals("Zahnarzt anrufen", split.words)
        assertEquals(listOf("#5min", "#telefon"), split.tags)
    }

    @Test
    fun `other tags stay`() {
        val split = TagText.split("#5min Müll #draußen", 0, definitions)
        assertEquals("Müll #draußen", split.words)
        assertEquals(listOf("#5min"), split.tags)
    }

    @Test
    fun `the cursor moves along`() {
        // "Müll #5min |" with the cursor after the space at the end.
        val split = TagText.split("Müll #5min ", 11, definitions)
        assertEquals("Müll ", split.words)
        assertEquals(5, split.cursor)
    }

    @Test
    fun `join keeps the words in front`() {
        assertEquals("Müll #5min", TagText.join("Müll", listOf("#5min")))
        assertEquals("Müll #5min", TagText.join("Müll ", listOf("#5min")))
        assertEquals("#5min", TagText.join("", listOf("#5min")))
        assertEquals("Müll", TagText.join("Müll", emptyList()))
    }
}
