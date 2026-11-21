package com.isotjs.todosian.utils

import java.net.URLEncoder

/**
 * Turns Obsidian Markdown into plain CommonMark for the note preview. Only the preview uses it;
 * the note itself is never changed.
 *
 * - `![[Bild.png|300]]` and `![](Bilder/Bild.png)` become images, when [resolveFile] finds the file
 * - `[[Notiz]]`, `[[Notiz|Name]]` and `[[Notiz#Abschnitt]]` become links to [NOTE_LINK_SCHEME]
 * - callouts (`> [!tip] Titel`) become quotes with a bold title
 * - front matter and `%%comments%%` are left out
 */
object ObsidianMarkdown {
    /** Links to other notes: `todosian-note:<name>`, the name URL-encoded. */
    const val NOTE_LINK_SCHEME = "todosian-note"

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif", "heic")
    private val EMBED = Regex("""!\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]*))?]]""")
    private val WIKILINK = Regex("""(?<!!)\[\[([^\]|#]+)?(#[^\]|]*)?(?:\|([^\]]*))?]]""")
    private val MARKDOWN_IMAGE = Regex("""!\[([^\]]*)]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")
    private val CALLOUT = Regex("""^(\s*(?:>\s*)+)\[!([\w-]+)][+-]?\s*(.*)$""")
    private val COMMENT = Regex("""%%.*?%%""", RegexOption.DOT_MATCHES_ALL)

    /**
     * [resolveFile] gets a file name or path as written in the note and returns a URI the image
     * loader can open, or null.
     */
    fun prepare(text: String, resolveFile: (String) -> String?): String {
        val body = COMMENT.replace(stripFrontMatter(text), "")
        return body.lines().joinToString("\n") { line -> prepareLine(line, resolveFile) }
    }

    private fun prepareLine(line: String, resolveFile: (String) -> String?): String {
        var result = CALLOUT.find(line)?.let { match ->
            val (quote, type, title) = match.destructured
            val heading = title.ifBlank { type.replaceFirstChar { it.uppercase() } }
            "$quote**${heading.trim()}**"
        } ?: line
        // Markdown images first: the images made from embeds below carry content:// URIs.
        result = MARKDOWN_IMAGE.replace(result) { match ->
            val target = match.groupValues[2]
            if (target.contains("://") || target.startsWith("data:")) {
                // Web images are never loaded: the app has no network access.
                "*${escape(match.groupValues[1].ifBlank { target })}*"
            } else {
                val decoded = runCatching { java.net.URLDecoder.decode(target, "UTF-8") }.getOrDefault(target)
                resolveFile(decoded)?.let { "![${match.groupValues[1]}]($it)" } ?: "*${escape(match.groupValues[1].ifBlank { decoded })}*"
            }
        }
        result = EMBED.replace(result) { match ->
            val target = match.groupValues[1].trim()
            if (isImage(target)) {
                resolveFile(target)?.let { "![${escape(target)}]($it)" } ?: "*${escape(target)}*"
            } else {
                // An embedded note: shown as a link, the preview does not nest notes.
                noteLink(target, match.groupValues[2].ifBlank { target })
            }
        }
        result = WIKILINK.replace(result) { match ->
            val target = match.groupValues[1].trim()
            val heading = match.groupValues[2].removePrefix("#").trim()
            val alias = match.groupValues[3].trim()
            val label = alias.ifEmpty { if (target.isEmpty()) heading else if (heading.isEmpty()) target else "$target › $heading" }
            if (target.isEmpty()) escape(label) else noteLink(target, label)
        }
        return result
    }

    private fun noteLink(target: String, label: String): String =
        "[${escape(label)}]($NOTE_LINK_SCHEME:${URLEncoder.encode(target, "UTF-8").replace("+", "%20")})"

    /** The note name of a link made by [prepare], or null for any other link. */
    fun noteOfLink(link: String): String? =
        link.takeIf { it.startsWith("$NOTE_LINK_SCHEME:") }
            ?.removePrefix("$NOTE_LINK_SCHEME:")
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun escape(text: String): String = text.replace("[", "\\[").replace("]", "\\]")

    private fun stripFrontMatter(text: String): String {
        val normalized = text.removePrefix("\uFEFF")
        if (!normalized.startsWith("---")) return normalized
        val lines = normalized.lines()
        if (lines.first().trim() != "---") return normalized
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" || it.trim() == "..." }
        return if (end < 0) normalized else lines.drop(end + 2).joinToString("\n")
    }
}
