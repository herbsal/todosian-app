package com.isotjs.todosian.utils

import com.isotjs.todosian.data.settings.TaskIdMode
import kotlin.random.Random

/**
 * Gives tasks a stable `🆔` while the app writes their line anyway. Never a bulk change: only lines
 * that this write adds or changes get one, and only open tasks.
 */
object TaskIds {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val LENGTH = 6
    private val blockIdAtEnd = Regex("""\s\^[A-Za-z0-9-]+\s*$""")
    private val openTask = Regex("""^[ \t]*- \[ ] .*""")

    /**
     * [after] as it should be written over [before]: added or changed open tasks without an id get
     * one per [mode], and an id that another line of the note already has is taken off the new line.
     */
    fun assign(
        before: List<String>,
        after: List<String>,
        mode: TaskIdMode,
        newId: () -> String = ::randomId,
    ): List<String> {
        if (mode == TaskIdMode.NEVER) return after
        val remaining = HashMap<String, Int>()
        before.forEach { remaining[it] = (remaining[it] ?: 0) + 1 }
        val unchanged = BooleanArray(after.size)
        after.forEachIndexed { index, line ->
            val left = remaining[line] ?: 0
            if (left > 0) {
                unchanged[index] = true
                remaining[line] = left - 1
            }
        }
        val used = HashSet<String>()
        after.forEachIndexed { index, line ->
            if (unchanged[index]) MarkdownParser.taskIdOf(line)?.let(used::add)
        }
        val taken = HashSet<String>().apply { after.forEach { line -> MarkdownParser.taskIdOf(line)?.let(::add) } }

        var changedAny = false
        val result = after.mapIndexed { index, line ->
            if (unchanged[index] || !MarkdownParser.isTaskLine(line)) return@mapIndexed line
            val existing = MarkdownParser.taskIdOf(line)
            var current = line
            if (existing != null) {
                if (used.add(existing)) return@mapIndexed line
                // Two tasks in one note must never share an id.
                current = MarkdownParser.withoutTaskId(line)
            }
            if (!qualifies(current, mode)) {
                if (current != line) changedAny = true
                return@mapIndexed current
            }
            var id = newId()
            var attempts = 0
            while ((id in used || id in taken) && attempts++ < 20) id = newId()
            used.add(id)
            taken.add(id)
            changedAny = true
            withId(current, id)
        }
        return if (changedAny) result else after
    }

    private fun qualifies(line: String, mode: TaskIdMode): Boolean {
        if (!openTask.matches(line)) return false
        // A block id is a stable id too; the app uses it and adds no second one.
        if (blockIdAtEnd.containsMatchIn(line)) return false
        return when (mode) {
            TaskIdMode.NEVER -> false
            TaskIdMode.ALL -> true
            TaskIdMode.REMINDERS -> MarkdownParser.parse(listOf(line)).firstOrNull()?.reminder != null
        }
    }

    private fun withId(line: String, id: String): String = "${line.trimEnd()} $TASK_ID_MARKER $id"

    fun randomId(): String = buildString { repeat(LENGTH) { append(ALPHABET[Random.nextInt(ALPHABET.length)]) } }
}
