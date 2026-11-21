package com.isotjs.todosian.data.index

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer

/**
 * The note index in SQLite (part of Android, no extra library). The database is disposable: a
 * new schema version simply drops and rebuilds it, and a corrupt file is deleted by SQLite's
 * default error handler, after which the index is rebuilt from the vault.
 */
class SqliteIndexStore(context: Context) : IndexStore {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE notes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    folder TEXT NOT NULL,
                    doc_id TEXT NOT NULL,
                    uri TEXT NOT NULL UNIQUE,
                    rel_path TEXT NOT NULL,
                    mtime INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    hash TEXT NOT NULL,
                    line_count INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    indexed_at INTEGER NOT NULL,
                    own_write_hash TEXT,
                    line_hashes BLOB,
                    base_line_hashes BLOB
                )""",
            )
            db.execSQL(
                """CREATE TABLE task_lines (
                    note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                    line INTEGER NOT NULL,
                    raw TEXT NOT NULL,
                    PRIMARY KEY (note_id, line)
                )""",
            )
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
            db.execSQL(
                """CREATE TABLE versions (
                    note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
                    at INTEGER NOT NULL,
                    line_hashes BLOB NOT NULL
                )""",
            )
            db.execSQL("CREATE INDEX versions_note ON versions(note_id, at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = rebuild(db)

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = rebuild(db)

        private fun rebuild(db: SQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS versions")
            db.execSQL("DROP TABLE IF EXISTS task_lines")
            db.execSQL("DROP TABLE IF EXISTS notes")
            db.execSQL("DROP TABLE IF EXISTS meta")
            onCreate(db)
        }
    }

    override fun loadAll(): List<IndexedNote> {
        val db = helper.readableDatabase
        val tasks = HashMap<Long, MutableList<TaskLine>>()
        db.rawQuery("SELECT note_id, line, raw FROM task_lines ORDER BY note_id, line", null).use { c ->
            while (c.moveToNext()) tasks.getOrPut(c.getLong(0)) { mutableListOf() }.add(TaskLine(c.getInt(1), c.getString(2)))
        }
        val notes = ArrayList<IndexedNote>()
        db.rawQuery(
            "SELECT id, folder, doc_id, uri, rel_path, mtime, size, hash, line_count, state, indexed_at, own_write_hash, line_hashes, base_line_hashes FROM notes",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                notes += IndexedNote(
                    id = id,
                    folder = c.getString(1),
                    docId = c.getString(2),
                    uri = c.getString(3),
                    relPath = c.getString(4),
                    mtime = c.getLong(5),
                    size = c.getLong(6),
                    hash = c.getString(7),
                    lineCount = c.getInt(8),
                    state = runCatching { NoteState.valueOf(c.getString(9)) }.getOrDefault(NoteState.UNSETTLED),
                    indexedAt = c.getLong(10),
                    ownWriteHash = if (c.isNull(11)) null else c.getString(11),
                    lineHashes = if (c.isNull(12)) LongArray(0) else c.getBlob(12).toLongs(),
                    baseLineHashes = if (c.isNull(13)) null else c.getBlob(13).toLongs(),
                    taskLines = tasks[id].orEmpty(),
                )
            }
        }
        return notes
    }

    override fun upsert(note: IndexedNote): IndexedNote {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("folder", note.folder)
                put("doc_id", note.docId)
                put("uri", note.uri)
                put("rel_path", note.relPath)
                put("mtime", note.mtime)
                put("size", note.size)
                put("hash", note.hash)
                put("line_count", note.lineCount)
                put("state", note.state.name)
                put("indexed_at", note.indexedAt)
                put("own_write_hash", note.ownWriteHash)
                put("line_hashes", note.lineHashes.toBytes())
                put("base_line_hashes", note.baseLineHashes?.toBytes())
            }
            // A URI belongs to one note only (a renamed note may take over a free URI).
            db.delete("notes", "uri = ? AND id != ?", arrayOf(note.uri, note.id.toString()))
            val id = if (note.id == 0L) {
                db.insertOrThrow("notes", null, values)
            } else {
                if (db.update("notes", values, "id = ?", arrayOf(note.id.toString())) == 0) {
                    values.put("id", note.id)
                    db.insertOrThrow("notes", null, values)
                }
                note.id
            }
            db.delete("task_lines", "note_id = ?", arrayOf(id.toString()))
            note.taskLines.forEach { task ->
                db.insertOrThrow(
                    "task_lines",
                    null,
                    ContentValues().apply {
                        put("note_id", id)
                        put("line", task.line)
                        put("raw", task.raw)
                    },
                )
            }
            db.setTransactionSuccessful()
            return note.copy(id = id)
        } finally {
            db.endTransaction()
        }
    }

    override fun delete(id: Long) {
        helper.writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))
    }

    override fun meta(key: String): String? =
        helper.readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    override fun setMeta(key: String, value: String?) {
        val db = helper.writableDatabase
        if (value == null) {
            db.delete("meta", "key = ?", arrayOf(key))
        } else {
            db.insertWithOnConflict(
                "meta",
                null,
                ContentValues().apply {
                    put("key", key)
                    put("value", value)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    override fun clear() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("versions", null, null)
            db.delete("task_lines", null, null)
            db.delete("notes", null, null)
            db.delete("meta", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun addVersion(noteId: Long, at: Long, hashes: LongArray) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow(
                "versions",
                null,
                ContentValues().apply {
                    put("note_id", noteId)
                    put("at", at)
                    put("line_hashes", hashes.toBytes())
                },
            )
            val rows = ArrayList<Pair<Long, Long>>()
            db.rawQuery("SELECT rowid, at FROM versions WHERE note_id = ? ORDER BY at, rowid", arrayOf(noteId.toString())).use { c ->
                while (c.moveToNext()) rows += c.getLong(0) to c.getLong(1)
            }
            val keep = NoteVersions.keep(rows.map { it.second }, System.currentTimeMillis())
            rows.forEachIndexed { index, (rowId, _) ->
                if (index !in keep) db.delete("versions", "rowid = ?", arrayOf(rowId.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun oldestVersion(noteId: Long): LongArray? =
        helper.readableDatabase.rawQuery(
            "SELECT line_hashes FROM versions WHERE note_id = ? ORDER BY at, rowid LIMIT 1",
            arrayOf(noteId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getBlob(0).toLongs() else null }

    private fun LongArray.toBytes(): ByteArray = ByteBuffer.allocate(size * 8).also { buffer -> forEach { buffer.putLong(it) } }.array()

    private fun ByteArray.toLongs(): LongArray {
        val buffer = ByteBuffer.wrap(this)
        return LongArray(size / 8) { buffer.getLong() }
    }

    companion object {
        private const val DB_NAME = "note-index.db"
        private const val DB_VERSION = 2
    }
}
