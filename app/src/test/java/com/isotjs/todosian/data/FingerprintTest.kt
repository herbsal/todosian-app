package com.isotjs.todosian.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FingerprintTest {
    private val base = listOf("Inbox" to "100:20", "Projects/Move" to "200:35")

    @Test
    fun `same notes in another order give the same fingerprint`() {
        assertEquals(fingerprintOf(base), fingerprintOf(base.reversed()))
    }

    @Test
    fun `a written note changes the fingerprint`() {
        assertNotEquals(fingerprintOf(base), fingerprintOf(listOf("Inbox" to "101:20", base[1])))
    }

    @Test
    fun `an added, removed or renamed note changes the fingerprint`() {
        assertNotEquals(fingerprintOf(base), fingerprintOf(base + ("New" to "300:1")))
        assertNotEquals(fingerprintOf(base), fingerprintOf(base.take(1)))
        assertNotEquals(fingerprintOf(base), fingerprintOf(listOf("Inbox2" to "100:20", base[1])))
    }

    @Test
    fun `path and stamp boundaries do not collide`() {
        assertNotEquals(fingerprintOf(listOf("a1" to "2")), fingerprintOf(listOf("a" to "12")))
    }

    @Test
    fun `no notes has a stable fingerprint`() {
        assertEquals(fingerprintOf(emptyList()), fingerprintOf(emptyList()))
    }
}
