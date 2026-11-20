package com.isotjs.todosian.notifications

import com.isotjs.todosian.data.settings.ReminderRepeat
import com.isotjs.todosian.utils.TagDefinitionParser
import com.isotjs.todosian.utils.TagDefinitionWriter
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagGroup
import com.isotjs.todosian.utils.TagTerm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class ReminderProfileWriterTest {
    private val profile = ReminderProfile(
        "Alex",
        listOf(
            KeywordRules(
                "#energie/niedrig",
                listOf(ProfileRule(0, LocalTime.of(9, 0)), ProfileRule(1, LocalTime.of(18, 0))),
                ReminderRepeat.HOURLY,
            ),
            KeywordRules("#2h", listOf(ProfileRule(2, LocalTime.of(8, 30))), null),
        ),
    )

    @Test
    fun `German profile round-trips`() {
        val lines = ReminderProfileWriter.write(profile, german = true)
        assertEquals(
            listOf(
                "# Alex", "", "## #energie/niedrig", "- 1 Tag vorher 18:00", "- 09:00", "- erneut: stündlich",
                "", "## #2h", "- 2 Tage vorher 08:30",
            ),
            lines,
        )
        assertEquals(profile.keywords.map { it.rules.toSet() to it.repeat }, ReminderProfileParser.parse("Alex", lines).keywords.map { it.rules.toSet() to it.repeat })
    }

    @Test
    fun `English profile round-trips`() {
        val lines = ReminderProfileWriter.write(profile, german = false)
        assertEquals("- 1 day before 18:00", lines[3])
        assertEquals("- repeat: hourly", lines[5])
        assertEquals(profile.keywords.map { it.rules.toSet() to it.repeat }, ReminderProfileParser.parse("Alex", lines).keywords.map { it.rules.toSet() to it.repeat })
    }

    @Test
    fun `keyword note round-trips`() {
        val defs = TagDefinitions(
            listOf(
                TagGroup(null, listOf(TagTerm("Kinder", listOf("#kinder", "#kids")))),
                TagGroup("Aufwand", listOf(TagTerm("5 Minuten", listOf("#5min", "#kurz")), TagTerm("2h", listOf("#2h")))),
            ),
        )
        val lines = TagDefinitionWriter.write(defs, "Schlagwörter")
        assertEquals(listOf("# Schlagwörter", "", "- Kinder: #kinder, #kids", "", "## Aufwand", "- 5 Minuten: #5min, #kurz", "- 2h: #2h"), lines)
        assertEquals(defs, TagDefinitionParser.parse(lines))
    }

    @Test
    fun `tag input is normalized`() {
        assertEquals(listOf("#kurz", "#5min", "#quick", "#energie/niedrig"), TagDefinitionWriter.parseTagInput("kurz, #5min  quick;#Kurz energie/niedrig #"))
    }
}
