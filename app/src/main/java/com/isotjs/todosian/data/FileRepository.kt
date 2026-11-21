package com.isotjs.todosian.data

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.utils.MarkdownParser
import com.isotjs.todosian.utils.TaskMarkers
import com.isotjs.todosian.utils.TaskIds
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.isotjs.todosian.notifications.NewTaskAlerts
import com.isotjs.todosian.notifications.TaskReminderScheduler
import com.isotjs.todosian.sync.FolderWatchScheduler
import com.isotjs.todosian.utils.TagDefinitionParser
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.SavedView
import com.isotjs.todosian.utils.SavedViews
import com.isotjs.todosian.ui.widget.WidgetRefresher
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.LocalDate

private const val CONTENT_SCHEME = "content"

/** A config note changed since it was shown, so the line to edit was not found exactly once. */
class NoteChangedException : IllegalStateException("The note changed in the meantime")

internal fun isDocumentWithinFolder(
    folderAuthority: String?,
    candidateScheme: String?,
    candidateAuthority: String?,
    candidateDocumentId: String?,
): Boolean {
    if (candidateScheme != CONTENT_SCHEME) return false
    if (folderAuthority.isNullOrEmpty()) return false
    if (candidateAuthority != folderAuthority) return false
    return !candidateDocumentId.isNullOrEmpty()
}

/** Joins [lines] using the line separator and final-newline style already present in [original]. */
internal fun joinLinesLike(original: String, lines: List<String>): String {
    val separator = if (original.contains("\r\n")) "\r\n" else "\n"
    val finalNewline = if (lines.isNotEmpty() && original.endsWith('\n')) separator else ""
    return lines.joinToString(separator) + finalNewline
}

/**
 * The todo line a change was meant for is no longer in the file, usually because another app
 * (Obsidian, Syncthing) changed it after Todosian last read the file. Nothing was written.
 */
class TodoLineChangedException : IllegalStateException("Todo line changed on disk")

/** A folder the app reads notes from; [prefix] is put in front of its notes' names, empty for the main folder. */
internal data class NoteFolder(val uri: Uri, val prefix: String)

/** Splits `Shared/Groceries` into the extra folder named `Shared` and `Groceries`, if such a folder exists. */
internal fun <T> splitFolderPrefix(name: String, extraFolders: Map<String, T>): Pair<T, String>? {
    val slash = name.indexOf('/')
    if (slash <= 0) return null
    val folderName = name.substring(0, slash).trim()
    val rest = name.substring(slash + 1).trim()
    if (rest.isEmpty()) return null
    val folder = extraFolders.entries.firstOrNull { it.key.equals(folderName, ignoreCase = true) }?.value ?: return null
    return folder to rest
}

interface FileRepository {
    fun getFolderUri(): Uri?

    /** Folders added next to the main folder, in the order they were added. */
    fun getExtraFolderUris(): List<Uri>

    /** Takes a persistent permission for [uri] and adds it as an extra folder. */
    suspend fun addExtraFolder(uri: Uri): Result<Unit>

    /** Forgets the extra folder [uri] and releases its permission. Its files are not touched. */
    suspend fun removeExtraFolder(uri: Uri): Result<Unit>

    fun isCategoryUri(uri: Uri): Boolean

    fun clearFolderUri()

    suspend fun persistFolderUri(uri: Uri): Result<Unit>

    /**
     * All notes with their tasks. Notes unchanged since the last look come from the note index;
     * [fresh] is kept for callers; a normal look already reads every changed note, and the deep
     * check in the settings reads all of them.
     */
    suspend fun getCategories(markers: TaskMarkers = TaskMarkers.Default, fresh: Boolean = false): Result<List<Category>>

    /** The lists as the note index knows them, without looking at the folders; empty if unknown. */
    suspend fun getIndexedCategories(markers: TaskMarkers = TaskMarkers.Default): List<Category> = emptyList()

    suspend fun readLines(uri: Uri): Result<List<String>>

    suspend fun getDisplayName(uri: Uri): Result<String>

    suspend fun getFolderDisplayName(folderUri: Uri): Result<String>

    suspend fun writeLines(uri: Uri, lines: List<String>): Result<Unit>

    /** Repairs notes whose save was cut off, e.g. because the app was killed while writing. */
    suspend fun recoverInterruptedWrites() {}
    
