package com.isotjs.todosian.utils

/**
 * One keyword with its synonyms, e.g. "Low energy" = `#energy/low`, `#energie/niedrig`.
 * The app writes [primaryTag]; every tag in [tags] counts as the same keyword when reading.
 */
data class TagTerm(val label: String, val tags: List<String>) {
    val primaryTag: String get() = tags.first()

    fun matches(tag: String): Boolean = tags.any { it.equals(tag, ignoreCase = true) }
}

/** Keywords under one heading of the keyword note. Only one keyword of a named group is set at a time. */
data class TagGroup(val name: String?, val terms: List<TagTerm>) {
    val exclusive: Boolean get() = name != null
}

/** The keywords a folder defines in its keyword note. */
data class TagDefinitions(val groups: List<TagGroup>) {
    val isEmpty: Boolean get() = groups.all { it.terms.isEmpty() }

    fun termFor(tag: String): TagTerm? = groups.firstNotNullOfOrNull { group -> group.terms.firstOrNull { it.matches(tag) } }

    companion object {
        val EMPTY = TagDefinitions(emptyList())
    }
}

/**
 * Reads a keyword note:
 *
 * ```
 * ## Effort
 * - 5 minutes: #5min, #kurz
 * - 2 hours: #2h
 *
 * ## Energy
 * - Low: #energy/low, #energie/niedrig
 * ```
 *
 * A heading of level 2 or deeper starts a group; the `#` title does not. Each list item is one keyword: the text before `:` is its label, the
 * tags after it are synonyms, the first one is written by the app. Without a label the first tag
 * is used as label. Lines without tags and code blocks are ignored.
 */
object TagDefinitionParser {
    private val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val itemRegex = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(.*)$""")
    private val tagRegex = Regex("""(?<![\p{L}\p{N}_])#([\p{L}\p{N}_/\-]*[\p{L}_/\-][\p{L}\p{N}_/\-]*)""")
    private val separatorRegex = Regex("""[,;\s]""")

    fun parse(lines: List<String>): TagDefinitions = parseNote(lines).definitions

    /** Reads the note with the line of every heading and keyword, and the lines it did not understand. */
    fun parseNote(lines: List<String>): TagNote {
        val groups = mutableListOf<TagNoteGroup>()
        val problems = mutableListOf<NoteProblem>()
        var currentName: String? = null
        var headingLine: Int? = null
        var currentTerms = mutableListOf<TagNoteTerm>()
        var inCode = false

        fun flush() {
            if (headingLine != null || currentTerms.isNotEmpty()) {
                groups.add(TagNoteGroup(currentName, headingLine, currentTerms.toList()))
            }
            currentTerms = mutableListOf()
        }

        lines.forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                inCode = !inCode
                return@forEachIndexed
            }
            if (inCode) return@forEachIndexed

            val heading = headingRegex.matchEntire(line)
            if (heading != null) {
                flush()
                // The note's title (`# ...`) is not a group; keywords below it stay ungrouped.
                currentName = if (heading.groupValues[1].length == 1) null else heading.groupValues[2].trim().ifEmpty { null }
                headingLine = index
                return@forEachIndexed
            }

            val item = itemRegex.matchEntire(line)?.groupValues?.get(1) ?: return@forEachIndexed
            if (item.startsWith("[")) return@forEachIndexed // a task, not a keyword
            val colon = item.indexOf(':')
            val labelPart = if (colon >= 0) item.substring(0, colon).trim() else ""
            val tagPart = if (colon >= 0) item.substring(colon + 1) else item
            val tags = tagRegex.findAll(tagPart).map { "#" + it.groupValues[1].trimEnd('/') }.distinctBy { it.lowercase() }.toList()
            if (tags.isEmpty()) {
                problems.add(NoteProblem(index, raw, NoteProblemKind.NO_TAG))
                return@forEachIndexed
            }
            if (tagRegex.replace(tagPart, "").replace(separatorRegex, "").isNotEmpty()) {
                problems.add(NoteProblem(index, raw, NoteProblemKind.UNKNOWN_TEXT))
            }
            val label = labelPart.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: tags.first().removePrefix("#")
            currentTerms.add(TagNoteTerm(TagTerm(label, tags), index))
        }
        flush()
        return TagNote(lines, groups, problems)
    }
}

/** A keyword and the index of its line in the note. */
data class TagNoteTerm(val term: TagTerm, val line: Int)

/** A heading of the note (or the part before the first heading) with its keywords. */
data class TagNoteGroup(val name: String?, val headingLine: Int?, val terms: List<TagNoteTerm>) {
    /** The line after which a new keyword of this group goes, or null when there is none. */
    val lastLine: Int? get() = terms.lastOrNull()?.line ?: headingLine
}

