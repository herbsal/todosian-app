package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TagDisplayTest {
    private val definitions = TagDefinitionParser.parse(
        listOf(
            "# Schlagwörter",
            "## Aufwand",
            "- 5 Min: #5min, #kurz",
            "## 🧠 Energie",
            "- Niedrig: #energie/niedrig",
            "## Person",
            "- Michael: #person/michael",
        ),
    )

    @Test
    fun definedTagsBecomeBadges() {
        val result = TagDisplay.split("Müll rausbringen #kurz #person/michael", definitions, TagDisplaySettings())
        assertEquals("Müll rausbringen", result.title)
        assertEquals(listOf(TagBadge("⏱", "5 Min"), TagBadge("👤", "Michael", isPerson = true)), result.badges)
    }

    @Test
    fun headingIconWinsAndNameIsWithoutIt() {
        val group = definitions.groups.first { it.name == "Energie" }
        assertEquals("🧠", group.icon)
        assertEquals(TagBadge("🧠", "Niedrig"), TagDisplay.split("x #energie/niedrig", definitions, TagDisplaySettings()).badges.single())
    }

    @Test
    fun hiddenTextAndOtherTags() {
        val person = definitions.groups.first { it.name == "Person" }
        val effort = definitions.groups.first { it.name == "Aufwand" }
        val settings = TagDisplaySettings()
            .withGroup(person, TagDisplayMode.HIDDEN)
            .withGroup(effort, TagDisplayMode.TEXT)
        val result = TagDisplay.split("Bild #person/michael #5min #urlaub", definitions, settings)
        assertEquals("Bild #5min #urlaub", result.title)
        assertEquals(emptyList<TagBadge>(), result.badges)

        val chips = TagDisplay.split("Bild #urlaub", definitions, TagDisplaySettings(otherTags = TagDisplayMode.CHIP))
        assertEquals("Bild", chips.title)
        assertEquals(listOf(TagBadge("🏷", "urlaub")), chips.badges)
    }

    @Test
    fun plainTextForNotifications() {
        assertEquals(
            "Müll rausbringen · ⏱ 5 Min · 🧠 Niedrig",
            TagDisplay.plainText("Müll rausbringen #kurz #energie/niedrig", definitions, TagDisplaySettings()),
        )
        assertEquals("Bild #urlaub", TagDisplay.plainText("Bild #urlaub", definitions, TagDisplaySettings()))
    }

    @Test
    fun settingsRoundTrip() {
        val person = definitions.groups.first { it.name == "Person" }
        val settings = TagDisplaySettings(otherTags = TagDisplayMode.HIDDEN).withGroup(person, TagDisplayMode.TEXT)
        assertEquals(settings, TagDisplaySettings.decode(settings.encode()))
        assertEquals(TagDisplaySettings(), TagDisplaySettings.decode(null))
    }

    @Test
    fun onlyTagsKeepsText() {
        assertEquals("#kurz", TagDisplay.split("#kurz", definitions, TagDisplaySettings()).title)
    }

    @Test
    fun severalPeopleBecomeSeveralPersonBadges() {
        val defs = TagDefinitionParser.parse(listOf("## Person", "- Michael: #person/michael", "- Sam: #person/sam"))
        val result = TagDisplay.split("Elternabend #person/michael #person/sam", defs, TagDisplaySettings())
        assertEquals("Elternabend", result.title)
        assertEquals(listOf(TagBadge("👤", "Michael", true), TagBadge("👤", "Sam", true)), result.badges)
    }
}
