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
 * - a single line break stays a line break, and a line right after a list item starts its own
 *   paragraph instead of joining the item (Obsidian has no "lazy" list lines)
 * - tags (`#30min`) are shown as code, so they stand out like in Obsidian
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
    private val FENCE = Regex("""^\s*(```|~~~)""")
    private val LIST_ITEM = Regex("""^\s*([-*+]|\d+[.)])\s""")
    private val HEADING = Regex("""^\s{0,3}#{1,6}(\s|$)""")
    private val TAG = Regex("""(^|\s)(#[\p{L}\p{N}_/-]*[\p{L}_/-][\p{L}\p{N}_/-]*)""")

    /**
     * [resolveFile] gets a file name or path as written in the note and returns a URI the image
     * loader can open, or null.
     */
    fun prepare(text: String, resolveFile: (String) -> String?): String {
        val body = COMMENT.replace(stripFrontMatter(text), "")
        val lines = body.lines()
        val out = ArrayList<String>(lines.size + 8)
        var inFence = false
        lines.forEachIndexed { index, line ->
            if (FENCE.containsMatchIn(line)) {
                inFence = !inFence
                out.add(line)
                return@forEachIndexed
            }
            if (inFence) {
                out.add(line)
                return@forEachIndexed
            }
            val previous = lines.getOrNull(index - 1)
            if (previous != null && LIST_ITEM.containsMatchIn(previous) && line.isNotBlank() && !continuesList(line)) {
                out.add("")
            }
            var prepared = markTags(prepareLine(line, resolveFile))
            val next = lines.getOrNull(index + 1)
            if (next != null && next.isNotBlank() && line.isNotBlank() && !HEADING.containsMatchIn(line) &&
                !line.trimStart().startsWith("|") && !FENCE.containsMatchIn(next) &&
                !(LIST_ITEM.containsMatchIn(line) && !continuesList(next))
            ) {
                // Two spaces: a hard line break, as Obsidian shows a single line break.
                prepared = prepared.trimEnd() + "  "
            }
            out.add(prepared)
        }
        return out.joinToString("\n")
    }

    /** A line that still belongs to the list above it: indented, another item, or a quote/table/heading of its own. */
    private fun continuesList(line: String): Boolean =
        line.first().isWhitespace() || LIST_ITEM.containsMatchIn(line) || line.startsWith(">") ||
            line.startsWith("|") || HEADING.containsMatchIn(line)

    /** Tags outside code become code spans; links and headings are left alone. */
    private fun markTags(line: String): String {
        if (!line.contains('#')) return line
        return line.split('`').mapIndexed { i, part ->
            if (i % 2 == 1) part else TAG.replace(part) { "${it.groupValues[1]}`${it.groupValues[2]}`" }
        }.joinToString("`")
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
