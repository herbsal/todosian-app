package com.isotjs.todosian.utils

/**
 * A saved view: a named keyword filter, e.g. "Small tasks" = `#5min`. [folder] is the folder
 * whose views note defines it; its tasks come from all folders.
 */
data class SavedView(val name: String, val terms: List<FilterTerm>, val folder: String)

/**
 * Reads the views note (`Todosian/Views.md`). Each list item is one view, in
 * the same syntax as a keyword: `- Small and tired: #5min, #energy/low`. Its tags are resolved
 * with the folder's keyword note, so synonyms count; keywords of one group are alternatives,
 * keywords of different groups must all apply.
 */
object SavedViews {
    fun parse(lines: List<String>, definitions: TagDefinitions, folder: String): List<SavedView> =
        TagDefinitionParser.parseNote(lines).groups.flatMap { it.terms }.map { entry ->
            SavedView(entry.term.label, TagFilter.termsForTags(entry.term.tags, definitions), folder)
        }
}
