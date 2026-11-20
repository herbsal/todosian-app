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
            "- Alex: #person/alex, #alex",
            "- Sam: #person/sam",
        ),
    )

    @Test
    fun onlyMine() {
        val scope = PersonScope("Person", setOf("#person/alex"), includeUnassigned = false)
        assertTrue(scope.allows("Zahnarzt #alex", definitions))
        assertFalse(scope.allows("Einkauf #person/sam", definitions))
        assertFalse(scope.allows("Müll raus", definitions))
        assertTrue(scope.allows("Gemeinsam #person/sam #person/alex", definitions))
    }

    @Test
    fun mineAndUnassigned() {
        val scope = PersonScope("person", setOf("#person/alex"), includeUnassigned = true)
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
        val scope = PersonScope("Person", setOf("#person/sam", "#person/alex"), includeUnassigned = true)
        assertEquals(scope, PersonScope.decode(scope.encode()))
        assertEquals(PersonScope("Person", emptySet(), false), PersonScope.decode(PersonScope("Person", emptySet(), false).encode()))
        assertNull(PersonScope.decode(null))
        assertNull(PersonScope.decode(""))
    }
}
