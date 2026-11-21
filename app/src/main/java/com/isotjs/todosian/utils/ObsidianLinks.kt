package com.isotjs.todosian.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens notes and searches in the Obsidian app through its `obsidian://` links. */
object ObsidianLinks {
    fun openNoteUri(vault: String, relPath: String): Uri =
        Uri.Builder().scheme("obsidian").authority("open")
            .appendQueryParameter("vault", vault)
            .appendQueryParameter("file", relPath)
            .build()

    fun searchUri(vault: String, query: String): Uri =
        Uri.Builder().scheme("obsidian").authority("search")
            .appendQueryParameter("vault", vault)
            .appendQueryParameter("query", query)
            .build()

    /**
     * The vault name Obsidian knows [folder] by: the name set in the settings for the main folder,
     * else the folder's own name.
     */
    fun vaultName(context: Context, folder: Uri, mainFolder: Uri?, configured: String): String =
        configured.trim().takeIf { it.isNotEmpty() && folder == mainFolder }
            ?: runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folder)?.name }.getOrNull()
            ?: folder.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/').orEmpty()

    /** False when Obsidian is not installed. */
    fun launch(context: Context, uri: Uri): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
