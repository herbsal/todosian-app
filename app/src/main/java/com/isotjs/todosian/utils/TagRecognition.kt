package com.isotjs.todosian.utils

/** A tag of the tag note found in words in a new task, e.g. "5 Minuten" for `#5min`. */
data class TagHit(val group: TagGroup, val term: TagTerm, val start: Int, val end: Int) {
    val phrase: IntRange get() = start until end
}

/** Text after [TagRecognition.settle]: [replaced] maps each tag set to the words it came from. */
data class SettledTags(val text: String, val cursor: Int, val replaced: Map<String, String>)

/**
 * Finds tags of the tag note written as plain words, e.g. dictated: "Alex Müll rausbringen
 * 5 Minuten wenig Energie" finds 👤 Alex, ⏱ 5 Min and 🔋 Niedrig. Only tags the note defines
 * are found. Words that match:
 * - the term's name ("Alex", "5 Min") and its tags without # ("5min", "energie niedrig"),
 * - durations in other spellings ("5 Minuten", "2 Std.", "halbe Stunde"),
 * - name and group together, also with common words ("Energie niedrig", "wenig Energie"),
 * - the user's own words for the term in the note ("dreißig Minuten").
 *
 * In a group where only one tag may be set, only the first one found counts.
 */
object TagRecognition {
    private const val B = """(?<![\p{L}\p{N}_#/])"""
    private const val E = """(?![\p{L}\p{N}_])"""

    private val durationRegex = Regex("""^(\d+(?:[.,]\d+)?)\s*([\p{L}.]+)$""")
    private val minuteUnits = listOf("min", "min.", "mins", "minute", "minuten", "minutes", "m")
    private val hourUnits = listOf("h", "std", "std.", "stunde", "stunden", "hour", "hours", "hr", "hrs")
    private val minuteWords = listOf("minuten", "min", "minutes", "minute", "mins")
    private val hourWords = listOf("stunden", "stunde", "std", "hours", "hour", "h")

    /** Words for low/high, used together with the group name: "wenig Energie". */
    private val levelWords = mapOf(
        "niedrig" to listOf("wenig", "niedrige", "niedriger", "niedrig", "geringe", "low", "little"),
        "low" to listOf("wenig", "niedrige", "niedriger", "niedrig", "geringe", "low", "little"),
        "hoch" to listOf("viel", "hohe", "hoher", "hoch", "volle", "high", "lots of"),
        "high" to listOf("viel", "hohe", "hoher", "hoch", "volle", "high", "lots of"),
        "mittel" to listOf("mittlere", "mittel", "medium"),
        "medium" to listOf("mittlere", "mittel", "medium"),
    )

    fun find(text: String, definitions: TagDefinitions): List<TagHit> {
        if (definitions.isEmpty || text.isBlank()) return emptyList()
        val candidates = definitions.groups.flatMap { group ->
            group.terms.flatMap { term ->
                if (TagText.hasTerm(text, term)) emptyList()
                else phrasesFor(group, term).flatMap { phrase -> occurrences(text, phrase).map { TagHit(group, term, it.first, it.last + 1) } }
            }
        }
        // Longest phrase first, then the earliest; never two on the same words.
        val hits = mutableListOf<TagHit>()
        candidates.sortedWith(compareByDescending<TagHit> { it.end - it.start }.thenBy { it.start }).forEach { hit ->
            if (hits.any { it.start < hit.end && hit.start < it.end }) return@forEach
            if (hits.any { it.term == hit.term }) return@forEach
            if (hit.group.exclusive && hits.any { it.group == hit.group }) return@forEach
            if (isDurationAfterIn(text, hit)) return@forEach
            hits += hit
        }
        return hits.sortedBy { it.start }
    }

    /**
     * Sets the found tags in [text]. With [removeWords] the found words leave the title, except
     * people's names, which usually belong to the sentence ("Alex anrufen").
     */
    fun apply(text: String, hits: List<TagHit>, removeWords: Boolean): String {
        var result = text
        if (removeWords) {
            hits.filter { !it.group.isPerson }.sortedByDescending { it.start }.forEach { hit ->
                if (hit.end <= result.length) result = result.removeRange(hit.start, hit.end)
            }
            result = result.replace(Regex("""\s+([,.;:!?])"""), "$1").replace(Regex("""[ \t]{2,}"""), " ").trim()
                .trim(',', ';', ' ')
        }
        hits.forEach { hit ->
            if (!TagText.hasTerm(result, hit.term)) result = TagText.toggle(result, hit.group, hit.term)
        }
        return result
    }

