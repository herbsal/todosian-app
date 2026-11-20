package com.isotjs.todosian.utils

/** An open task as seen by the check for new tasks; [line] is the raw line in the note. */
data class SeenTask(val text: String, val line: String) {
    val key: String get() = NewTasks.keyOf(text)
}

/**
 * Finds tasks that are new in a note since the last look, for the "New for you" notification.
 * A task counts by its text, so moving or rewording a date does not make it new. Lines that this
 * device wrote itself are never new: adding a task in the app does not notify its own author.
 */
object NewTasks {
    fun keyOf(text: String): String = hash(text.trim().lowercase())

    fun lineKey(line: String): String = hash(line.trim())

    /**
     * The tasks of [current] that were not in [previous]. [previous] null means the note is
     * looked at for the first time, so nothing is new yet. [ownLines] holds [lineKey]s.
     */
    fun detect(previous: Set<String>?, current: List<SeenTask>, ownLines: Set<String>): List<SeenTask> {
        if (previous == null) return emptyList()
        return current
            .filter { it.key !in previous && lineKey(it.line) !in ownLines }
            .distinctBy { it.key }
    }

    private fun hash(value: String): String {
        var h = -0x340d631b7bdddcdbL
        for (ch in value) h = (h xor ch.code.toLong()) * 0x100000001b3L
        return java.lang.Long.toHexString(h)
    }
}
