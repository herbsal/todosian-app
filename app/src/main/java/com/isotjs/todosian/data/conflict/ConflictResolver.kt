package com.isotjs.todosian.data.conflict

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.NoteConflict
import com.isotjs.todosian.data.VaultSync
import com.isotjs.todosian.data.index.NoteIndexer
import com.isotjs.todosian.sync.FolderChangeCheck
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TaskMarkers
import java.io.File
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A note, its conflict copy and their merge, as read just now. */
internal data class PreparedMerge(val conflict: NoteConflict, val result: MergeResult)

internal sealed interface MergeOutcome {
    /** Written and the copy deleted; [backupId] restores both. */
    data class Done(val backupId: String) : MergeOutcome

    /** A file changed while the merge was open and there are new questions; nothing was written. */
    data class Changed(val prepared: PreparedMerge) : MergeOutcome

    data object Failed : MergeOutcome
}

/** A merged conflict that can be restored, from the private backups. */
internal data class MergeBackup(val id: String, val at: Long, val notePath: String, val copyName: String)

/**
 * Merges Syncthing conflict copies back into their note: reads both fresh, writes the result
 * through the normal save path, reads it back and only then deletes the copy. Before writing,
 * both files go to a private backup that lives 30 days and can be restored.
 */
internal class ConflictResolver(private val context: Context, private val files: FileRepository) {
    private val resolver get() = context.contentResolver
    private val backupDir get() = File(context.filesDir, BACKUP_DIR)

    suspend fun prepare(conflict: NoteConflict, markers: TaskMarkers): PreparedMerge? = withContext(Dispatchers.IO) {
        runCatching {
            val noteUri = conflict.noteUri ?: return@runCatching null
            val copyUri = conflict.copyUri ?: return@runCatching null
            val original = NoteIndexer.splitLines(read(Uri.parse(noteUri)).toString(Charsets.UTF_8))
            val copy = NoteIndexer.splitLines(read(Uri.parse(copyUri)).toString(Charsets.UTF_8))
            val base = VaultSync.get(context).conflictBase(noteUri)
            PreparedMerge(conflict, ConflictMerge.merge(base, original, copy, markers))
        }.onFailure { Log.w(TAG, "Reading a conflict failed", it) }.getOrNull()
    }

    /**
     * Writes the merge with [choices]. The files are read again first: if that brings other
     * questions than [prepared] had, nothing is written and the new merge is returned.
     */
    suspend fun apply(prepared: PreparedMerge, choices: Map<Int, MergeChoice>, markers: TaskMarkers): MergeOutcome =
        withContext(Dispatchers.IO) {
            val fresh = prepare(prepared.conflict, markers) ?: return@withContext MergeOutcome.Failed
            if (fresh.result.questions() != prepared.result.questions() || fresh.result.hasBase != prepared.result.hasBase) {
                return@withContext MergeOutcome.Changed(fresh)
            }
            val merged = fresh.result.render(choices) ?: return@withContext MergeOutcome.Failed
            runCatching {
                val conflict = fresh.conflict
                val noteUri = Uri.parse(conflict.noteUri!!)
                val copyUri = Uri.parse(conflict.copyUri!!)
                val noteBytes = read(noteUri)
                val copyBytes = read(copyUri)
                val backupId = backup(conflict, noteBytes, copyBytes)
                files.writeLines(noteUri, merged).getOrThrow()
                // Never delete the copy unless the note really holds the merge now (a new 🆔 aside).
                val written = NoteIndexer.splitLines(read(noteUri).toString(Charsets.UTF_8)).map(MarkdownParser::withoutTaskId)
                check(written == merged.map(MarkdownParser::withoutTaskId)) { "Merged note reads back differently" }
                check(DocumentsContract.deleteDocument(resolver, copyUri)) { "Conflict copy not deleted" }
                VaultSync.get(context).dropConflict(conflict)
                runCatching { FolderChangeCheck.run(context) }
                MergeOutcome.Done(backupId)
            }.getOrElse {
                Log.w(TAG, "Merging a conflict failed", it)
                MergeOutcome.Failed
            }
        }

    /** Puts note and copy back as they were before the merge [id]. */
    suspend fun restore(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(backupDir, id)
            val meta = Properties().apply { File(dir, META).inputStream().use(::load) }
            val noteUri = Uri.parse(meta.getProperty("note"))
            val noteLines = NoteIndexer.splitLines(File(dir, NOTE).readText())
            files.writeLines(noteUri, noteLines).getOrThrow()
            val copyUri = Uri.parse(meta.getProperty("copy"))
            val exists = runCatching { resolver.openInputStream(copyUri)?.close() != null }.getOrDefault(false)
            val target = if (exists) {
                copyUri
            } else {
                // Octet-stream keeps the name as it is; a Markdown type could get a second `.md`.
                DocumentsContract.createDocument(
                    resolver, Uri.parse(meta.getProperty("dir")), "application/octet-stream", meta.getProperty("copyName"),
                ) ?: error("Conflict copy could not be created")
            }
            resolver.openOutputStream(target, "wt")?.use { it.write(File(dir, COPY).readBytes()) } ?: error("Copy not writable")
            dir.deleteRecursively()
            runCatching { FolderChangeCheck.run(context) }
            true
        }.getOrElse {
            Log.w(TAG, "Restoring a merged conflict failed", it)
            false
        }
    }

    /** Merged conflicts that can still be restored, newest first; older than 30 days are deleted. */
    fun backups(now: Long = System.currentTimeMillis()): List<MergeBackup> =
        backupDir.listFiles().orEmpty().mapNotNull { dir ->
            val meta = runCatching { Properties().apply { File(dir, META).inputStream().use(::load) } }.getOrNull()
            val at = meta?.getProperty("at")?.toLongOrNull()
            if (meta == null || at == null || now - at > KEEP_MS) {
                dir.deleteRecursively()
                return@mapNotNull null
            }
            MergeBackup(dir.name, at, meta.getProperty("path").orEmpty(), meta.getProperty("copyName").orEmpty())
        }.sortedByDescending { it.at }

    private fun backup(conflict: NoteConflict, note: ByteArray, copy: ByteArray): String {
        backups() // drops the old ones
        val at = System.currentTimeMillis()
        val id = "$at-${conflict.copyPath.hashCode().toUInt()}"
        val dir = File(backupDir, id).apply { mkdirs() }
        File(dir, NOTE).writeBytes(note)
        File(dir, COPY).writeBytes(copy)
        Properties().apply {
            setProperty("at", at.toString())
            setProperty("note", conflict.noteUri)
            setProperty("copy", conflict.copyUri)
            setProperty("dir", conflict.dirUri.orEmpty())
            setProperty("copyName", conflict.copyName)
            setProperty("path", conflict.relPath)
        }.let { props -> File(dir, META).outputStream().use { props.store(it, null) } }
        return id
    }

    private fun read(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read $uri")

    companion object {
        private const val TAG = "ConflictResolver"
        private const val BACKUP_DIR = "conflict-backups"
        private const val NOTE = "note.md"
        private const val COPY = "copy.md"
        private const val META = "meta.properties"
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
