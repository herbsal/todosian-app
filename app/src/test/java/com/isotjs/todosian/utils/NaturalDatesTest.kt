package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class NaturalDatesTest {
    // Friday, 20 November 2026, 10:15
    private val now = LocalDateTime.of(2026, 11, 20, 10, 15)

    private fun check(input: String, text: String, date: LocalDate, time: LocalTime?) {
        assertEquals(DateMatch(text, date, time), NaturalDates.find(input, now))
    }

    @Test
    fun tomorrowWithTime() {
        check("morgen 17 Uhr Käse kaufen", "Käse kaufen", LocalDate.of(2026, 11, 21), LocalTime.of(17, 0))
        check("Käse kaufen morgen um 17:30", "Käse kaufen", LocalDate.of(2026, 11, 21), LocalTime.of(17, 30))
        check("Morgen um 9 Elternbrief", "Elternbrief", LocalDate.of(2026, 11, 21), LocalTime.of(9, 0))
    }

    @Test
    fun dayParts() {
        check("morgen früh Brotdosen spülen", "Brotdosen spülen", LocalDate.of(2026, 11, 21), LocalTime.of(8, 0))
        check("heute Abend Müll rausbringen", "Müll rausbringen", LocalDate.of(2026, 11, 20), LocalTime.of(19, 0))
    }

    @Test
    fun weekdaysAndDates() {
        check("Zahnarzt anrufen am Montag", "Zahnarzt anrufen", LocalDate.of(2026, 11, 23), null)
        check("Freitag Laternenfest", "Laternenfest", LocalDate.of(2026, 11, 27), null)
        check("Impfpass suchen bis 3.12.", "Impfpass suchen bis", LocalDate.of(2026, 12, 3), null)
        check("Geschenk 15.1. kaufen", "Geschenk kaufen", LocalDate.of(2027, 1, 15), null)
        check("Steuer 2026-12-31", "Steuer", LocalDate.of(2026, 12, 31), null)
    }

    @Test
    fun relative() {
        check("in 2 Stunden Wäsche aufhängen", "Wäsche aufhängen", LocalDate.of(2026, 11, 20), LocalTime.of(12, 15))
        check("in 3 Tagen Rezept abholen", "Rezept abholen", LocalDate.of(2026, 11, 23), null)
        check("nächste Woche Reifen wechseln", "Reifen wechseln", LocalDate.of(2026, 11, 23), null)
    }

    @Test
    fun timeOnlyIsTodayOrTomorrow() {
        check("um 18 Uhr Training", "Training", LocalDate.of(2026, 11, 20), LocalTime.of(18, 0))
        check("7 Uhr Brötchen", "Brötchen", LocalDate.of(2026, 11, 21), LocalTime.of(7, 0))
    }

    @Test
    fun english() {
        check("tomorrow 5 pm buy cheese", "buy cheese", LocalDate.of(2026, 11, 21), LocalTime.of(17, 0))
        check("call dentist on monday at 9:30", "call dentist", LocalDate.of(2026, 11, 23), LocalTime.of(9, 30))
    }

    @Test
    fun nothingToFind() {
        assertNull(NaturalDates.find("Käse kaufen #5min", now))
        assertNull(NaturalDates.find("Guten Morgen sagen", now))
        assertNull(NaturalDates.find("morgen", now))
    }
}
