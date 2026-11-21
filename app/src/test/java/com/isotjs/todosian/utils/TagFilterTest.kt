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

    @Test
    fun savedViewsUseSynonymsAndGroups() {
        val views = SavedViews.parse(
            listOf(
                "# Ansichten",
                "- Kleine Aufgaben: #kurz, #2h",
                "- Schnell und müde: #5min, #energie/niedrig",
                "- Papier: #papierkram",
            ),
            definitions,
            "folder",
        )
        assertEquals(listOf("Kleine Aufgaben", "Schnell und müde", "Papier"), views.map { it.name })
        // #kurz and #2h are both effort: either one is enough.
        assertEquals(texts.take(3), texts.filter { TagFilter.matches(it, views[0].terms) })
        // Effort and energy are different groups: both must apply; #5min is a synonym of #kurz.
        assertEquals(listOf(texts[0]), texts.filter { TagFilter.matches(it, views[1].terms) })
        assertEquals(listOf(texts[1]), texts.filter { TagFilter.matches(it, views[2].terms) })
    }

    @Test
    fun savedViewAlternativesAreOr() {
        val lines = listOf(
            "# Ansichten",
            "- Leicht: #kurz, #energie/niedrig | #papierkram",
            "- Lang: #2h oder #energie/hoch",
        )
        val views = SavedViews.parse(lines, definitions, "folder")
        // (5 min and low energy) or paperwork.
        assertEquals(texts.take(2), texts.filter { views[0].matches(it) })
        assertEquals(texts.slice(1..2), texts.filter { views[1].matches(it) })
        assertTrue(TagDefinitionParser.parseNote(lines).problems.isEmpty())
        assertEquals(listOf(listOf("#kurz", "#energie/niedrig"), listOf("#papierkram")), SavedViews.alternativesOf(lines[1]))
    }

    @Test
    fun savedViewLineRoundTrips() {
        val line = SavedViews.line("Leicht: heute", listOf(listOf("#kurz", "#energie/niedrig"), emptyList(), listOf("#papierkram")))
        assertEquals("- Leicht heute: #kurz, #energie/niedrig | #papierkram", line)
        val view = SavedViews.parse(listOf(line), definitions, "folder").single()
        assertEquals("Leicht heute", view.name)
        assertEquals(2, view.alternatives.size)
    }

    @Test
    fun duplicatingAGroupCopiesItsKeywords() {
        val lines = listOf("# Tags", "## ⏱ Aufwand", "- 5 Min: #5min \"kurz mal\"", "", "Freier Text")
        val note = TagDefinitionParser.parseNote(lines)
        val copy = TagNoteEdits.duplicateGroup(lines, note.editableGroups.first(), "⏱ Aufwand (Kopie)")
        assertEquals(lines + listOf("", "## ⏱ Aufwand (Kopie)", "- 5 Min: #5min \"kurz mal\""), copy)
    }
}
