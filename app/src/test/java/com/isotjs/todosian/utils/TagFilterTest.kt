package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagFilterTest {
    private val definitions = TagDefinitionParser.parse(
        listOf(
            "# Schlagwörter",
            "## Aufwand",
            "- 5 Minuten: #5min, #kurz",
            "- 2 Stunden: #2h",
            "## Energie",
            "- Niedrig: #energie/niedrig",
            "- Hoch: #energie/hoch",
        ),
    )
    private val texts = listOf(
        "Zahnarzt anrufen #kurz #energie/niedrig",
        "Steuer #2h #energie/hoch #papierkram",
        "Garage aufräumen #2h",
        "Ohne Tag",
    )

    @Test
    fun listsUsedKeywordsInNoteOrderThenOtherTags() {
        val terms = TagFilter.termsFor(texts.map { it to definitions })
        assertEquals(listOf("5 Minuten", "2 Stunden", "Niedrig", "Hoch", "papierkram"), terms.map { it.label })
        assertEquals(listOf("Aufwand", "Aufwand", "Energie", "Energie", null), terms.map { it.group })
    }

    @Test
    fun sameGroupIsOrAndOtherGroupsAreAnd() {
        val terms = TagFilter.termsFor(texts.map { it to definitions }).associateBy { it.label }
        val short = terms.getValue("5 Minuten")
        val long = terms.getValue("2 Stunden")
        val low = terms.getValue("Niedrig")

        assertEquals(texts.take(3), texts.filter { TagFilter.matches(it, listOf(short, long)) })
        assertEquals(listOf(texts[0]), texts.filter { TagFilter.matches(it, listOf(short, long, low)) })
        assertTrue(TagFilter.matches("Synonym #5min", listOf(short)))
        assertFalse(TagFilter.matches("Ohne Tag", listOf(terms.getValue("papierkram"))))
        assertTrue(TagFilter.matches("Ohne Tag", emptyList()))
    }

    @Test
    fun worksWithoutKeywordNote() {
        val terms = TagFilter.termsFor(listOf("a #Einkauf" to TagDefinitions.EMPTY, "b #einkauf #baumarkt" to TagDefinitions.EMPTY))
        assertEquals(listOf("baumarkt", "Einkauf"), terms.map { it.label })
        assertTrue(TagFilter.matches("b #einkauf", listOf(terms[1])))
    }
}
