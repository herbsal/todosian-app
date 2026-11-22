package com.isotjs.todosian.utils.ai

import com.isotjs.todosian.utils.REMINDER_MARKER
import com.isotjs.todosian.utils.TASK_ID_MARKER
import com.isotjs.todosian.utils.TaskMarkers

/**
 * Keeps the parts of a task the AI must not touch away from it: tags, list words, Tasks fields
 * with their values, links and code are replaced by placeholders `⟦1⟧`, `⟦2⟧` … before the text goes
 * to the model, and put back afterwards. An answer that lost, doubled or made up a placeholder is
 * rejected, so the task stays as it was.
 */
object AiTextGuard {

    /** [text] as the model sees it; [tokens] are the protected parts, placeholder n = tokens[n - 1]. */
    data class Masked(val text: String, val tokens: List<String>, val trailing: Set<Int>) {
        fun placeholder(index: Int) = "$OPEN${index + 1}$CLOSE"

        /** [text] without the placeholders at its end: for models that should only see the words. */
        val textWithoutTrailing: String
            get() = trailing.fold(text) { acc, index -> acc.replace(placeholder(index), " ") }
                .replace(Regex("""[ \t]+"""), " ").trim()
    }

    private const val OPEN = "⟦"
    private const val CLOSE = "⟧"

    /** Answers may come out a bit longer ("Zahnarzt Termin" → "Zahnarzttermin" is shorter, but quotes, a full stop …). */
    private const val LENGTH_SLACK = 6

    private val PRIORITY_MARKERS = listOf("🔺", "⏫", "🔼", "🔽", "⏬")
    private val OTHER_FIELD_MARKERS = listOf(REMINDER_MARKER, TASK_ID_MARKER, "⛔", "🏁", "❌")

    private val inlineCode = Regex("""`[^`\n]*`""")
    private val wikiLink = Regex("""!?\[\[[^\]\n]*]]""")
    private val markdownLink = Regex("""!?\[[^\]\n]*]\([^)\n]*\)""")
    private val url = Regex("""https?://\S+""")
    private val tag = Regex("""(?<![\p{L}\p{N}_&/])#[\p{L}\p{N}_/\-]+""")
    private val listWord = Regex("""(?<!\S)@[\p{L}\p{N}_/\-]+""")
    private val placeholder = Regex("""⟦(\d+)⟧""")
    private val quotes = setOf('"', '\'', '„', '“', '”', '‚', '‘', '’', '«', '»')

    fun mask(text: String, markers: TaskMarkers = TaskMarkers.Default): Masked {
        val fieldMarkers = (
            listOf(markers.created, markers.start, markers.scheduled, markers.due, markers.done, markers.recurrence) +
                OTHER_FIELD_MARKERS + PRIORITY_MARKERS
            ).filter { it.isNotBlank() }.distinct()
        val markerAlternation = fieldMarkers.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        // A field runs from its marker up to the next marker or tag: "📅 2026-11-24", "🔁 every week".
        val field = Regex("""(?:$markerAlternation)\x{FE0F}?(?:(?!$markerAlternation|#|⟦).)*""")

        val taken = mutableListOf<IntRange>()
        fun collect(regex: Regex, trimEnd: Boolean = false) {
            regex.findAll(text).forEach { match ->
                var range = match.range
                if (trimEnd) {
                    val trimmed = match.value.trimEnd()
                    if (trimmed.isEmpty()) return@forEach
                    range = range.first until range.first + trimmed.length
                }
                if (taken.none { it.first <= range.last && range.first <= it.last }) taken += range
            }
        }
        collect(inlineCode)
        collect(wikiLink)
        collect(markdownLink)
        collect(url)
        collect(field, trimEnd = true)
        collect(tag)
        collect(listWord)
        // Text that already looks like a placeholder is protected too, so it can't be confused.
        collect(placeholder)

        val ranges = taken.sortedBy { it.first }
        val tokens = ranges.map { text.substring(it) }
        val masked = StringBuilder()
        var last = 0
        ranges.forEachIndexed { index, range ->
            masked.append(text, last, range.first)
            masked.append("$OPEN${index + 1}$CLOSE")
            last = range.last + 1
        }
        masked.append(text, last, text.length)

        // Protected parts after the last plain word (fields and tags at the end of the line) go
        // back to the end, in their order, wherever the model puts them: the Tasks format needs it.
        val lastWordEnd = ranges.indices.reversed().firstOrNull { index ->
            val gapStart = ranges[index].last + 1
            val gapEnd = ranges.getOrNull(index + 1)?.first ?: text.length
            text.substring(gapStart, gapEnd).any { it.isLetterOrDigit() }
        }
        val trailing = if (lastWordEnd == null) {
            // No word after any token: trailing ones are those after the first word.
            val firstWordBefore = ranges.indexOfFirst { range -> text.substring(0, range.first).any { it.isLetterOrDigit() } }
            if (firstWordBefore < 0) emptySet() else (firstWordBefore until ranges.size).toSet()
        } else {
            ((lastWordEnd + 1) until ranges.size).toSet()
        }
        return Masked(masked.toString(), tokens, trailing)
    }

    /**
     * The model's [answer] with the protected parts put back, or null when it can't be trusted:
     * a placeholder missing, doubled or new, a line break inside, nothing but placeholders left,
     * or clearly longer than the text it got.
     */
    fun restore(answer: String, masked: Masked, trailingOmitted: Boolean = false): String? {
        var cleaned = answer.trim()
        while (cleaned.length >= 2 && cleaned.first() in quotes && cleaned.last() in quotes) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }
        if (cleaned.isEmpty() || cleaned.contains('\n') || cleaned.contains('\r')) return null
        if (cleaned.length > masked.text.trim().length + LENGTH_SLACK) return null

        val found = placeholder.findAll(cleaned).map { it.groupValues[1].toIntOrNull() ?: -1 }.toList()
        val expected = masked.tokens.indices.filter { !trailingOmitted || it !in masked.trailing }.map { it + 1 }
        if (found.sorted() != expected) return null
        if (cleaned.count { it == OPEN.single() } != expected.size || cleaned.count { it == CLOSE.single() } != expected.size) return null
        if (!placeholder.replace(cleaned, "").any { it.isLetterOrDigit() }) return null

        // Trailing parts leave their place and are added at the end again, in their order.
        var body = cleaned
        masked.trailing.forEach { index -> body = body.replace(masked.placeholder(index), " ") }
        body = normalizeSpaces(body)
        val tail = masked.trailing.sorted().joinToString(" ") { masked.placeholder(it) }
        val withTail = if (tail.isEmpty()) body else "$body $tail"

        val restored = placeholder.replace(withTail) { match -> masked.tokens[match.groupValues[1].toInt() - 1] }
        return normalizeSpaces(restored)
    }

    /** True when the cleaned text is the same as [original], apart from spaces. */
    fun isUnchanged(original: String, cleaned: String): Boolean = normalizeSpaces(original) == normalizeSpaces(cleaned)

    private fun normalizeSpaces(text: String): String =
        text.replace(Regex("""[ \t]+"""), " ")
            .replace(Regex(""" +([,.;:!?])"""), "$1")
            .trim()
}
