package com.isotjs.todosian.data.conflict

import com.isotjs.todosian.data.index.NoteIndexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictMergeTest {
    private fun hashes(lines: List<String>) = LongArray(lines.size) { NoteIndexer.lineHash(lines[it]) }

    private fun merge(base: List<String>?, original: List<String>, copy: List<String>) =
        ConflictMerge.merge(base?.let(::hashes), original, copy)

    private val base = listOf("# Einkauf", "- [ ] Milch", "- [ ] Brot", "- [ ] Käse", "Notiz")

    @Test
    fun changesOnDifferentLinesAreBothTaken() {
        val original = listOf("# Einkauf", "- [x] Milch ✅ 2026-11-21", "- [ ] Brot", "- [ ] Käse", "Notiz")
        val copy = listOf("# Einkauf", "- [ ] Milch", "- [ ] Brot", "- [ ] Käse", "- [ ] Eier", "Notiz geändert")
        val result = merge(base, original, copy)
        assertTrue(result.isClean)
        assertEquals(
            listOf("# Einkauf", "- [x] Milch ✅ 2026-11-21", "- [ ] Brot", "- [ ] Käse", "- [ ] Eier", "Notiz geändert"),
            result.render(emptyMap()),
        )
    }

    @Test
    fun deletionOnOneSideIsTakenAndTheSameChangeOnce() {
        val original = listOf("# Einkauf", "- [ ] Milch", "- [ ] Käse", "Notiz!")
        val copy = listOf("# Einkauf", "- [ ] Milch", "- [ ] Brot", "- [ ] Käse", "Notiz!")
        assertEquals(listOf("# Einkauf", "- [ ] Milch", "- [ ] Käse", "Notiz!"), merge(base, original, copy).render(emptyMap()))
    }

    @Test
    fun aTaskChangedOnBothSidesIsMergedByField() {
        // Ticked on the phone, text changed on the computer: new text, ticked.
        val original = listOf("# Einkauf", "- [x] Milch 📅 2026-11-22 ✅ 2026-11-21", "- [ ] Brot", "- [ ] Käse", "Notiz")
        val b = listOf("# Einkauf", "- [ ] Milch 📅 2026-11-22", "- [ ] Brot", "- [ ] Käse", "Notiz")
        val copy = listOf("# Einkauf", "- [ ] Milch 2 l 📅 2026-11-22", "- [ ] Brot", "- [ ] Käse", "Notiz")
        val result = merge(b, original, copy)
        assertTrue(result.isClean)
        assertEquals("- [x] Milch 2 l 📅 2026-11-22 ✅ 2026-11-21", result.render(emptyMap())!![1])
        assertEquals(1, result.places(MergeSource.FIELDS))
    }

    @Test
    fun theSameFieldChangedDifferentlyIsAContradiction() {
        val original = listOf("# Einkauf", "- [ ] Hafermilch", "- [ ] Brot", "- [ ] Käse", "Notiz")
        val copy = listOf("# Einkauf", "- [ ] Sojamilch", "- [ ] Brot", "- [ ] Käse", "Notiz")
        val result = merge(base, original, copy)
        assertFalse(result.isClean)
        val question = result.contradictions.single()
        assertEquals(listOf("- [ ] Hafermilch") to listOf("- [ ] Sojamilch"), question.original to question.copy)
        assertNull(result.render(emptyMap()))
        assertEquals("- [ ] Sojamilch", result.render(mapOf(question.id to MergeChoice.COPY))!![1])
        assertEquals(
            listOf("- [ ] Hafermilch", "- [ ] Sojamilch"),
            result.render(mapOf(question.id to MergeChoice.BOTH))!!.subList(1, 3),
        )
    }

    @Test
    fun deletedOnOneSideAndChangedOnTheOtherIsAsked() {
        val original = listOf("# Einkauf", "- [ ] Brot", "- [ ] Käse", "Notiz")
        val copy = listOf("# Einkauf", "- [x] Milch", "- [ ] Brot", "- [ ] Käse", "Notiz")
        val question = merge(base, original, copy).contradictions.single()
        assertEquals(emptyList<String>() to listOf("- [x] Milch"), question.original to question.copy)
    }

    @Test
    fun newTasksAtTheSamePlaceOnBothSidesAreBothKept() {
        val original = base.take(4) + "- [ ] Eier" + "Notiz"
        val copy = base.take(4) + "- [ ] Butter" + "Notiz"
        val result = merge(base, original, copy)
        assertTrue(result.isClean)
        assertEquals(base.take(4) + listOf("- [ ] Eier", "- [ ] Butter", "Notiz"), result.render(emptyMap()))
    }

    @Test
    fun aRecurringTaskTickedOnOneSideBringsItsNextInstance() {
        val b = listOf("- [ ] Müll 🔁 every week 📅 2026-11-21", "- [ ] Rest")
        val original = listOf("- [ ] Müll 🔁 every week 📅 2026-11-28", "- [x] Müll 🔁 every week 📅 2026-11-21 ✅ 2026-11-21", "- [ ] Rest")
        val copy = listOf("- [ ] Müll 🔁 every week 📅 2026-11-21", "- [ ] Rest geändert")
        assertEquals(
            listOf("- [ ] Müll 🔁 every week 📅 2026-11-28", "- [x] Müll 🔁 every week 📅 2026-11-21 ✅ 2026-11-21", "- [ ] Rest geändert"),
            merge(b, original, copy).render(emptyMap()),
        )
    }

    @Test
    fun withoutABaseEveryDifferenceIsAskedAndNothingIsClean() {
        val original = listOf("A", "- [x] B", "C")
        val copy = listOf("A", "- [ ] B", "C", "D")
        val result = merge(null, original, copy)
        assertFalse(result.hasBase)
        assertFalse(result.isClean)
        val question = result.contradictions.single()
        assertEquals(listOf("- [x] B") to listOf("- [ ] B"), question.original to question.copy)
        assertEquals(listOf("A", "- [x] B", "- [ ] B", "C", "D"), result.render(mapOf(question.id to MergeChoice.BOTH)))
    }

    @Test
    fun aBaseEqualToOneSideIsNotTrusted() {
        val original = listOf("A", "B2")
        val copy = listOf("A", "B")
        // The index only knew the copy's state: it would silently undo the original's change.
        assertFalse(merge(copy, original, copy).hasBase)
    }

    @Test
    fun fieldMergeNeedsTheBaseExactly() {
        val result = ConflictMerge.mergeFields(
            NoteIndexer.lineHash("- [ ] Etwas anderes"), "- [x] Milch", "- [ ] Milch 2 l",
            com.isotjs.todosian.utils.TaskMarkers.Default, NoteIndexer::lineHash,
        )
        assertNull(result)
    }
}
