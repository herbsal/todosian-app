package com.isotjs.todosian.data.index

import android.content.Context

/**
 * The index of the vault in memory, backed by an [IndexStore]. Loaded once per process; every
 * change goes to the store first (one transaction per note) and then to memory.
 */
class VaultIndex(private val store: IndexStore) {
    private val byUri = LinkedHashMap<String, IndexedNote>()
    private var loaded = false

    @Synchronized
    fun notes(): List<IndexedNote> {
        ensureLoaded()
        return byUri.values.toList()
    }

    @Synchronized
    fun note(uri: String): IndexedNote? {
        ensureLoaded()
        return byUri[uri]
    }

    @Synchronized
    fun isEmpty(): Boolean {
        ensureLoaded()
        return byUri.isEmpty()
    }

    @Synchronized
    fun put(note: IndexedNote): IndexedNote {
        ensureLoaded()
        val stored = store.upsert(note)
        byUri.values.removeAll { it.id == stored.id && it.uri != stored.uri }
        byUri[stored.uri] = stored
        return stored
    }

    @Synchronized
    fun remove(uri: String) {
        ensureLoaded()
        byUri.remove(uri)?.let { store.delete(it.id) }
    }

    /** Throws the index away; the next look reads every note again. */
    @Synchronized
    fun rebuild() {
        store.clear()
        store.setMeta(KEY_VERSION, VERSION.toString())
        byUri.clear()
        loaded = true
    }

    @Synchronized
    fun meta(key: String): String? = store.meta(key)

    @Synchronized
    fun setMeta(key: String, value: String?) = store.setMeta(key, value)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        // A new way of reading notes (parser or task detection) means a fresh index.
        if (store.meta(KEY_VERSION) != VERSION.toString()) {
            store.clear()
            store.setMeta(KEY_VERSION, VERSION.toString())
            return
        }
        store.loadAll().forEach { byUri[it.uri] = it }
    }

    companion object {
        /** Raise when what is stored per note changes; the index is then rebuilt from the vault. */
        const val VERSION = 1
        private const val KEY_VERSION = "index_version"

        @Volatile private var instance: VaultIndex? = null

        /** One index per app process, shared by lists, reminders and widgets. */
        fun get(context: Context): VaultIndex =
            instance ?: synchronized(this) {
                instance ?: VaultIndex(SqliteIndexStore(context.applicationContext)).also { instance = it }
            }
    }
}