    /**
     * Reads the current file, applies [transform] to those lines and writes the result, all while
     * holding the file lock, so changes made by other apps since the last read are not lost.
     * Returns the lines now on disk, or null when [transform] returned null and nothing was written.
     */
    suspend fun updateLines(uri: Uri, transform: (List<String>) -> List<String>?): Result<List<String>?>

    suspend fun createCategory(folderUri: Uri, name: String): Result<Uri>

    /**
     * Creates a note from what the user typed: `Shared/Groceries` goes into the extra folder
     * `Shared` when there is one, anything else into the main folder.
     */
    suspend fun createCategoryByName(name: String): Result<Uri>

    suspend fun renameCategory(categoryUri: Uri, newName: String): Result<Unit>

    suspend fun deleteCategory(categoryUri: Uri): Result<Unit>

    /** Fails with [TodoLineChangedException] when [expectedLine] is no longer in the source file. */
    suspend fun moveTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int, expectedLine: String): Result<Unit>

    /** Fails with [TodoLineChangedException] when [expectedLine] is no longer in the source file. */
    suspend fun copyTodoLine(sourceUri: Uri, targetUri: Uri, lineIndex: Int, expectedLine: String): Result<Unit>

    fun hasPersistedReadWritePermission(uri: Uri): Boolean

    suspend fun countMarkdownFiles(folderUri: Uri): Result<Int>

    fun observeDocumentChanges(documentUri: Uri): Flow<Unit>

    fun observeMarkdownFilesChanges(folderUri: Uri): Flow<Unit>

    /** Changes to the notes of the main folder and every extra folder. */
    fun observeAllMarkdownFilesChanges(): Flow<Unit>

    /**
     * The keywords defined in `Todosian/Tags.md` of the folder that holds
     * [noteUri]; the main folder's when [noteUri] is null. Empty when there is no such note.
     */
    suspend fun getTagDefinitions(noteUri: Uri?): TagDefinitions

    /**
     * The tags last read for the folder of [noteUri], at once and without touching the folder, so a
     * screen shows tag symbols right away instead of `#tag` first; null when never read.
     */
    fun cachedTagDefinitions(noteUri: Uri?): TagDefinitions? = null

    /** Names of the reminder profiles in `Todosian/Reminders/` of [folderUri]. */
    suspend fun getReminderProfileNames(folderUri: Uri): List<String>

    /** The lines of the keyword note of [folderUri] itself; empty when there is none. */
    suspend fun readTagNote(folderUri: Uri): List<String>

    /**
     * Changes the keyword note of [folderUri] with [edit] on its current lines, creating the note
     * (and `Todosian/`) with [title] when needed. Fails with [NoteChangedException] when [edit]
     * returns null. Returns the lines written.
     */
    suspend fun editTagNote(folderUri: Uri, title: String, edit: (List<String>) -> List<String>?): Result<List<String>>

    /** The lines of the saved views note (`Todosian/Views.md`) of [folderUri]; empty when there is none. */
    suspend fun readViewNote(folderUri: Uri): List<String>

    /** Like [editTagNote] for the saved views note. */
    suspend fun editViewNote(folderUri: Uri, title: String, edit: (List<String>) -> List<String>?): Result<List<String>>

    /** The saved views of all folders, in folder order. */
    suspend fun getSavedViews(): List<SavedView>

    /** The lines of the profile note [name]; null when there is none. */
    suspend fun readReminderProfile(folderUri: Uri, name: String): List<String>?

    /** Like [editTagNote] for the profile note `Todosian/Reminders/<name>.md`. */
    suspend fun editReminderProfile(folderUri: Uri, name: String, edit: (List<String>) -> List<String>?): Result<List<String>>

    suspend fun deleteReminderProfile(folderUri: Uri, name: String): Result<Unit>
}

