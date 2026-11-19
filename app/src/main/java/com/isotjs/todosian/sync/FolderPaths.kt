package com.isotjs.todosian.sync

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** The directory behind a folder picked through the system picker, or null if it has none. */
internal fun treeUriToDirectory(treeUri: Uri, primaryRoot: File): File? {
    if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
    return treeDocumentIdToPath(documentId, primaryRoot.absolutePath)?.let(::File)
}
