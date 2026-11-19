package com.isotjs.todosian.sync

/**
 * Maps a tree document id of the external storage provider to a file system path:
 * `primary:Documents/Vault` → `<primaryRoot>/Documents/Vault`, `1234-ABCD:Notes` →
 * `/storage/1234-ABCD/Notes`. Returns null for ids it does not understand.
 */
internal fun treeDocumentIdToPath(documentId: String, primaryRoot: String): String? {
    val colon = documentId.indexOf(':')
    if (colon <= 0) return null
    val volume = documentId.substring(0, colon)
    val relative = documentId.substring(colon + 1).trim('/')
    val root = when {
        volume.equals("primary", ignoreCase = true) -> primaryRoot.trimEnd('/')
        volume.equals("home", ignoreCase = true) -> primaryRoot.trimEnd('/') + "/Documents"
        volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "/storage/$volume"
        else -> return null
    }
    return if (relative.isEmpty()) root else "$root/$relative"
}

/** True for file names whose change matters for notes (same rule as the note list). */
internal fun isWatchedNoteName(name: String): Boolean =
    name.endsWith(".md", ignoreCase = true) && !name.contains("sync-conflict", ignoreCase = true)