class SafFileRepository(
    private val appContext: Context,
    private val preferencesManager: PreferencesManager,
    private val appSettingsRepository: AppSettingsRepository,
) : FileRepository {

    private val widgetUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()
    
    @Volatile
    private var widgetUpdateJob: Job? = null

    /** The set of folders changed: let the watch service observe the new set. */
    private fun onFoldersChanged() {
        widgetUpdateScope.launch {
            runCatching {
                val mode = appSettingsRepository.settings.first().folderWatchMode
                FolderWatchScheduler.apply(appContext, mode)
            }.onFailure { Log.w(TAG, "Folder watch update failed", it) }
        }
    }

    private fun scheduleWidgetUpdate() {
        // Every path that changes notes or folders ends here, so reminders are re-planned too.
        TaskReminderScheduler.requestSync(appContext)
        widgetUpdateJob?.cancel()
        widgetUpdateJob = widgetUpdateScope.launch {
            delay(WIDGET_UPDATE_DEBOUNCE_MS)
            refreshTodosianWidget()
        }
    }


    private fun refreshTodosianWidget() {
        widgetUpdateScope.launch {
            runCatching {
                Log.d(TAG, "Refreshing Todosian widgets")
                WidgetRefresher.refreshWidgets(appContext)
            }.onFailure { Log.e(TAG, "Widget refresh failed", it) }
        }
    }

    companion object {
        private const val WIDGET_UPDATE_DEBOUNCE_MS = 50L
        private const val TAG = "SafFileRepository"
    }

    override fun getFolderUri(): Uri? = preferencesManager.getFolderUri()

    private suspend fun includeSubfolders(): Boolean = appSettingsRepository.settings.first().includeSubfolders

    override fun getExtraFolderUris(): List<Uri> {
        val main = getFolderUri()
        return preferencesManager.getExtraFolderUris().filter { it != main }
    }

    private fun allFolderUris(): List<Uri> = listOfNotNull(getFolderUri()) + getExtraFolderUris()

    override suspend fun addExtraFolder(uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                require(uri != getFolderUri()) { "Already the main folder" }
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
                preferencesManager.saveExtraFolderUris(preferencesManager.getExtraFolderUris() + uri)
            }.onSuccess {
                scheduleWidgetUpdate()
                onFoldersChanged()
            }
        }
    }

    override suspend fun removeExtraFolder(uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                preferencesManager.saveExtraFolderUris(preferencesManager.getExtraFolderUris() - uri)
                preferencesManager.getDefaultAddTaskFileUri()
                    ?.takeIf { it.isWithinTree(uri) }
                    ?.let { preferencesManager.clearDefaultAddTaskFileUri() }
                if (uri != getFolderUri()) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    runCatching { appContext.contentResolver.releasePersistableUriPermission(uri, flags) }
                }
            }.onSuccess {
                scheduleWidgetUpdate()
                onFoldersChanged()
            }
        }
    }

    private fun Uri.isWithinTree(treeUri: Uri): Boolean =
        authority == treeUri.authority &&
            runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull() ==
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()

    private fun noteFolders(): List<NoteFolder> {
        val main = getFolderUri()
        return listOfNotNull(main?.let { NoteFolder(it, prefix = "") }) +
            getExtraFolderUris().map { NoteFolder(it, prefix = folderNameOf(it) + "/") }
    }

    private fun folderNameOf(folderUri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(appContext, folderUri)?.name }.getOrNull()
            ?: folderUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: folderUri.toString()

    override fun isCategoryUri(uri: Uri): Boolean {
        return allFolderUris().any { folderUri ->
            if (folderUri == uri) return@any false
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            isDocumentWithinFolder(
                folderAuthority = folderUri.authority,
                candidateScheme = uri.scheme,
                candidateAuthority = uri.authority,
                candidateDocumentId = documentId,
            )
        }
    }

    private fun <T> foreignUriError(): Result<T> =
        Result.failure(IllegalArgumentException("URI is not a note in the selected folder"))

    override fun clearFolderUri() {
        preferencesManager.clearFolderUri()
        scheduleWidgetUpdate()
        onFoldersChanged()
    }

    override suspend fun persistFolderUri(uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                appContext.contentResolver.takePersistableUriPermission(uri, flags)
                preferencesManager.saveFolderUri(uri)
            }.onSuccess {
                scheduleWidgetUpdate()
                onFoldersChanged()
            }
        }
    }

    private val vaultSync by lazy { VaultSync.get(appContext) }

    override suspend fun getCategories(markers: TaskMarkers, fresh: Boolean): Result<List<Category>> {
        if (getFolderUri() == null) return Result.success(emptyList())
        return withContext(Dispatchers.IO) {
            val recursive = includeSubfolders()
            runCatching {
                val folders = noteFolders()
                vaultSync.retainFolders(folders.map { it.uri })
                // Lists come from the note index; only notes that changed were read.
                categoriesOf(folders.flatMap { folder -> syncFolderOrEmpty(folder, recursive) }, markers)
            }
        }
    }

    override suspend fun getIndexedCategories(markers: TaskMarkers): List<Category> {
        if (getFolderUri() == null) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                categoriesOf(noteFolders().flatMap { folder -> vaultSync.indexedNotes(folder.uri).map { folder to it } }, markers)
            }.getOrDefault(emptyList())
        }
    }

    private fun categoriesOf(notes: List<Pair<NoteFolder, SyncedNote>>, markers: TaskMarkers): List<Category> {
        val today = LocalDate.now().toString()
        return notes
            .map { (folder, synced) ->
                val todos = MarkdownParser.parse(synced.lines, markers)
                val activeTodos = todos.filter { !it.isDone }
                Category(
                    displayName = folder.prefix + synced.entry.relativePath,
                    uri = synced.entry.ref,
                    todoCount = todos.size,
                    doneCount = todos.count { it.isDone },
                    dueTodayCount = activeTodos.count { it.dueDate == today },
                    overdueCount = activeTodos.count { todo -> todo.dueDate?.let { it < today } == true },
                    openTodos = activeTodos,
                    todos = todos,
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    override suspend fun readLines(uri: Uri): Result<List<String>> {
        if (!isCategoryUri(uri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock { readLinesInternal(uri) }
            }
        }
    }

    override suspend fun getDisplayName(uri: Uri): Result<String> {
        if (!isCategoryUri(uri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = DocumentFile.fromSingleUri(appContext, uri)
                    ?: throw IllegalStateException("Invalid file URI")
                file.name ?: ""
            }
        }
    }

    override suspend fun getFolderDisplayName(folderUri: Uri): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")
                folder.name
                    ?: folderUri.lastPathSegment
                    ?: folderUri.toString()
            }
        }
    }

    override suspend fun recoverInterruptedWrites() {
        withContext(Dispatchers.IO) {
            fileMutex.withLock {
                writeJournal.pending().forEach { entry ->
                    runCatching {
                        val uri = Uri.parse(entry.uri)
                        val current = runCatching { readBytesInternal(uri) }.getOrNull()
                        if (WriteJournal.recoveryFor(current, entry) == WriteJournal.Recovery.COMPLETE) {
                            Log.w(TAG, "Completing an interrupted save")
                            writeBytesInternal(uri, entry.new)
                            runCatching { appContext.contentResolver.notifyChange(uri, null) }
                        }
                    }.onFailure { Log.w(TAG, "Recovering an interrupted save failed", it) }
                    writeJournal.finish(entry.uri)
                }
            }
        }
    }

    override suspend fun writeLines(uri: Uri, lines: List<String>): Result<Unit> {
        if (!isCategoryUri(uri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock { writeLinesInternal(uri, lines) }
                Unit
            }
        }
    }

    override suspend fun updateLines(uri: Uri, transform: (List<String>) -> List<String>?): Result<List<String>?> {
        if (!isCategoryUri(uri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock {
                    val currentLines = readLinesInternal(uri)
                    val newLines = transform(currentLines) ?: return@withLock null
                    if (newLines != currentLines) writeLinesInternal(uri, newLines) else newLines
                }
            }
        }
    }

    /** The main folder must be readable; an extra folder that went missing is skipped. */
    private fun syncFolderOrEmpty(folder: NoteFolder, recursive: Boolean): List<Pair<NoteFolder, SyncedNote>> {
        val notes = if (folder.prefix.isEmpty()) {
            vaultSync.notes(folder.uri, recursive)
        } else {
            runCatching { vaultSync.notes(folder.uri, recursive) }
                .onFailure { Log.w(TAG, "Skipping unreadable extra folder", it) }
                .getOrDefault(emptyList())
        }
        return notes.map { folder to it }
    }

    override suspend fun createCategoryByName(name: String): Result<Uri> {
        val mainFolder = getFolderUri()
            ?: return Result.failure(IllegalStateException("No folder selected"))
        val extras = withContext(Dispatchers.IO) {
            getExtraFolderUris().associateBy { folderNameOf(it) }
        }
        val (folderUri, fileName) = splitFolderPrefix(name.trim(), extras) ?: (mainFolder to name)
        return createCategory(folderUri, fileName)
    }

    override suspend fun createCategory(folderUri: Uri, name: String): Result<Uri> {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = DocumentFile.fromTreeUri(appContext, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                val fileName = if (cleaned.endsWith(".md", ignoreCase = true)) cleaned else "$cleaned.md"
                val created = folder.createFile("text/markdown", fileName)
                    ?: throw IllegalStateException("Unable to create file")

                scheduleWidgetUpdate()
                created.uri
            }
        }
    }

    override suspend fun renameCategory(categoryUri: Uri, newName: String): Result<Unit> {
        val cleaned = newName.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Empty name"))

        return withContext(Dispatchers.IO) {
            runCatching {
                val fileName = cleaned
                    .removeSuffix(".md")
                    .removeSuffix(".MD")
                    .removeSuffix(".Md")
                    .removeSuffix(".mD")
                    .trim()
                    .let { base -> if (base.endsWith(".md", ignoreCase = true)) base else "$base.md" }

                val renamed = DocumentsContract.renameDocument(appContext.contentResolver, categoryUri, fileName)
                if (renamed == null) throw IllegalStateException("Unable to rename document")
                vaultSync.recordRename(categoryUri, renamed, fileName.dropLast(3))
                scheduleWidgetUpdate()
            }
        }
    }

    override suspend fun deleteCategory(categoryUri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = DocumentFile.fromSingleUri(appContext, categoryUri)
                    ?: throw IllegalStateException("Invalid file URI")
                val ok = file.delete()
                if (!ok) throw IllegalStateException("Unable to delete file")
                vaultSync.recordDelete(categoryUri)
                scheduleWidgetUpdate()
            }
        }
    }

    override suspend fun moveTodoLine(
        sourceUri: Uri,
        targetUri: Uri,
        lineIndex: Int,
        expectedLine: String,
    ): Result<Unit> {
        if (!isCategoryUri(sourceUri) || !isCategoryUri(targetUri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock {
                    val sourceLines = readLinesInternal(sourceUri)
                    val currentIndex = MarkdownParser.locateTodoLine(sourceLines, lineIndex, expectedLine)
                        ?: throw TodoLineChangedException()
                    val targetLines = readLinesInternal(targetUri)
                    val updated = MarkdownParser.tryMoveTodoLine(
                        sourceLines = sourceLines,
                        lineIndex = currentIndex,
                        targetLines = targetLines,
                    ) ?: throw TodoLineChangedException()

                    // Target first: if the second write fails the todo exists twice instead of not at all.
                    writeLinesInternal(targetUri, updated.second)
                    writeLinesInternal(sourceUri, updated.first)
                    Unit
                }
            }
        }
    }

    override suspend fun copyTodoLine(
        sourceUri: Uri,
        targetUri: Uri,
        lineIndex: Int,
        expectedLine: String,
    ): Result<Unit> {
        if (!isCategoryUri(sourceUri) || !isCategoryUri(targetUri)) return foreignUriError()
        return withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock {
                    val sourceLines = readLinesInternal(sourceUri)
                    val currentIndex = MarkdownParser.locateTodoLine(sourceLines, lineIndex, expectedLine)
                        ?: throw TodoLineChangedException()
                    val targetLines = readLinesInternal(targetUri)
                    val updated = MarkdownParser.tryCopyTodoLine(
                        sourceLines = sourceLines,
                        lineIndex = currentIndex,
                        targetLines = targetLines,
                    ) ?: throw TodoLineChangedException()

                    writeLinesInternal(targetUri, updated.second)
                    Unit
                }
            }
        }
    }

    override fun hasPersistedReadWritePermission(uri: Uri): Boolean {
        val perms = appContext.contentResolver.persistedUriPermissions
        val perm = perms.firstOrNull { it.uri == uri } ?: return false
        return perm.isReadPermission && perm.isWritePermission
    }

    override suspend fun countMarkdownFiles(folderUri: Uri): Result<Int> {
        val recursive = includeSubfolders()
        return withContext(Dispatchers.IO) {
            runCatching { scanMarkdownTree(appContext, folderUri, recursive).files.size }
        }
    }

    override fun observeDocumentChanges(documentUri: Uri): Flow<Unit> {
        return observeUrisChanges(
            uris = listOf(documentUri),
            notifyDescendants = false,
        )
    }

    override suspend fun getTagDefinitions(noteUri: Uri?): TagDefinitions = withContext(Dispatchers.IO) {
        val folder = tagFolderOf(noteUri) ?: return@withContext TagDefinitions.EMPTY
        runCatching {
            val lines = findTagNote(appContext, folder)?.let { readLinesInternal(it) }.orEmpty()
            rememberTagNote(folder, lines)
            TagDefinitionParser.parse(lines)
        }.onFailure { Log.w(TAG, "Reading the keyword note failed", it) }
            .getOrElse { cachedTagDefinitions(noteUri) ?: TagDefinitions.EMPTY }
    }

    override fun cachedTagDefinitions(noteUri: Uri?): TagDefinitions? {
        val folder = tagFolderOf(noteUri) ?: return null
        val key = folder.toString()
        tagCache[key]?.let { return it }
        // After a restart: the copy of the tag note from the last read (a few hundred bytes).
        val lines = runCatching { tagCacheFile(key).takeIf { it.exists() }?.readLines() }.getOrNull() ?: return null
        return TagDefinitionParser.parse(lines).also { tagCache[key] = it }
    }

    private val tagCache = java.util.concurrent.ConcurrentHashMap<String, TagDefinitions>()

    private fun rememberTagNote(folder: Uri, lines: List<String>) {
        val key = folder.toString()
        tagCache[key] = TagDefinitionParser.parse(lines)
        runCatching {
            val file = tagCacheFile(key)
            val text = lines.joinToString("\n")
            if (!file.exists() || file.readText() != text) {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        }
    }

    private fun tagCacheFile(folderKey: String): java.io.File =
        java.io.File(appContext.noBackupFilesDir, "tag-notes/${folderKey.hashCode().toUInt()}.md")

    private fun tagFolderOf(noteUri: Uri?): Uri? {
        val folders = allFolderUris()
        return noteUri
            ?.let { uri -> runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() }
            ?.let { documentId ->
                val byTreeId = folders.associateBy { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull().orEmpty() }
                treeIdForDocument(documentId, byTreeId.keys.filter { it.isNotEmpty() }.toList())?.let(byTreeId::get)
            }
            ?: getFolderUri()
    }

    override suspend fun getReminderProfileNames(folderUri: Uri): List<String> = withContext(Dispatchers.IO) {
        runCatching { findReminderProfiles(appContext, folderUri).map { it.first } }
            .onFailure { Log.w(TAG, "Listing reminder profiles failed", it) }
            .getOrDefault(emptyList())
    }


    override suspend fun readTagNote(folderUri: Uri): List<String> = withContext(Dispatchers.IO) {
        runCatching { findTagNote(appContext, folderUri)?.let { readLinesInternal(it) } }.getOrNull().orEmpty()
    }

    override suspend fun editTagNote(folderUri: Uri, title: String, edit: (List<String>) -> List<String>?): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock {
                    val existing = findTagNote(appContext, folderUri)
                    val names = TAG_NOTE_NAMES
                    val (uri, created) = existing?.let { it to false }
                        ?: ensureConfigNote(appContext, folderUri, subfolderNames = null, fileNames = names)
                    editConfigNote(uri, created, "# $title", edit)
                }
            }
        }

    override suspend fun readViewNote(folderUri: Uri): List<String> = withContext(Dispatchers.IO) {
        runCatching { findConfigNote(appContext, folderUri, VIEW_NOTE_NAMES)?.let { readLinesInternal(it) } }.getOrNull().orEmpty()
    }

    override suspend fun editViewNote(folderUri: Uri, title: String, edit: (List<String>) -> List<String>?): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock {
                    val existing = findConfigNote(appContext, folderUri, VIEW_NOTE_NAMES)
                    val names = VIEW_NOTE_NAMES
                    val (uri, created) = existing?.let { it to false }
                        ?: ensureConfigNote(appContext, folderUri, subfolderNames = null, fileNames = names)
                    editConfigNote(uri, created, "# $title", edit)
                }
            }
        }

    override suspend fun getSavedViews(): List<SavedView> = withContext(Dispatchers.IO) {
        allFolderUris().flatMap { folder ->
            runCatching {
                val note = findConfigNote(appContext, folder, VIEW_NOTE_NAMES) ?: return@runCatching emptyList()
                val definitions = findTagNote(appContext, folder)?.let { TagDefinitionParser.parse(readLinesInternal(it)) }
                    ?: TagDefinitions.EMPTY
                SavedViews.parse(readLinesInternal(note), definitions, folder.toString())
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun readReminderProfile(folderUri: Uri, name: String): List<String>? = withContext(Dispatchers.IO) {
        runCatching {
            findReminderProfiles(appContext, folderUri).firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
                ?.let { readLinesInternal(it) }
        }.getOrNull()
    }

    override suspend fun editReminderProfile(folderUri: Uri, name: String, edit: (List<String>) -> List<String>?): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                fileMutex.withLock {
                    val existing = findReminderProfiles(appContext, folderUri).firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
                    val folders = PROFILE_FOLDER_NAMES
                    val (uri, created) = existing?.let { it to false }
                        ?: ensureConfigNote(appContext, folderUri, folders, listOf("$name.md"))
                    editConfigNote(uri, created, "# $name", edit)
                }
            }
        }

    /** Reads the note again right before the change, so edits made elsewhere in the meantime survive. */
    private fun editConfigNote(uri: Uri, created: Boolean, title: String, edit: (List<String>) -> List<String>?): List<String> {
        val current = if (created) emptyList() else readLinesInternal(uri)
        val base = if (current.all { it.isBlank() }) listOf(title) else current
        val updated = edit(base) ?: throw NoteChangedException()
        // A new file gets a final newline; existing files keep theirs (see writeLinesInternal).
        writeLinesInternal(uri, if (created) updated + "" else updated)
        return updated
    }

    override suspend fun deleteReminderProfile(folderUri: Uri, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = findReminderProfiles(appContext, folderUri).firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
                ?: return@runCatching
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
            scheduleWidgetUpdate()
        }
    }

    override fun observeAllMarkdownFilesChanges(): Flow<Unit> {
        val folders = allFolderUris()
        if (folders.isEmpty()) return emptyFlow()
        return folders.map { folder ->
            observeMarkdownFilesChanges(folder).catch { Log.w(TAG, "Cannot observe folder", it) }
        }.merge()
    }

    override fun observeMarkdownFilesChanges(folderUri: Uri): Flow<Unit> {
        val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)

        return callbackFlow {
            val resolver = appContext.contentResolver

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var updateJob = scope.launch { }
            updateJob.cancel()

            var observedUris: List<Pair<Uri, Boolean>> = emptyList()

            lateinit var observer: ContentObserver

            suspend fun currentMarkdownUris(): List<Pair<Uri, Boolean>> {
                val tree = scanMarkdownTree(appContext, folderUri, includeSubfolders())

                val uris = mutableListOf<Pair<Uri, Boolean>>()
                // structural changes, in the selected folder and every scanned subfolder
                uris.add(folderUri to true)
                tree.folderIds.forEach { id ->
                    uris.add(DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, id) to true)
                }
                // content changes
                tree.files.forEach { uris.add(it.ref to false) }
                return uris
            }

            fun scheduleUpdate() {
                updateJob.cancel()
                updateJob = scope.launch {
                    delay(200)
                    val next = runCatching { currentMarkdownUris() }.getOrElse { emptyList() }
                    if (next == observedUris) return@launch

                    runCatching { resolver.unregisterContentObserver(observer) }
                    observedUris = next
                    runCatching {
                        observedUris.forEach { (uri, notifyDescendants) ->
                            resolver.registerContentObserver(uri, notifyDescendants, observer)
                        }
                    }.onFailure { t ->
                        close(t)
                    }
                }
            }

            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    trySend(Unit)
                    // Anything but a content change of a known note may add, remove or rename notes.
                    val isKnownNote = observedUris.any { (uri, notifyDescendants) -> !notifyDescendants && uri == changedUri }
                    if (!isKnownNote) scheduleUpdate()
                }
            }

            // initial register
            observedUris = listOf(
                folderUri to true,
                childrenUri to true,
            )
            runCatching {
                observedUris.forEach { (uri, notifyDescendants) ->
                    resolver.registerContentObserver(uri, notifyDescendants, observer)
                }
            }.onFailure { t ->
                close(t)
                return@callbackFlow
            }

            scheduleUpdate()

            awaitClose {
                updateJob.cancel()
                scope.cancel()
                runCatching { resolver.unregisterContentObserver(observer) }
            }
        }.conflate()
    }

    private fun observeUrisChanges(
        uris: List<Uri>,
        notifyDescendants: Boolean,
    ): Flow<Unit> {
        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }

                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    trySend(Unit)
                }
            }

            val resolver = appContext.contentResolver
            runCatching {
                uris.forEach { uri ->
                    resolver.registerContentObserver(uri, notifyDescendants, observer)
                }
            }.onFailure { t ->
                close(t)
                return@callbackFlow
            }

            awaitClose {
                runCatching { resolver.unregisterContentObserver(observer) }
            }
        }.conflate()
    }

    // SAF offers no atomic replace, so truncate and write in one go. Not every provider knows "wt".
    private fun openTruncatingOutputStream(uri: Uri): OutputStream? {
        val resolver = appContext.contentResolver
        return try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            resolver.openOutputStream(uri, "w")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        }
    }

    private fun readLinesInternal(uri: Uri): List<String> {
        appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            return reader.readLines()
        } ?: throw IllegalStateException("Unable to open input stream")
    }

    private val writeJournal by lazy { WriteJournal(java.io.File(appContext.filesDir, "write-journal")) }

    private fun readBytesInternal(uri: Uri): ByteArray? =
        appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    private fun writeBytesInternal(uri: Uri, bytes: ByteArray) {
        // Should the write fail half way, the next look reads the note itself.
        vaultSync.invalidate(uri)
        openTruncatingOutputStream(uri)?.use { out ->
            runCatching { (out as? FileOutputStream)?.channel?.truncate(0) }
            out.write(bytes)
            out.flush()
            // Make sure the data reaches the disk before sync tools pick the file up.
            runCatching { (out as? FileOutputStream)?.fd?.sync() }
        } ?: throw IllegalStateException("Unable to open output stream")
        // The index takes over exactly the written bytes, never the other way round.
        runCatching { vaultSync.recordOwnWrite(uri, bytes) }.onFailure { Log.w(TAG, "Index update after write failed", it) }
    }

    /** Writes [requested] and returns the lines as written, which may carry a new `🆔` (step 9). */
    private fun writeLinesInternal(uri: Uri, requested: List<String>): List<String> {
        // Re-read the file so CRLF and the final newline survive the round trip through readLines().
        val originalBytes = readBytesInternal(uri) ?: ByteArray(0)
        val original = originalBytes.toString(Charsets.UTF_8)
        val lines = runCatching {
            TaskIds.assign(original.lines(), requested, SharedPrefsAppSettingsRepository.taskIdMode(appContext))
        }.getOrDefault(requested)
        // Encode everything before opening the file, so a failure cannot leave it half written.
        val bytes = joinLinesLike(original, lines).toByteArray(Charsets.UTF_8)
        // Old and new content go to the journal first, so an interrupted save can be repaired (D2).
        val journaled = runCatching { writeJournal.begin(uri.toString(), originalBytes, bytes) }.isSuccess
        // Lines written here are this device's own, never "new for you" (P1).
        runCatching {
            NewTaskAlerts.recordOwnLines(appContext, lines.toSet() - original.lines().toSet())
        }
        writeBytesInternal(uri, bytes)
        if (journaled) {
            // Read back: some providers report success but keep old bytes at the end of the file.
            if (readBytesInternal(uri)?.contentEquals(bytes) != true) {
                writeBytesInternal(uri, bytes)
                check(readBytesInternal(uri)?.contentEquals(bytes) == true) { "Saved note differs from what was written" }
            }
            writeJournal.finish(uri.toString())
        }

        try {
            appContext.contentResolver.notifyChange(uri, null)
        } catch (e: Exception) {
            // Ignore
        }
        scheduleWidgetUpdate()
        return lines
    }
}
