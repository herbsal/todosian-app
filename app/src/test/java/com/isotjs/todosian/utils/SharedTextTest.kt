package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextTest {
    @Test
    fun browserKeepsTitleAndLink() {
        assertEquals(
            "Kinderarzt Öffnungszeiten https://example.org/praxis",
            SharedText.toTaskText("Kinderarzt Öffnungszeiten", "https://example.org/praxis"),
        )
    }

    @Test
    fun messengerTextBecomesOneLine() {
        assertEquals("Bitte Turnbeutel einpacken und Elternbrief unterschreiben", SharedText.toTaskText(null, "Bitte Turnbeutel einpacken\n\nund Elternbrief unterschreiben\n"))
    }

    @Test
    fun listMarkerIsDropped() {
        assertEquals("Milch kaufen", SharedText.toTaskText(null, "- [ ] Milch kaufen"))
    }

    @Test
    fun subjectAlreadyInTextIsNotRepeated() {
        assertEquals("Schau mal: Laternenfest https://x.de", SharedText.toTaskText("Laternenfest", "Schau mal: Laternenfest https://x.de"))
    }

    @Test
    fun emptyShareGivesNothing() {
        assertNull(SharedText.toTaskText(" ", "\n"))
    }
}
