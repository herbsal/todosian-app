package com.isotjs.todosian.notifications

import com.isotjs.todosian.data.settings.ReminderRepeat
import com.isotjs.todosian.utils.NoteProblemKind
import com.isotjs.todosian.utils.TagDefinitionParser
import com.isotjs.todosian.utils.TagNoteEdits
import com.isotjs.todosian.utils.TagTerm
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteEditsTest {
    private val tagNote = listOf(
        "# Schlagwörter",
        "Freier Text aus Obsidian bleibt.",
        "",
        "## Aufwand",
        "- 5 Minuten: #5min, #kurz",
        "- 2 Stunden: 2h",
        "",
        "## Energie",
        "- Niedrig: #energie/niedrig, 5min",
        "> Kommentar",
    )

    @Test
    fun keywordNote_reportsWrongSyntaxWithLine() {
        val note = TagDefinitionParser.parseNote(tagNote)
        assertEquals(listOf(5 to NoteProblemKind.NO_TAG, 8 to NoteProblemKind.UNKNOWN_TEXT), note.problems.map { it.line to it.kind })
        assertEquals(listOf("Aufwand", "Energie"), note.editableGroups.map { it.name })
        assertEquals(listOf("5 Minuten"), note.definitions.groups[0].terms.map { it.label })
    }

    @Test
    fun keywordNote_editsOnlyTheAffectedLines() {
        val note = TagDefinitionParser.parseNote(tagNote)
        val effort = note.editableGroups[0]
        val added = TagNoteEdits.addTerm(tagNote, note, effort, TagTerm("1 Stunde", listOf("#1h")))!!
        assertEquals("- 1 Stunde: #1h", added[5])
        assertEquals(tagNote.size + 1, added.size)
        assertEquals(tagNote.take(5), added.take(5))
        assertEquals(tagNote.drop(5), added.drop(6))

        val replaced = TagNoteEdits.replaceTerm(tagNote, note, effort.terms[0], TagTerm("Kurz", listOf("#kurz")))!!
        assertEquals(tagNote.toMutableList().apply { set(4, "- Kurz: #kurz") }, replaced)

        val removedGroup = TagNoteEdits.removeGroup(tagNote, note, note.editableGroups[1])!!
        assertEquals(tagNote.filterIndexed { i, _ -> i != 7 && i != 8 }, removedGroup)

        val renamed = TagNoteEdits.renameGroup(tagNote, note, effort, "Dauer")!!
        assertEquals("## Dauer", renamed[3])
    }

    @Test
    fun keywordNote_findsMovedLineAndRefusesMissingLine() {
        val note = TagDefinitionParser.parseNote(tagNote)
        val shifted = listOf("Neu oben") + tagNote
        val removed = TagNoteEdits.removeTerm(shifted, note, note.editableGroups[0].terms[0])!!
        assertEquals(shifted.filterIndexed { i, _ -> i != 5 }, removed)
        val changed = tagNote.toMutableList().apply { set(4, "- anders: #x") }
        assertNull(TagNoteEdits.removeTerm(changed, note, note.editableGroups[0].terms[0]))
    }

    @Test
    fun keywordNote_firstUngroupedKeywordGoesBelowTitle() {
        val lines = listOf("# Schlagwörter", "", "## Aufwand", "- Kurz: #kurz")
        val note = TagDefinitionParser.parseNote(lines)
        val added = TagNoteEdits.addTerm(lines, note, null, TagTerm("Wichtig", listOf("#wichtig")))!!
        assertEquals(listOf("# Schlagwörter", "- Wichtig: #wichtig", "", "## Aufwand", "- Kurz: #kurz"), added)
        val empty = TagDefinitionParser.parseNote(emptyList())
        assertEquals(
            listOf("# Keywords", "- X: #x"),
            TagNoteEdits.addTerm(listOf("# Keywords"), empty, null, TagTerm("X", listOf("#x"))),
        )
        assertEquals(listOf("## Gruppe"), TagNoteEdits.addGroup(emptyList(), "Gruppe"))
        assertEquals(lines + "" + "## Neu", TagNoteEdits.addGroup(lines, "Neu"))
    }

    @Test
    fun profileNote_reportsProblemsAndEditsLines() {
        val lines = listOf(
            "# Abends",
            "Notiz für mich.",
            "## #energie/niedrig",
            "- 1 Tag vorher 18:00",
            "- irgendwann",
            "- erneut: manchmal",
            "## Energie",
            "- 09:00",
        )
        val note = ReminderProfileParser.parseNote("Abends", lines)
        assertEquals(
            listOf(4 to NoteProblemKind.NO_TIME, 5 to NoteProblemKind.BAD_REPEAT, 6 to NoteProblemKind.BAD_HEADING),
            note.problems.map { it.line to it.kind },
        )
        val keyword = note.keywords.single()
        assertEquals(listOf(ProfileRule(1, LocalTime.of(18, 0))), note.profile.keywords.single().rules)

        val withRule = ProfileNoteEdits.addRule(lines, note, keyword, ProfileRule(0, LocalTime.of(9, 0)), german = true)!!
        assertEquals("- 09:00", withRule[4])
        assertEquals(lines.size + 1, withRule.size)

        val withRepeat = ProfileNoteEdits.setRepeat(lines, note, keyword, ReminderRepeat.HOURLY, german = true)!!
        assertEquals("- erneut: stündlich", withRepeat[4])

        val again = ReminderProfileParser.parseNote("Abends", withRepeat)
        val cleared = ProfileNoteEdits.setRepeat(withRepeat, again, again.keywords.single(), null, german = true)!!
        assertEquals(lines, cleared)

        val removed = ProfileNoteEdits.removeKeyword(lines, note, keyword)!!
        assertEquals(lines.filterIndexed { i, _ -> i != 2 && i != 3 }, removed)
    }

    @Test
    fun profileNote_englishTextAndNewKeyword() {
        assertEquals("2 days before 07:30", ProfileNoteEdits.describe(ProfileRule(2, LocalTime.of(7, 30)), german = false))
        assertEquals("repeat: daily", ProfileNoteEdits.repeatText(ReminderRepeat.DAILY, german = false))
        assertEquals(listOf("# P", "", "## #x"), ProfileNoteEdits.addKeyword(listOf("# P"), "#x"))
    }

    @Test
    fun tagInput_isNormalized() {
        assertEquals(
            listOf("#kurz", "#5min", "#quick", "#energie/niedrig"),
            TagNoteEdits.parseTagInput("kurz, #5min  quick;#Kurz energie/niedrig # #5"),
        )
    }
}
