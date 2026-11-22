package com.isotjs.todosian.utils.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTextGuardTest {

    @Test
    fun `plain text has nothing to protect`() {
        val masked = AiTextGuard.mask("ähm ja also Nudeltopf, den Nudeltopf aufräumen")
        assertEquals("ähm ja also Nudeltopf, den Nudeltopf aufräumen", masked.text)
        assertTrue(masked.tokens.isEmpty())
        assertEquals("Nudeltopf aufräumen", AiTextGuard.restore("Nudeltopf aufräumen", masked))
    }

    @Test
    fun `tags fields and list words become placeholders`() {
        val masked = AiTextGuard.mask("also Kinderarzt anrufen #kinder @Familie ⏰ 2026-11-23 17:00 📅 2026-11-23")
        assertEquals("also Kinderarzt anrufen ⟦1⟧ ⟦2⟧ ⟦3⟧ ⟦4⟧", masked.text)
        assertEquals(listOf("#kinder", "@Familie", "⏰ 2026-11-23 17:00", "📅 2026-11-23"), masked.tokens)
        assertEquals(
            "Kinderarzt anrufen #kinder @Familie ⏰ 2026-11-23 17:00 📅 2026-11-23",
            AiTextGuard.restore("Kinderarzt anrufen ⟦1⟧ ⟦2⟧ ⟦3⟧ ⟦4⟧", masked),
        )
    }

    @Test
    fun `recurrence with free text stays whole`() {
        val masked = AiTextGuard.mask("Müll rausbringen 🔁 every week 📅 2026-11-24")
        assertEquals(listOf("🔁 every week", "📅 2026-11-24"), masked.tokens)
    }

    @Test
    fun `fields moved to the front go back to the end in their order`() {
        val masked = AiTextGuard.mask("äh Kinderarzt anrufen #kinder 📅 2026-11-23")
        assertEquals(
            "Kinderarzt anrufen #kinder 📅 2026-11-23",
            AiTextGuard.restore("⟦2⟧ ⟦1⟧ Kinderarzt anrufen", masked),
        )
    }

    @Test
    fun `a tag in the middle keeps its place`() {
        val masked = AiTextGuard.mask("also Geschenk für #lena besorgen")
        assertEquals("Geschenk für #lena besorgen", AiTextGuard.restore("Geschenk für ⟦1⟧ besorgen", masked))
    }

    @Test
    fun `models that only see the words get the trailing parts back`() {
        val masked = AiTextGuard.mask("ähm also Geschenk für #lena besorgen #familie 📅 2026-11-30")
        assertEquals("ähm also Geschenk für ⟦1⟧ besorgen", masked.textWithoutTrailing)
        assertEquals(
            "Geschenk für #lena besorgen #familie 📅 2026-11-30",
            AiTextGuard.restore("Geschenk für ⟦1⟧ besorgen", masked, masked.trailing),
        )
        assertNull(AiTextGuard.restore("Geschenk besorgen", masked, masked.trailing))
    }

    @Test
    fun `models that see no placeholder get all parts back at the end`() {
        val masked = AiTextGuard.mask("Alex #person/alex nudeltopf nudeltopf aufgefunden und ähm draußen saubermachen")
        assertEquals("Alex nudeltopf nudeltopf aufgefunden und ähm draußen saubermachen", masked.textWithout(masked.allTokens))
        assertEquals(
            "Nudeltopf draußen saubermachen #person/alex",
            AiTextGuard.restore("Nudeltopf draußen saubermachen.", masked, masked.allTokens),
        )
    }

    @Test
    fun `extra ranges are protected like tags`() {
        val text = "ähm Nudeltopf aufräumen Energie niedrig"
        val start = text.indexOf("Energie")
        val masked = AiTextGuard.mask(text, extra = listOf(start until text.length))
        assertEquals(listOf("Energie niedrig"), masked.tokens)
        assertEquals("ähm Nudeltopf aufräumen ⟦1⟧", masked.text)
        assertEquals("Nudeltopf aufräumen Energie niedrig", AiTextGuard.restore("Nudeltopf aufräumen", masked, masked.allTokens))
    }

    @Test
    fun `rejection says why`() {
        val masked = AiTextGuard.mask("Kinderarzt anrufen #kinder")
        assertEquals(
            AiTextGuard.Restored.Rejected(AiTextGuard.Rejection.PLACEHOLDERS),
            AiTextGuard.check("Kinderarzt anrufen", masked),
        )
    }

    @Test
    fun `missing placeholder is rejected`() {
        val masked = AiTextGuard.mask("Kinderarzt anrufen #kinder 📅 2026-11-23")
        assertNull(AiTextGuard.restore("Kinderarzt anrufen ⟦1⟧", masked))
    }

    @Test
    fun `doubled placeholder is rejected`() {
        val masked = AiTextGuard.mask("Kinderarzt anrufen #kinder")
        assertNull(AiTextGuard.restore("Kinderarzt ⟦1⟧ anrufen ⟦1⟧", masked))
    }

    @Test
    fun `made up placeholder is rejected`() {
        val masked = AiTextGuard.mask("Kinderarzt anrufen #kinder")
        assertNull(AiTextGuard.restore("Kinderarzt anrufen ⟦1⟧ ⟦2⟧", masked))
    }

    @Test
    fun `changed placeholder text is rejected`() {
        val masked = AiTextGuard.mask("Kinderarzt anrufen #kinder")
        assertNull(AiTextGuard.restore("Kinderarzt anrufen [1]", masked))
    }

    @Test
    fun `line breaks and empty answers are rejected`() {
        val masked = AiTextGuard.mask("ähm Nudeltopf aufräumen")
        assertNull(AiTextGuard.restore("Nudeltopf\naufräumen", masked))
        assertNull(AiTextGuard.restore("   ", masked))
    }

    @Test
    fun `answers with only placeholders are rejected`() {
        val masked = AiTextGuard.mask("ähm Nudeltopf aufräumen #haushalt")
        assertNull(AiTextGuard.restore("⟦1⟧", masked))
    }

    @Test
    fun `clearly longer answers are rejected`() {
        val masked = AiTextGuard.mask("Nudeltopf aufräumen")
        assertNull(AiTextGuard.restore("Den großen Nudeltopf in der Küche gründlich aufräumen und spülen", masked))
    }

    @Test
    fun `surrounding quotes are dropped`() {
        val masked = AiTextGuard.mask("ähm Nudeltopf aufräumen")
        assertEquals("Nudeltopf aufräumen", AiTextGuard.restore("„Nudeltopf aufräumen“", masked))
    }

    @Test
    fun `links and code are protected`() {
        val masked = AiTextGuard.mask("also [[Arzt Lena]] lesen und `npm i` und [Seite](https://example.com) und https://a.de/x")
        assertEquals(listOf("[[Arzt Lena]]", "`npm i`", "[Seite](https://example.com)", "https://a.de/x"), masked.tokens)
    }

    @Test
    fun `custom markers are protected`() {
        val markers = com.isotjs.todosian.utils.TaskMarkers(due = "due::")
        val masked = AiTextGuard.mask("ähm Steuer machen due:: 2026-12-01", markers)
        assertEquals(listOf("due:: 2026-12-01"), masked.tokens)
    }

    @Test
    fun `heading hash and numbers are not tags`() {
        val masked = AiTextGuard.mask("Seite 3 lesen, C# lernen")
        assertTrue(masked.tokens.isEmpty())
    }

    @Test
    fun `unchanged ignores spaces`() {
        assertTrue(AiTextGuard.isUnchanged("Nudeltopf  aufräumen ", "Nudeltopf aufräumen"))
        assertFalse(AiTextGuard.isUnchanged("ähm Nudeltopf aufräumen", "Nudeltopf aufräumen"))
    }

    @Test
    fun `prompt answer takes the first line without prefix`() {
        assertEquals("Nudeltopf aufräumen", AiCleanupPrompt.answerOf(" Aufgabe: Nudeltopf aufräumen\n\nText: x"))
        assertTrue(AiCleanupPrompt.build("ähm ⟦1⟧ test").endsWith("Text: ähm ⟦1⟧ test\nAufgabe:"))
    }
}
