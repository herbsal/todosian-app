package com.isotjs.todosian.utils

/** Text shared from another app ("Share" in a messenger or browser), turned into one task line. */
object SharedText {
    private const val MAX_LENGTH = 1000
    private val urlRegex = Regex("""^https?://\S+$""")

    /**
     * One line for a new task. Browsers share the page title as [subject] and the link as [text];
     * both are kept. Line breaks become spaces, because a task is a single line in the note.
     */
    fun toTaskText(subject: String?, text: String?): String? {
        val body = text.orEmpty().oneLine()
        val title = subject.orEmpty().oneLine()
        val combined = when {
            body.isEmpty() -> title
            title.isEmpty() || body.contains(title, ignoreCase = true) -> body
            urlRegex.matches(body) -> "$title $body"
            else -> body
        }
        return combined.take(MAX_LENGTH).trim().ifEmpty { null }
    }

    private fun String.oneLine(): String =
        lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            // A leading list marker or checkbox would end up doubled in the note.
            .replaceFirst(Regex("""^(?:[-*+]\s+)?(?:\[[ xX]]\s+)?"""), "")
            .trim()
}
