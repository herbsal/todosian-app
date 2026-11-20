package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListRecognitionTest {
    private val lists = listOf("Inbox", "Haushalt", "Einkauf", "Einkauf Baumarkt", "Kinder")

    private fun name(text: String) = ListRecognition.find(text, lists)?.name

    @Test
    fun withAtOrSignalWord() {
        assertEquals("Haushalt", name("Fenster putzen @Haushalt"))
        assertEquals("Haushalt", name("Fenster putzen in Haushalt"))
        assertEquals("Einkauf", name("Milch auf die Einkaufsliste"))
        assertEquals("Einkauf", name("Milch auf die Einkauf Liste"))
        assertEquals("Kinder", name("Liste Kinder Turnbeutel waschen"))
        assertEquals("Einkauf Baumarkt", name("Schrauben zu Einkauf Baumarkt"))
    }

    @Test
    fun withoutSignalWordNothing() {
        assertNull(name("Haushalt Plan machen"))
        assertNull(name("Kinderarzt anrufen"))
        assertNull(name("Fenster putzen"))
    }

    @Test
    fun removeTakesOutTheWords() {
        val text = "Milch auf die Einkaufsliste, morgen"
        val hit = ListRecognition.find(text, lists)!!
        assertEquals("Milch, morgen", ListRecognition.remove(text, hit))
        val at = "Fenster putzen @Haushalt"
        assertEquals("Fenster putzen", ListRecognition.remove(at, ListRecognition.find(at, lists)!!))
    }
}
