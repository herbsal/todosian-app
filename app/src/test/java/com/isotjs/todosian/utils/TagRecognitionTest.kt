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
