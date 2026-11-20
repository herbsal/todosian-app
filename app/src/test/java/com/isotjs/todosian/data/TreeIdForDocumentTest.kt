package com.isotjs.todosian.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeIdForDocumentTest {
    private val trees = listOf("primary:Vault", "primary:Sync/Shared", "primary:Sync")

    @Test
    fun `document in a folder picks that folder`() {
        assertEquals("primary:Vault", treeIdForDocument("primary:Vault/Inbox.md", trees))
    }

    @Test
    fun `the deepest matching folder wins`() {
        assertEquals("primary:Sync/Shared", treeIdForDocument("primary:Sync/Shared/Groceries.md", trees))
        assertEquals("primary:Sync", treeIdForDocument("primary:Sync/Other.md", trees))
    }

    @Test
    fun `a folder with a similar name does not match`() {
        assertNull(treeIdForDocument("primary:Vault2/Inbox.md", trees))
    }

    @Test
    fun `storage root matches everything below it`() {
        assertEquals("primary:", treeIdForDocument("primary:Notes/Inbox.md", listOf("primary:")))
    }

    @Test
    fun `walk skips the Todosian folder at the root only`() {
        val tree = mapOf(
            "root" to listOf(
                DocumentEntry("cfg", "Todosian", isDirectory = true),
                DocumentEntry("p", "Projects", isDirectory = true),
                DocumentEntry("inbox", "Inbox.md", isDirectory = false),
            ),
            "cfg" to listOf(DocumentEntry("tags", "Tags.md", isDirectory = false)),
            "p" to listOf(
                DocumentEntry("pt", "Todosian", isDirectory = true),
                DocumentEntry("move", "Move.md", isDirectory = false),
            ),
            "pt" to listOf(DocumentEntry("x", "X.md", isDirectory = false)),
        )
        val paths = walkMarkdownTree("root", recursive = true) { tree[it].orEmpty() }.files.map { it.relativePath }
        assertTrue("Inbox" in paths)
        assertTrue("Projects/Todosian/X" in paths)
        assertFalse(paths.any { it.startsWith("Todosian/") })
    }
}
