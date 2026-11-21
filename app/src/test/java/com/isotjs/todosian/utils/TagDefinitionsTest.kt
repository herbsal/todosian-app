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

    @Test
    fun `a task can be assigned to several people`() {
        val d = TagDefinitionParser.parse(listOf("## 👤 Wer", "- Alex: #person/alex", "- Sam: #person/sam", "## Person", "- Kim: #kim"))
        val who = d.groups[0]
        assertTrue(who.isPerson)
        assertFalse(who.exclusive)
        assertTrue(d.groups[1].isPerson)
        val both = TagText.toggle("Kita anrufen #person/alex", who, who.terms[1])
        assertEquals("Kita anrufen #person/alex #person/sam", both)
        assertEquals("Kita anrufen #person/sam", TagText.toggle(both, who, who.terms[0]))
    }

    @Test
    fun `words in quotes are own recognition words, not unknown text`() {
        val note = TagDefinitionParser.parseNote(listOf("## ⏱ Aufwand", "- 30 Min: #30min, #halbestunde \"dreißig Minuten\", „halbe Std“"))
        val term = note.definitions.groups.single().terms.single()
        assertEquals(listOf("#30min", "#halbestunde"), term.tags)
        assertEquals(listOf("dreißig Minuten", "halbe Std"), term.words)
        assertTrue(note.problems.isEmpty())
    }

    @Test
    fun `own words are written in quotes and read back`() {
        val term = TagTerm("30 Min", listOf("#30min"), listOf("dreißig Minuten", " halbe  Stunde ", "DREIßIG MINUTEN"))
        val line = TagNoteEdits.termLine(term)
        assertEquals("- 30 Min: #30min \"dreißig Minuten\", \"halbe Stunde\"", line)
        val read = TagDefinitionParser.parse(listOf("## Aufwand", line)).groups.single().terms.single()
        assertEquals(listOf("dreißig Minuten", "halbe Stunde"), read.words)
        assertEquals("- 5 Min: #5min", TagNoteEdits.termLine(TagTerm("5 Min", listOf("#5min"))))
    }

    @Test
    fun `typed words are split at commas`() {
        assertEquals(listOf("dreißig Minuten", "halbe Stunde"), TagWords.parseInput("dreißig Minuten, halbe Stunde,, \"x\"; "))
    }
}
