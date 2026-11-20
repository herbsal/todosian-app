package com.isotjs.todosian.utils

/** A list (note) named in a quick task, e.g. "@Haushalt" or "auf die Einkaufsliste". */
data class ListHit(val name: String, val start: Int, val end: Int)

/**
 * Finds the list a quick task should go to. Only names of existing lists count, and only
 * with "@" or a signal word before them, so dictated text works without the "@":
 * "@Haushalt", "in Haushalt", "auf die Einkaufsliste", "Liste Kinder", "to my Shopping list".
 * Without such a word nothing is recognized, so "Garten gießen" stays a plain task.
 */
object ListRecognition {
    private const val B = """(?<![\p{L}\p{N}_])"""
    private const val E = """(?![\p{L}\p{N}_])"""
    private const val SIGNAL = """(?:in|ins|auf|zu|zur|zum|nach|to|on|into)"""
    private const val ARTICLE = """(?:(?:die|der|den|das|dem|meine|meiner|meinen|meinem|unsere|unserer|the|my|our)\s+)?"""
    private const val LIST_WORD = """(?:liste|list|notiz|note)"""

    fun find(text: String, listNames: List<String>): ListHit? {
        if (text.isBlank()) return null
        val hits = listNames.filter { it.isNotBlank() }.distinct().flatMap { name ->
            val n = name.trim().split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
            val patterns = listOf(
                """@$n$E""",
                """$B$SIGNAL\s+$ARTICLE(?:$LIST_WORD\s+)?$n(?:s?-?$LIST_WORD|[\s-]+$LIST_WORD)?$E""",
                """$B$LIST_WORD\s+$n$E""",
            )
            patterns.flatMap { p ->
                Regex(p, RegexOption.IGNORE_CASE).findAll(text).map { ListHit(name, it.range.first, it.range.last + 1) }.toList()
            }
        }
        // The longest name wins ("Einkauf Baumarkt" over "Einkauf"), then the earliest place.
        return hits.sortedWith(compareByDescending<ListHit> { it.name.length }.thenBy { it.start }).firstOrNull()
    }

    /** The task text without the words naming the list. */
    fun remove(text: String, hit: ListHit): String =
        text.removeRange(hit.start, hit.end).replace(Regex("""\s+([,.;:!?])"""), "$1")
            .replace(Regex("""[ \t]{2,}"""), " ").trim().trim(',', ';', ' ')
}
