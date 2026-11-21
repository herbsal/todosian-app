package com.isotjs.todosian.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.isotjs.todosian.data.index.IndexedNote
import com.isotjs.todosian.data.index.ListedNote
import com.isotjs.todosian.data.index.NoteState
import com.isotjs.todosian.data.index.ReconcileResult
import com.isotjs.todosian.data.index.Reconciler
import com.isotjs.todosian.data.index.VaultIndex
import java.io.File

/** A note of the vault together with what the index knows about it. */
internal data class SyncedNote(val entry: MarkdownEntry<Uri>, val note: IndexedNote?) {
    /** The note's lines as far as tasks go (task lines at their place, other lines empty). */
    val lines: List<String> get() = note?.sparseLines().orEmpty()

    /** False when the note could not be read yet; its tasks are then unknown. */
    val isKnown: Boolean get() = note != null && note.hash.isNotEmpty()
}

/**
 * Lists a folder, brings the note index in line with it and hands out the notes. Lists,
 * reminders and widgets all go through here, so a note is read once per change, not once per
 * consumer. Editing and saving never use this: they always read the file itself.
 */
internal class VaultSync(private val context: Context, private val index: VaultIndex) {
    private val reconciler = Reconciler(index)
    private val lock = Any()

    /**
     * All Markdown notes of [folder]. [fresh] reads every note again (pull to refresh).
     * Throws when the folder itself cannot be listed.
     */
    fun notes(
        folder: Uri,
        recursive: Boolean,
        fresh: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedNote> {
        val tree = scanMarkdownTree(context, folder, recursive)
        val key = folder.toString()
        val listed = tree.files.map { it.toListed() }
        val result: ReconcileResult
        synchronized(lock) {
            if (fresh) invalidateFolder(key)
            result = reconciler.reconcile(key, listed, ::readBytes, onProgress)
        }
        if (result.read > 0) {
            Log.d(TAG, "sync $key: ${listed.size} notes, read ${result.read}, +${result.added.size} ~${result.changed.size} -${result.removed.size}")
        }
        return tree.files.map { SyncedNote(it, index.note(it.ref.toString())) }
    }

    /** Drops notes of folders that are no longer selected. */
    fun retainFolders(folders: Collection<Uri>) {
        synchronized(lock) { reconciler.retainFolders(folders.mapTo(HashSet()) { it.toString() }) }
    }

    /** The app wrote [uri]: read it again on the next look, whatever its time and size say. */
    fun invalidate(uri: Uri) {
        index.note(uri.toString())?.let { index.put(it.copy(state = NoteState.UNSETTLED)) }
    }

    private fun invalidateFolder(folder: String) {
        index.notes().filter { it.folder == folder && it.state == NoteState.OK }
            .forEach { index.put(it.copy(state = NoteState.UNSETTLED)) }
    }

    private fun readBytes(note: ListedNote): ByteArray? =
        context.contentResolver.openInputStream(Uri.parse(note.uri))?.use { it.readBytes() }

    private fun MarkdownEntry<Uri>.toListed(): ListedNote {
        val uri = ref.toString()
        return ListedNote(
            docId = uri.substringAfterLast("/document/"),
            uri = uri,
            relPath = relativePath,
            mtime = stamp?.lastModified ?: 0L,
            size = stamp?.size ?: -1L,
        )
    }

    companion object {
        private const val TAG = "VaultSync"
        private const val OLD_INDEX_FILE = "note-index.bin"

        @Volatile private var instance: VaultSync? = null

        fun get(context: Context): VaultSync =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    // The file cache of the first index version is replaced by the database.
                    runCatching { File(app.noBackupFilesDir, OLD_INDEX_FILE).delete() }
                    VaultSync(app, VaultIndex.get(app))
                }.also { instance = it }
            }
    }
}
