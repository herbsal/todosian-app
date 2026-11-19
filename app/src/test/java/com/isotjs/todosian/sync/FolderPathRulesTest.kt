package com.isotjs.todosian.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPathRulesTest {
    private val primary = "/storage/emulated/0"

    @Test
    fun `primary storage maps below the primary root`() {
        assertEquals("/storage/emulated/0/Documents/Vault", treeDocumentIdToPath("primary:Documents/Vault", primary))
        assertEquals("/storage/emulated/0", treeDocumentIdToPath("primary:", primary))
        assertEquals("/storage/emulated/0/Sync", treeDocumentIdToPath("primary:/Sync/", "$primary/"))
    }

    @Test
    fun `sd card volume maps below storage`() {
        assertEquals("/storage/1A2B-3C4D/Notes", treeDocumentIdToPath("1A2B-3C4D:Notes", primary))
    }

    @Test
    fun `home maps to documents`() {
        assertEquals("/storage/emulated/0/Documents/Vault", treeDocumentIdToPath("home:Vault", primary))
    }

    @Test
    fun `unknown ids give null`() {
        assertNull(treeDocumentIdToPath("raw:/data/x", primary))
        assertNull(treeDocumentIdToPath("nocolon", primary))
        assertNull(treeDocumentIdToPath(":Vault", primary))
    }

    @Test
    fun `only markdown notes are watched`() {
        assertTrue(isWatchedNoteName("Inbox.md"))
        assertTrue(isWatchedNoteName("Inbox.MD"))
        assertFalse(isWatchedNoteName(".syncthing.Inbox.md.tmp"))
        assertFalse(isWatchedNoteName("Inbox.sync-conflict-20261119.md"))
        assertFalse(isWatchedNoteName("image.png"))
    }
}
