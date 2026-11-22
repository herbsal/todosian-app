package com.isotjs.todosian.utils

/**
 * Whose tasks remind on this device, for one folder. People are the keywords of the keyword
 * group [group] (e.g. "Person" with `#person/michael`). A task assigned to one of [persons]
 * (primary tags) reminds; a task without any person of the group reminds when
 * [includeUnassigned] is set. Stored per device, so everyone sharing a folder decides alone.
 */
data class PersonScope(val group: String, val persons: Set<String>, val includeUnassigned: Boolean) {

    fun allows(text: String, definitions: TagDefinitions): Boolean {
        val terms = definitions.groups.firstOrNull { it.name.equals(group, ignoreCase = true) }?.terms ?: return true
        val tags = TagText.tagsIn(text)
        val assigned = terms.filter { term -> tags.any { term.matches(it) } }
        if (assigned.isEmpty()) return includeUnassigned
        return assigned.any { it.primaryTag.lowercase() in persons }
    }

    fun encode(): String = (listOf(group, if (includeUnassigned) "1" else "0") + persons.sorted()).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "\u001F"

        fun decode(value: String?): PersonScope? {
            val parts = value?.split(SEPARATOR) ?: return null
            if (parts.size < 2 || parts[0].isBlank()) return null
            return PersonScope(parts[0], parts.drop(2).filter { it.isNotEmpty() }.toSet(), parts[1] == "1")
        }
    }
}
