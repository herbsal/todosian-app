package com.isotjs.todosian.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonScopeTest {
    private val definitions = TagDefinitionParser.parse(
        listOf(
            "## Person",
            "- Michael: #person/michael, #michael",
            "- Sam: #person/sam",
        ),
    )

    @Test
    fun onlyMine() {
        val scope = PersonScope("Person", setOf("#person/michael"), includeUnassigned = false)
        assertTrue(scope.allows("Zahnarzt #michael", definitions))
        assertFalse(scope.allows("Einkauf #person/sam", definitions))
        assertFalse(scope.allows("Müll raus", definitions))
        assertTrue(scope.allows("Gemeinsam #person/sam #person/michael", definitions))
    }

    @Test
    fun mineAndUnassigned() {
        val scope = PersonScope("person", setOf("#person/michael"), includeUnassigned = true)
        assertTrue(scope.allows("Müll raus", definitions))
        assertFalse(scope.allows("Einkauf #person/sam", definitions))
    }

    @Test
    fun missingGroupAllowsEverything() {
        val scope = PersonScope("Leute", emptySet(), includeUnassigned = false)
        assertTrue(scope.allows("Einkauf #person/sam", definitions))
    }

    @Test
    fun encodeRoundTrip() {
        val scope = PersonScope("Person", setOf("#person/sam", "#person/michael"), includeUnassigned = true)
        assertEquals(scope, PersonScope.decode(scope.encode()))
        assertEquals(PersonScope("Person", emptySet(), false), PersonScope.decode(PersonScope("Person", emptySet(), false).encode()))
        assertNull(PersonScope.decode(null))
        assertNull(PersonScope.decode(""))
    }
}
