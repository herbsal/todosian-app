package com.isotjs.todosian.utils

/** Why a line of a keyword or profile note was not understood. */
enum class NoteProblemKind {
    /** A list item without any valid `#tag`. */
    NO_TAG,

    /** A keyword line with text that is not a tag, e.g. `5min` without `#`, or `#5` (only digits). */
    UNKNOWN_TEXT,

    /** A rule without a valid time like `09:00`. */
    NO_TIME,

    /** A repeat line with an unknown value. */
    BAD_REPEAT,

    /** A heading in a profile note that is not a `#tag`, so its rules are ignored. */
    BAD_HEADING,
}

/** A line the app did not understand; [line] is its index, [text] its content. */
data class NoteProblem(val line: Int, val text: String, val kind: NoteProblemKind)

/**
 * Edits single lines of a note and leaves everything else as it is, so a note can be changed
 * both in the app and in another editor. Every edit names the line by index and by its expected
 * content; when the note changed meanwhile, the line is looked up by content. If it cannot be
 * found exactly once the edit returns null and nothing is written.
 */
object NoteLines {
    private val itemPrefixRegex = Regex("""^(\s*(?:[-*+]|\d+[.)])\s+)""")

    fun locate(lines: List<String>, line: Int, expected: String): Int? {
        if (line in lines.indices && lines[line] == expected) return line
        return lines.indices.filter { lines[it] == expected }.singleOrNull()
    }

    fun replace(lines: List<String>, line: Int, expected: String, text: String): List<String>? {
        val index = locate(lines, line, expected) ?: return null
        return lines.toMutableList().apply { set(index, text) }
    }

    /** Removes all [targets] (index to expected content) at once, or nothing. */
    fun remove(lines: List<String>, targets: List<Pair<Int, String>>): List<String>? {
        val indices = targets.map { (line, expected) -> locate(lines, line, expected) ?: return null }.toSet()
        return lines.filterIndexed { index, _ -> index !in indices }
    }

    fun insertAfter(lines: List<String>, line: Int, expected: String, newLines: List<String>): List<String>? {
        val index = locate(lines, line, expected) ?: return null
        return lines.take(index + 1) + newLines + lines.drop(index + 1)
    }

    /** Appends [block] after the last non-blank line, separated by one blank line. */
    fun append(lines: List<String>, block: List<String>): List<String> {
        var end = lines.size
        while (end > 0 && lines[end - 1].isBlank()) end--
        val body = lines.take(end)
        val trailing = if (end < lines.size) listOf("") else emptyList()
        return body + (if (body.isNotEmpty()) listOf("") else emptyList()) + block + trailing
    }

    /** Puts [content] behind the list marker of [original] (`- `, `* `, `1. `, with indentation). */
    fun withItemPrefix(original: String, content: String): String {
        val prefix = itemPrefixRegex.find(original)?.groupValues?.get(1) ?: "- "
        return prefix + content
    }

    /** The text of a list item without its marker, or the trimmed line. */
    fun itemContent(line: String): String = line.replaceFirst(itemPrefixRegex, "").trim()
}
