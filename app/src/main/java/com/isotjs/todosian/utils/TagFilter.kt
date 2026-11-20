package com.isotjs.todosian.utils

/**
 * One chip of the keyword filter: a keyword from the keyword note (with all its synonyms), or a
 * tag that only appears in the tasks. [group] is the keyword's named group, if any.
 */
data class FilterTerm(val key: String, val label: String, val group: String?, val tags: List<String>) {
    fun matches(tagsInText: List<String>): Boolean = tagsInText.any { tag -> tags.any { it.equals(tag, ignoreCase = true) } }
}

/**
 * Filtering tasks by keyword. Selected keywords of the same group are alternatives (Effort: 5 min
 * or 30 min), keywords of different groups must all apply (5 min and low energy).
 */
object TagFilter {
    /**
     * The chips for [texts], each with the keyword definitions of its folder: defined keywords in
     * the order of the keyword note, then other tags A–Z. Only keywords used in a task are listed.
     */
    fun termsFor(texts: List<Pair<String, TagDefinitions>>): List<FilterTerm> {
        val defined = LinkedHashMap<String, FilterTerm>()
        val others = sortedMapOf<String, FilterTerm>(String.CASE_INSENSITIVE_ORDER)
        val definitionsInOrder = texts.map { it.second }.distinct()
        // Defined keywords first, in note order, so the chips look the same every time.
        val usedTags = texts.flatMap { (text, _) -> TagText.tagsIn(text) }.map { it.lowercase() }.toSet()
        definitionsInOrder.forEach { definitions ->
            definitions.groups.forEach { group ->
                group.terms.forEach { term ->
                    if (term.tags.none { it.lowercase() in usedTags }) return@forEach
                    val key = term.primaryTag.lowercase()
                    defined.getOrPut(key) { FilterTerm(key, term.label, group.name, term.tags) }
                }
            }
        }
        texts.forEach { (text, definitions) ->
            TagText.tagsIn(text).forEach { tag ->
                if (definitions.termFor(tag) != null) return@forEach
                val key = tag.lowercase()
                if (key in defined || defined.values.any { it.matches(listOf(tag)) }) return@forEach
                others.getOrPut(key) { FilterTerm(key, tag.removePrefix("#"), null, listOf(tag)) }
            }
        }
        return defined.values.toList() + others.values.toList()
    }

    /** Filter terms for [tags] as written in a saved view, with synonyms and groups from [definitions]. */
    fun termsForTags(tags: List<String>, definitions: TagDefinitions): List<FilterTerm> =
        tags.map { tag ->
            val group = definitions.groups.firstOrNull { g -> g.terms.any { it.matches(tag) } }
            val term = group?.terms?.first { it.matches(tag) }
            if (term != null) {
                FilterTerm(term.primaryTag.lowercase(), term.label, group.name, term.tags)
            } else {
                FilterTerm(tag.lowercase(), tag.removePrefix("#"), null, listOf(tag))
            }
        }.distinctBy { it.key }

    fun matches(text: String, selected: List<FilterTerm>): Boolean {
        if (selected.isEmpty()) return true
        val tags = TagText.tagsIn(text)
        return selected.groupBy { it.group ?: it.key }.values.all { alternatives -> alternatives.any { it.matches(tags) } }
    }
}