/** A keyword note as read: its lines, groups with line numbers, and lines with wrong syntax. */
data class TagNote(val lines: List<String>, val groups: List<TagNoteGroup>, val problems: List<NoteProblem>) {
    val definitions: TagDefinitions
        get() = TagDefinitions(groups.filter { it.terms.isNotEmpty() }.map { group -> TagGroup(group.name, group.terms.map { it.term }) })

    /** Groups the editor shows: named groups, and ungrouped keywords when there are any. */
    val editableGroups: List<TagNoteGroup> get() = groups.filter { it.name != null || it.terms.isNotEmpty() }
}

/** Reading and setting keywords in a todo's text. */
object TagText {
    private val tagRegex = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_/\-]+""")

    fun tagsIn(text: String): List<String> = tagRegex.findAll(text).map { it.value }.toList()

    fun hasTerm(text: String, term: TagTerm): Boolean = tagsIn(text).any { term.matches(it) }

    /**
     * Sets or clears [term] in [text]. Setting appends its primary tag and, in a named group,
     * removes the group's other keywords. Clearing removes every synonym of [term].
     */
    fun toggle(text: String, group: TagGroup, term: TagTerm): String {
        if (hasTerm(text, term)) return tidy(removeTags(text, term.tags))
        var result = text
        if (group.exclusive) {
            group.terms.filter { it != term }.forEach { other -> result = removeTags(result, other.tags) }
        }
        result = tidy(result)
        return if (result.isEmpty()) term.primaryTag else "$result ${term.primaryTag}"
    }

    private fun removeTags(text: String, tags: List<String>): String =
        tags.fold(text) { acc, tag ->
            Regex("""(^|\s)${Regex.escape(tag)}(?=\s|$)""", RegexOption.IGNORE_CASE).replace(acc, "$1")
        }

    private fun tidy(text: String): String = text.replace(Regex("""[ \t]{2,}"""), " ").trim()
}

/**
 * Line edits on a keyword note for the in-app editor. Each returns the new lines, or null when
 * the line to change is no longer in the note; everything else in the note stays unchanged.
 */
object TagNoteEdits {
    private val headingHashesRegex = Regex("""^(\s{0,3}#{1,6})\s""")
    private val titleRegex = Regex("""^\s{0,3}#\s+\S.*$""")

    fun termLine(term: TagTerm): String = "- ${term.label}: ${term.tags.joinToString(", ")}"

    fun addTerm(lines: List<String>, note: TagNote, group: TagNoteGroup?, term: TagTerm): List<String>? {
        val anchor = group?.lastLine
        if (anchor != null) return NoteLines.insertAfter(lines, anchor, note.lines[anchor], listOf(termLine(term)))
        // No ungrouped keywords yet: put the first one right below the title, or at the top.
        val title = lines.indexOfFirst { titleRegex.matches(it) }
        if (title >= 0) return lines.take(title + 1) + termLine(term) + lines.drop(title + 1)
        return listOf(termLine(term)) + lines
    }

    fun replaceTerm(lines: List<String>, note: TagNote, term: TagNoteTerm, updated: TagTerm): List<String>? {
        val original = note.lines[term.line]
        return NoteLines.replace(lines, term.line, original, NoteLines.withItemPrefix(original, termLine(updated).removePrefix("- ")))
    }

    fun removeTerm(lines: List<String>, note: TagNote, term: TagNoteTerm): List<String>? =
        NoteLines.remove(lines, listOf(term.line to note.lines[term.line]))

    fun addGroup(lines: List<String>, name: String): List<String> = NoteLines.append(lines, listOf("## $name"))

    fun renameGroup(lines: List<String>, note: TagNote, group: TagNoteGroup, name: String): List<String>? {
        val line = group.headingLine ?: return null
        val original = note.lines[line]
        val hashes = headingHashesRegex.find(original)?.groupValues?.get(1) ?: "##"
        return NoteLines.replace(lines, line, original, "$hashes $name")
    }

    /** Removes the group's heading and its keyword lines; other text below the heading stays. */
    fun removeGroup(lines: List<String>, note: TagNote, group: TagNoteGroup): List<String>? {
        val targets = listOfNotNull(group.headingLine) + group.terms.map { it.line }
        return NoteLines.remove(lines, targets.map { it to note.lines[it] })
    }

    /** Normalizes user input like `kurz, #5min  quick` to `#kurz`, `#5min`, `#quick`. */
    fun parseTagInput(input: String): List<String> =
        input.split(',', ' ', ';', '\n')
            .map { it.trim().trimStart('#') }
            .filter { it.isNotEmpty() }
            .map { "#" + it.replace(Regex("""[^\p{L}\p{N}_/\-]"""), "") }
            .filter { it.length > 1 && it.drop(1).any { c -> !c.isDigit() } }
            .distinctBy { it.lowercase() }
}
