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
 * A heading starts a group. Each list item is one keyword: the text before `:` is its label, the
 * tags after it are synonyms, the first one is written by the app. Without a label the first tag
 * is used as label. Lines without tags and code blocks are ignored.
 */
object TagDefinitionParser {
    private val headingRegex = Regex("""^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$""")
    private val itemRegex = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(.*)$""")
    private val tagRegex = Regex("""(?<![\p{L}\p{N}_])#([\p{L}\p{N}_/\-]*[\p{L}_/\-][\p{L}\p{N}_/\-]*)""")

    fun parse(lines: List<String>): TagDefinitions {
        val groups = mutableListOf<TagGroup>()
        var currentName: String? = null
        var currentTerms = mutableListOf<TagTerm>()
        var inCode = false

        fun flush() {
            if (currentTerms.isNotEmpty()) groups.add(TagGroup(currentName, currentTerms.toList()))
            currentTerms = mutableListOf()
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                inCode = !inCode
                continue
            }
            if (inCode) continue

            val heading = headingRegex.matchEntire(line)
            if (heading != null) {
                flush()
                currentName = heading.groupValues[1].trim().ifEmpty { null }
                continue
            }

            val item = itemRegex.matchEntire(line)?.groupValues?.get(1) ?: continue
            if (item.startsWith("[")) continue // a task, not a keyword
            val colon = item.indexOf(':')
            val labelPart = if (colon >= 0) item.substring(0, colon).trim() else ""
            val tagPart = if (colon >= 0) item.substring(colon + 1) else item
            val tags = tagRegex.findAll(tagPart).map { "#" + it.groupValues[1].trimEnd('/') }.distinctBy { it.lowercase() }.toList()
            if (tags.isEmpty()) continue
            val label = labelPart.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: tags.first().removePrefix("#")
            currentTerms.add(TagTerm(label, tags))
        }
        flush()
        return TagDefinitions(groups)
    }
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
