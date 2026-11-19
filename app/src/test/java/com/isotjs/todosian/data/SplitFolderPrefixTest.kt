package com.isotjs.todosian.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitFolderPrefixTest {
    private val folders = mapOf("Familie" to 1, "Arbeit" to 2)

    @Test
    fun prefix_of_an_extra_folder_selects_that_folder() {
        assertEquals(1 to "Einkauf", splitFolderPrefix("Familie/Einkauf", folders))
    }

    @Test
    fun prefix_matches_ignoring_case_and_spaces() {
        assertEquals(2 to "Projekt X", splitFolderPrefix("arbeit / Projekt X", folders))
    }

    @Test
    fun name_without_prefix_stays_in_main_folder() {
        assertNull(splitFolderPrefix("Einkauf", folders))
    }

    @Test
    fun unknown_prefix_stays_in_main_folder() {
        assertNull(splitFolderPrefix("Urlaub/Packliste", folders))
    }

    @Test
    fun empty_parts_are_not_split() {
        assertNull(splitFolderPrefix("/Einkauf", folders))
        assertNull(splitFolderPrefix("Familie/", folders))
        assertNull(splitFolderPrefix("Familie/   ", folders))
    }

    @Test
    fun no_extra_folders_means_no_split() {
        assertNull(splitFolderPrefix("Familie/Einkauf", emptyMap<String, Int>()))
    }
}
