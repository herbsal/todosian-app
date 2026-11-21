package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObsidianMarkdownTest {
    private val files = mapOf("Bild.png" to "content://vault/Bild.png", "Anhang/Röntgen.jpg" to "content://vault/R.jpg")
    private fun prepare(text: String) = ObsidianMarkdown.prepare(text) { files[it] }

    @Test
    fun embedsImagesFromTheVaultAndNeverFromTheWeb() {
        assertEquals("![Bild.png](content://vault/Bild.png)", prepare("![[Bild.png|300]]"))
        assertEquals("![Arzt](content://vault/R.jpg)", prepare("![Arzt](Anhang/R%C3%B6ntgen.jpg)"))
        assertEquals("*Fehlt.png*", prepare("![[Fehlt.png]]"))
        assertEquals("*Logo*", prepare("![Logo](https://example.org/logo.png)"))
    }

    @Test
    fun wikilinksBecomeNoteLinks() {
        assertEquals("Siehe [Kinder](todosian-note:Familie%2FKinder).", prepare("Siehe [[Familie/Kinder|Kinder]]."))
        assertEquals("[Arzt › Termine](todosian-note:Arzt)", prepare("[[Arzt#Termine]]"))
        assertEquals("[Notiz](todosian-note:Notiz)", prepare("![[Notiz]]"))
        assertEquals("Familie/Kinder", ObsidianMarkdown.noteOfLink("todosian-note:Familie%2FKinder"))
        assertNull(ObsidianMarkdown.noteOfLink("https://example.org"))
    }

    @Test
    fun calloutsBecomeQuotesAndFrontMatterAndCommentsGo() {
        val text = "---\ntags: [a]\n---\n# Titel\n> [!tip]- Merken\n> Inhalt\n> [!note]\nText %%geheim%% weiter"
        assertEquals("# Titel\n> **Merken**  \n> Inhalt  \n> **Note**  \nText  weiter", prepare(text))
    }

    @Test
    fun leavesTasksAlone() {
        val text = "- [ ] Müll 📅 2026-11-21"
        assertEquals(text, prepare(text))
    }

    @Test
    fun aLineAfterATaskIsItsOwnParagraphAndLineBreaksStay() {
        val text = "Bla\nVhjjd\nNnko\n\n- [ ] Impfpass #30min #person/partner 📅 2026-11-25\nHhkbgjm\n  - [ ] Unteraufgabe\n- [ ] Zweite"
        assertEquals(
            "Bla  \nVhjjd  \nNnko\n\n- [ ] Impfpass `#30min` `#person/partner` 📅 2026-11-25\n\nHhkbgjm  \n  - [ ] Unteraufgabe  \n- [ ] Zweite",
            prepare(text),
        )
    }

    @Test
    fun codeHeadingsAndLinksKeepTheirHash() {
        val text = "# Titel\n```\n#nichtTag\n```\nSiehe [x](#abschnitt) und `#code` und #2026"
        assertEquals("# Titel\n```\n#nichtTag\n```\nSiehe [x](#abschnitt) und `#code` und #2026", prepare(text))
    }
}
