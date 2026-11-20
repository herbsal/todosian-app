package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagDefinitionsTest {
    private val note = """
        # Keywords

        Some text that is not a keyword.

        ## Aufwand
        - 5 Minuten: #5min, #kurz
        - 30 Minuten: #30min
        - #2h

        ## Energie
        * Niedrig: #energie/niedrig #energy/low
        * Hoch: #energie/hoch, #Energy/High

        ```
        - Ignored: #nope
        ```
        - [ ] A task #notakeyword
    """.trimIndent().lines()

    private val defs = TagDefinitionParser.parse(note)

    @Test
    fun `groups follow headings`() {
        assertEquals(listOf("Aufwand", "Energie"), defs.groups.map { it.name })
        assertEquals(listOf("5 Minuten", "30 Minuten", "2h"), defs.groups[0].terms.map { it.label })
    }

    @Test
    fun `synonyms are kept in order and the first is primary`() {
        val low = defs.groups[1].terms[0]
        assertEquals(listOf("#energie/niedrig", "#energy/low"), low.tags)
        assertEquals("#energie/niedrig", low.primaryTag)
    }

    @Test
    fun `lookup ignores case and finds synonyms`() {
        assertEquals("Hoch", defs.termFor("#energy/high")?.label)
        assertEquals("5 Minuten", defs.termFor("#KURZ")?.label)
        assertNull(defs.termFor("#nope"))
        assertNull(defs.termFor("#notakeyword"))
    }

    @Test
    fun `items before any heading form an unnamed group`() {
        val d = TagDefinitionParser.parse(listOf("- Kinder: #kinder, #kids", "## Ort", "- Zuhause: #zuhause"))
        assertNull(d.groups[0].name)
        assertFalse(d.groups[0].exclusive)
        assertTrue(d.groups[1].exclusive)
    }

    @Test
    fun `numbers only are not tags`() {
        val d = TagDefinitionParser.parse(listOf("- Year: #2026, #jahr"))
        assertEquals(listOf("#jahr"), d.groups[0].terms[0].tags)
    }

    @Test
    fun `empty note gives no keywords`() {
        assertTrue(TagDefinitionParser.parse(emptyList()).isEmpty)
    }

    @Test
    fun `setting a keyword appends its primary tag`() {
        val effort = defs.groups[0]
        assertEquals("Call doctor #5min", TagText.toggle("Call doctor", effort, effort.terms[0]))
        assertEquals("#5min", TagText.toggle("", effort, effort.terms[0]))
    }

    @Test
    fun `setting a keyword in a named group replaces the other one`() {
        val effort = defs.groups[0]
        assertEquals("Call doctor #kinder #30min", TagText.toggle("Call doctor #kurz #kinder", effort, effort.terms[1]))
    }

    @Test
    fun `clearing removes every synonym`() {
        val energy = defs.groups[1]
        val low = energy.terms[0]
        assertTrue(TagText.hasTerm("Tidy up #Energy/Low", low))
        assertEquals("Tidy up #kinder", TagText.toggle("Tidy up #energy/low #kinder #energie/niedrig", energy, low))
    }

    @Test
    fun `a longer tag is not taken for a shorter one`() {
        val effort = defs.groups[0]
        assertFalse(TagText.hasTerm("Read #5minutes", effort.terms[0]))
        assertEquals("Read #5minutes #5min", TagText.toggle("Read #5minutes", effort, effort.terms[0]))
    }
}