    /**
     * Turns finished tag words into the tag right in the text while typing: "5 Minuten " becomes
     * "#5min ". A phrase counts as finished once something other than a letter or digit follows it
     * and the cursor is not on it, so words still being typed stay. With [removeWords] the words
     * give way to the tag; otherwise, and always for people ("Alex anrufen"), the tag follows them.
     * A new tag of a group with one tag at a time replaces the group's tag already in the text.
     * With [finishedOnly] false all tag words count, e.g. when leaving the field.
     * Returns null when nothing changes.
     */
    fun settle(
        text: String,
        cursor: Int,
        definitions: TagDefinitions,
        ignoredTags: Set<String> = emptySet(),
        removeWords: Boolean = true,
        finishedOnly: Boolean = true,
    ): SettledTags? {
        val hits = find(text, definitions).filter { hit ->
            hit.term.primaryTag !in ignoredTags && (
                !finishedOnly ||
                    hit.end < text.length && !text[hit.end].isLetterOrDigit() && (cursor < hit.start || cursor > hit.end)
                )
        }
        if (hits.isEmpty()) return null
        var result = text
        var c = cursor.coerceIn(0, text.length)
        val replaced = mutableMapOf<String, String>()
        hits.sortedByDescending { it.start }.forEach { hit ->
            val phrase = result.substring(hit.start, hit.end)
            val tag = hit.term.primaryTag
            val replace = removeWords && !hit.group.isPerson
            if (replace) {
                result = result.replaceRange(hit.start, hit.end, tag)
                if (c >= hit.end) c += tag.length - phrase.length else if (c > hit.start) c = hit.start + tag.length
            } else {
                result = result.substring(0, hit.end) + " " + tag + result.substring(hit.end)
                if (c >= hit.end) c += tag.length + 1
            }
            replaced[tag] = phrase
        }
        // One tag at a time in such a group: the words just written win.
        hits.filter { it.group.exclusive }.forEach { hit ->
            hit.group.terms.filter { it != hit.term }.flatMap { it.tags }.forEach { other ->
                tagToken(other).findAll(result).toList().asReversed().forEach { m ->
                    val start = if (m.range.first > 0 && result[m.range.first - 1] == ' ') m.range.first - 1 else m.range.first
                    val end = m.range.last + 1
                    result = result.removeRange(start, end)
                    if (c >= end) c -= end - start else if (c > start) c = start
                }
            }
        }
        return SettledTags(result, c.coerceIn(0, result.length), replaced)
    }

    /**
     * Undoes [settle] for one tag: the tag leaves the text and, where it took the place of
     * [phrase], the words come back there.
     */
    fun unsettle(text: String, tag: String, phrase: String?): String {
        val m = tagToken(tag).find(text) ?: return text
        val before = text.substring(0, m.range.first)
        val after = text.substring(m.range.last + 1)
        val restored = if (phrase == null || before.trimEnd().endsWith(phrase, ignoreCase = true)) {
            before.removeSuffix(" ") + after
        } else {
            before + phrase + after
        }
        return restored.replace(Regex("""[ \t]{2,}"""), " ").trimStart()
    }

    private fun tagToken(tag: String): Regex =
        Regex("""(?<![\p{L}\p{N}_])${Regex.escape(tag)}(?![\p{L}\p{N}_/\-])""", RegexOption.IGNORE_CASE)

    internal fun phrasesFor(group: TagGroup, term: TagTerm): List<String> {
        val words = mutableSetOf<String>()
        val bases = (listOf(term.label) + term.tags.map { tagWords(it) }).map { it.trim() }.filter { it.length >= 2 }
        // A bare "niedrig" or "hoch" says too little; those need the group name ("Energie niedrig").
        words += bases.filter { it.lowercase() !in levelWords }
        bases.forEach { words += durationSpellings(it) }
        words += term.words
        val name = group.name?.trim()?.takeIf { it.isNotEmpty() && !group.isPerson }
        if (name != null) {
            val levels = (listOf(term.label) + term.tags.map { it.substringAfterLast('/').removePrefix("#") })
                .map { it.lowercase() }.distinct()
            levels.forEach { level ->
                words += "$name $level"
                words += "$level $name"
                levelWords[level]?.forEach { words += "$it $name" }
            }
        }
        return words.filter { it.isNotBlank() }
    }

    /** "#energie/niedrig" is written "energie niedrig", "#person/alex" only by the person's name. */
    private fun tagWords(tag: String): String {
        val plain = tag.removePrefix("#")
        return if (plain.startsWith("person/", ignoreCase = true)) plain.substringAfter('/') else plain.replace(Regex("[/_-]"), " ")
    }

    private fun durationSpellings(base: String): List<String> {
        val compact = base.lowercase().replace(" ", "")
        val match = durationRegex.matchEntire(compact) ?: return emptyList()
        val number = match.groupValues[1]
        val unit = match.groupValues[2]
        val units = when (unit) {
            in minuteUnits -> minuteWords
            in hourUnits -> hourWords
            else -> return emptyList()
        }
        val spellings = units.flatMap { listOf("$number $it", "$number$it") }.toMutableList()
        if (units === minuteWords && number == "30") spellings += listOf("halbe Stunde", "half an hour", "half hour")
        if (units === minuteWords && number == "15") spellings += listOf("Viertelstunde", "quarter hour")
        if (units === hourWords && number == "1") spellings += listOf("eine Stunde", "one hour", "an hour")
        if (units === hourWords && number == "2") spellings += listOf("zwei Stunden", "two hours")
        return spellings
    }

    private fun occurrences(text: String, phrase: String): List<IntRange> {
        val pattern = phrase.trim().split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
        val trailingDot = if (phrase.endsWith(".")) "" else """\.?"""
        return Regex("$B$pattern$trailingDot$E", RegexOption.IGNORE_CASE).findAll(text).map { it.range }
            // A trailing dot belongs to the phrase only for abbreviations like "Std.", not at a sentence end.
            .map { r -> if (text[r.last] == '.' && !phrase.endsWith(".") && !looksAbbreviated(text, r)) r.first..(r.last - 1) else r }
            .toList()
    }

    private fun looksAbbreviated(text: String, range: IntRange): Boolean {
        val word = text.substring(range).trimEnd('.').substringAfterLast(' ').lowercase()
        return word in listOf("min", "std", "h", "hr", "hrs", "mins")
    }

    /** "in 2 Stunden" is a time ("in two hours"), not an effort. */
    private fun isDurationAfterIn(text: String, hit: TagHit): Boolean {
        if (!text.substring(hit.start, hit.end).first().isDigit()) return false
        val before = text.substring(0, hit.start).trimEnd().substringAfterLast(' ').lowercase()
        return before == "in"
    }
}
