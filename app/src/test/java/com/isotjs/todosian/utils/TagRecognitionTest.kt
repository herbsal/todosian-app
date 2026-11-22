package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TagRecognitionTest {
    private val definitions = TagDefinitionParser.parse(
        listOf(
            "# Tags",
            "## ⏱ Aufwand",
            "- 5 Min: #5min, #kurz",
            "- 30 Min: #30min",
            "- 2 Std: #2h",
            "## 🔋 Energie",
            "- Niedrig: #energie/niedrig",
            "- Hoch: #energie/hoch",
            "## Person",
            "- Alex: #person/alex",
            "- Sam: #person/sam",
        ),
    )

    private fun labels(text: String) = TagRecognition.find(text, definitions).map { it.term.label }

    @Test
    fun findsPeopleEffortAndEnergy() {
        assertEquals(listOf("Alex", "5 Min", "Niedrig"), labels("Alex Müll rausbringen 5 Minuten wenig Energie"))
        assertEquals(listOf("2 Std", "Hoch"), labels("Garage aufräumen 2 Std. Energie hoch"))
        assertEquals(listOf("30 Min"), labels("Steuer halbe Stunde"))
        assertEquals(listOf("Alex", "Sam"), labels("Alex und Sam Kino"))
    }

    @Test
    fun leavesAloneWhatIsNoTag() {
        assertEquals(emptyList<String>(), labels("Tisch niedrig stellen"))
        assertEquals(emptyList<String>(), labels("in 2 Stunden Wäsche"))
        assertEquals(emptyList<String>(), labels("Alexander anrufen"))
        // Already tagged.
        assertEquals(emptyList<String>(), labels("Müll #5min"))
    }

    @Test
    fun onlyOnePerExclusiveGroup() {
        assertEquals(listOf("5 Min"), labels("5 Minuten oder 2 Stunden"))
    }

    @Test
    fun applyRemovesWordsButKeepsNames() {
        val text = "Alex Müll rausbringen 5 Minuten, wenig Energie"
        val hits = TagRecognition.find(text, definitions)
        assertEquals("Alex Müll rausbringen #person/alex #5min #energie/niedrig", TagRecognition.apply(text, hits, removeWords = true))
        assertEquals(
            "Alex Müll rausbringen 5 Minuten, wenig Energie #person/alex #5min #energie/niedrig",
            TagRecognition.apply(text, hits, removeWords = false),
        )
    }

    private fun settle(text: String, cursor: Int = text.length, removeWords: Boolean = true) =
        TagRecognition.settle(text, cursor, definitions, removeWords = removeWords)

    @Test
    fun settleReplacesFinishedWordsOnly() {
        // Still typing "5 Minuten": nothing yet.
        assertEquals(null, settle("Nudeltopf 5 Minuten"))
        val done = settle("Nudeltopf 5 Minuten ")!!
        assertEquals("Nudeltopf #5min ", done.text)
        assertEquals(done.text.length, done.cursor)
        assertEquals(mapOf("#5min" to "5 Minuten"), done.replaced)
        // Dictated in one go: words at the very end wait for the next character.
        assertEquals("Müll #5min, wenig Energie", settle("Müll 5 Minuten, wenig Energie")!!.text)
        assertEquals("Müll #5min, #energie/niedrig ", settle("Müll 5 Minuten, wenig Energie ")!!.text)
    }

    @Test
    fun settleKeepsPeoplesNamesAndTheCursor() {
        val done = settle("Alex anrufen", cursor = 5)!!
        assertEquals("Alex #person/alex anrufen", done.text)
        assertEquals(5 + " #person/alex".length, done.cursor)
        // Cursor before the words: it stays where it is.
        val early = settle("Müll 5 Minuten jetzt", cursor = 2)!!
        assertEquals("Müll #5min jetzt", early.text)
        assertEquals(2, early.cursor)
        // Cursor on the words: they may still change.
        assertEquals(null, settle("Müll 5 Minuten jetzt", cursor = 9))
    }

    @Test
    fun settleWithoutRemovingWordsAddsTheTag() {
        assertEquals("Müll 5 Minuten #5min ", settle("Müll 5 Minuten ", removeWords = false)!!.text)
    }

    @Test
    fun settleNewWordsWinInExclusiveGroups() {
        assertEquals("Garage #2h ", settle("Garage #5min 2 Stunden ")!!.text)
    }

    @Test
    fun settleSkipsIgnoredTags() {
        assertEquals(null, TagRecognition.settle("Müll 5 Minuten ", 15, definitions, ignoredTags = setOf("#5min")))
    }

    @Test
    fun unsettleBringsTheWordsBack() {
        assertEquals("Nudeltopf 5 Minuten ", TagRecognition.unsettle("Nudeltopf #5min ", "#5min", "5 Minuten"))
        assertEquals("Alex anrufen", TagRecognition.unsettle("Alex #person/alex anrufen", "#person/alex", "Alex"))
        assertEquals("Müll", TagRecognition.unsettle("Müll #5min", "#5min", null))
    }

    @Test
    fun noDefinitionsNoHits() {
        assertEquals(emptyList<TagHit>(), TagRecognition.find("Alex 5 Minuten", TagDefinitions.EMPTY))
    }

    @Test
    fun ownWordsSetTheTag() {
        val own = TagDefinitionParser.parse(
            listOf("## ⏱ Aufwand", "- 30 Min: #30min \"dreißig Minuten\", \"kurz mal\"", "## 👤 Person", "- Alex: #person/alex \"Papa\""),
        )
        val text = "Papa Rasen mähen dreißig Minuten"
        val hits = TagRecognition.find(text, own)
        assertEquals(listOf("Alex", "30 Min"), hits.map { it.term.label })
        assertEquals("Papa Rasen mähen #person/alex #30min", TagRecognition.apply(text, hits, removeWords = true))
        assertEquals(listOf("30 Min"), TagRecognition.find("Kurz mal aufräumen", own).map { it.term.label })
    }
}
