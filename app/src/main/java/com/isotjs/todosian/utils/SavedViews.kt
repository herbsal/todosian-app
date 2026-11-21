package com.isotjs.todosian.utils

/**
 * A saved view: a named keyword filter, e.g. "Small tasks" = `#5min`. [folder] is the folder
 * whose views note defines it; its tasks come from all folders.
 *
 * A task matches when it matches any of the [alternatives] ("or"); within one alternative all
 * selected keywords must apply ("and"), except that keywords of the same group are alternatives.
 */
data class SavedView(val name: String, val alternatives: List<List<FilterTerm>>, val folder: String) {
    /** All keywords of the view. */
    val terms: List<FilterTerm> get() = alternatives.flatten().distinctBy { it.key }

    fun matches(text: String): Boolean = alternatives.isEmpty() || alternatives.any { TagFilter.matches(text, it) }
}

/**
 * Reads the views note (`Todosian/Views.md`). Each list item is one view, in
 * the same syntax as a keyword: `- Small and tired: #5min, #energy/low`. Its tags are resolved
 * with the folder's keyword note, so synonyms count; keywords of one group are alternatives,
 * keywords of different groups must all apply. `|` (or the word "oder"/"or") separates
 * alternatives: `- Quick: #5min, #energy/low | #15min`.
 */
object SavedViews {
    private val alternativeSeparator = Regex("""\s*\|\s*|\s+(?:oder|or)\s+""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<String>, definitions: TagDefinitions, folder: String): List<SavedView> {
        val note = TagDefinitionParser.parseNote(lines)
        return note.groups.flatMap { it.terms }.map { entry ->
            val alternatives = alternativesOf(note.lines[entry.line]).ifEmpty { listOf(entry.term.tags) }
            SavedView(entry.term.label, alternatives.map { TagFilter.termsForTags(it, definitions) }, folder)
        }
    }

    /** The tags of each alternative of a view line, e.g. `[[#5min, #energy/low], [#15min]]`. */
    fun alternativesOf(line: String): List<List<String>> {
        val item = line.substringAfter(':', line)
        return item.split(alternativeSeparator)
            .map { part -> TagText.tagsIn(part).map { it.trimEnd('/') }.distinctBy { it.lowercase() } }
            .filter { it.isNotEmpty() }
    }

    /** The line of a view: `- Quick: #5min, #energy/low | #15min`. */
    fun line(name: String, alternatives: List<List<String>>): String =
        "- ${name.replace(":", "").trim()}: " +
            alternatives.filter { it.isNotEmpty() }.joinToString(" | ") { it.joinToString(", ") }
}
