package com.isotjs.todosian.utils

/** How the tags of one keyword group appear in a task row. */
enum class TagDisplayMode { CHIP, TEXT, HIDDEN }

/** A keyword shown as a small badge below the task title, e.g. ⏱ 5 Min. */
data class TagBadge(val icon: String, val label: String)

/** The task title without the tags shown as badges or hidden, and the badges. */
data class TagDisplayResult(val title: String, val badges: List<TagBadge>)

/**
 * Per-device choice how tags appear: per keyword group (by name, case-insensitive) and for tags
 * not defined in the keyword note. Defined keywords default to badges, other tags stay text.
 */
data class TagDisplaySettings(
    val groups: Map<String, TagDisplayMode> = emptyMap(),
    val otherTags: TagDisplayMode = TagDisplayMode.TEXT,
) {
    fun modeFor(group: TagGroup): TagDisplayMode = groups[keyOf(group)] ?: TagDisplayMode.CHIP

    fun withGroup(group: TagGroup, mode: TagDisplayMode): TagDisplaySettings = copy(groups = groups + (keyOf(group) to mode))

    fun encode(): String =
        (groups.map { (key, mode) -> "$key=$mode" } + "$OTHER_KEY=$otherTags").joinToString(SEPARATOR.toString())

    companion object {
        private const val SEPARATOR = '\u001F'
        private const val OTHER_KEY = "\u0000other"
        private const val UNGROUPED_KEY = "\u0000ungrouped"

        fun keyOf(group: TagGroup): String = group.name?.lowercase() ?: UNGROUPED_KEY

        fun decode(raw: String?): TagDisplaySettings {
            if (raw.isNullOrEmpty()) return TagDisplaySettings()
            var other = TagDisplayMode.TEXT
            val groups = mutableMapOf<String, TagDisplayMode>()
            raw.split(SEPARATOR).forEach { entry ->
                val key = entry.substringBeforeLast('=', "")
                val mode = runCatching { TagDisplayMode.valueOf(entry.substringAfterLast('=')) }.getOrNull() ?: return@forEach
                if (key == OTHER_KEY) other = mode else if (key.isNotEmpty()) groups[key] = mode
            }
            return TagDisplaySettings(groups, other)
        }
    }
}

object TagDisplay {
    private val tagRegex = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_/\-]*[\p{L}_/\-][\p{L}\p{N}_/\-]*""")
    private const val OTHER_ICON = "🏷"

    /** The symbol of a group: the one from its heading, else a guess from its name. */
    fun iconFor(group: TagGroup): String {
        group.icon?.let { return it }
        val name = group.name?.lowercase().orEmpty()
        return when {
            listOf("aufwand", "effort", "dauer", "zeit", "time", "duration").any { name.contains(it) } -> "⏱"
            listOf("energie", "energy").any { name.contains(it) } -> "🔋"
            listOf("person", "wer", "who", "people", "leute", "zuständig").any { name.contains(it) } -> "👤"
            listOf("ort", "place", "location", "kontext", "context").any { name.contains(it) } -> "📍"
            listOf("prio", "wichtig").any { name.contains(it) } -> "⭐"
            else -> OTHER_ICON
        }
    }

    /**
     * One line for places without badges, such as notifications: the title as in the app, then
     * the badges as plain text, e.g. "Altglas wegbringen · ⏱ 5 Min · 🔋 Niedrig".
     */
    fun plainText(text: String, definitions: TagDefinitions, settings: TagDisplaySettings): String {
        val result = split(text, definitions, settings)
        if (result.badges.isEmpty()) return result.title
        return (listOf(result.title) + result.badges.map { "${it.icon} ${it.label}" }).joinToString(" · ")
    }

    fun split(text: String, definitions: TagDefinitions, settings: TagDisplaySettings): TagDisplayResult {
        val badges = mutableListOf<TagBadge>()
        val title = tagRegex.replace(text) { match ->
            val tag = match.value
            val group = definitions.groups.firstOrNull { g -> g.terms.any { it.matches(tag) } }
            val mode = if (group != null) settings.modeFor(group) else settings.otherTags
            when (mode) {
                TagDisplayMode.TEXT -> tag
                TagDisplayMode.HIDDEN -> ""
                TagDisplayMode.CHIP -> {
                    val badge = if (group != null) {
                        TagBadge(iconFor(group), group.terms.first { it.matches(tag) }.label)
                    } else {
                        TagBadge(OTHER_ICON, tag.removePrefix("#"))
                    }
                    if (badge !in badges) badges += badge
                    ""
                }
            }
        }.replace(Regex("""[ \t]{2,}"""), " ").trim()
        // A title made only of tags keeps its text, so the row is never empty.
        return TagDisplayResult(title.ifEmpty { text.trim() }, badges)
    }
}
