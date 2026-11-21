package com.isotjs.todosian.data.conflict

import com.isotjs.todosian.data.index.NoteIndexer
import com.isotjs.todosian.utils.REMINDER_MARKER
import com.isotjs.todosian.utils.TASK_ID_MARKER
import com.isotjs.todosian.utils.TaskMarkers

/** Where a part of the merged note comes from. */
enum class MergeSource {
    /** Unchanged on both sides, or changed the same way. */
    BOTH,
    ORIGINAL,
    COPY,

    /** One task changed on both sides, put together field by field. */
    FIELDS,

    /** Added on both sides at the same place: both kept. */
    ADDED_BOTH,
}

/** What the user picks for a contradiction. */
enum class MergeChoice { ORIGINAL, COPY, BOTH }

sealed interface MergePart {
    data class Taken(val lines: List<String>, val source: MergeSource) : MergePart

    /** Both sides changed the same place differently; [id] counts from 0 in the note. */
    data class Contradiction(val id: Int, val original: List<String>, val copy: List<String>) : MergePart
}

/**
 * The merge of a note and its Syncthing conflict copy. Without a base ([hasBase] false) the app
 * cannot tell which side changed: every place where the two differ is a contradiction, and all
 * of them start as "keep both".
 */
data class MergeResult(val parts: List<MergePart>, val hasBase: Boolean) {
    val contradictions: List<MergePart.Contradiction> get() = parts.filterIsInstance<MergePart.Contradiction>()

    /** Nothing to ask: may be written without a question (if the setting says so). */
    val isClean: Boolean get() = hasBase && contradictions.isEmpty()

    /** How many places of the note come from [source]. */
    fun places(source: MergeSource): Int = parts.count { it is MergePart.Taken && it.source == source }

    /** The merged note, or null while a contradiction has no choice. */
    fun render(choices: Map<Int, MergeChoice>): List<String>? {
        val out = ArrayList<String>()
        for (part in parts) when (part) {
            is MergePart.Taken -> out.addAll(part.lines)
            is MergePart.Contradiction -> when (choices[part.id] ?: return null) {
                MergeChoice.ORIGINAL -> out.addAll(part.original)
                MergeChoice.COPY -> out.addAll(part.copy)
                MergeChoice.BOTH -> {
                    out.addAll(part.original)
                    out.addAll(part.copy.filter { it !in part.original })
                }
            }
        }
        return out
    }

    /** Equal when the open questions are the same, so earlier choices still fit. */
    fun questions(): List<Pair<List<String>, List<String>>> = contradictions.map { it.original to it.copy }
}

/**
 * Three-way merge of a note ([original]) and its conflict copy ([copy]) against the base, known
 * only as one hash per line ([base]). Line by line: a change on one side is taken; the same change
 * on both sides once; a task changed on both sides is merged field by field when the base can be
 * rebuilt from the two sides' fields; anything else is a contradiction.
 */
object ConflictMerge {
    /** Larger notes (after cutting equal ends) are merged without a base: the table would be too big. */
    private const val MAX_CELLS = 4_000_000L
    private const val MAX_FIELD_DIFFS = 10

    fun merge(
        base: LongArray?,
        original: List<String>,
        copy: List<String>,
        markers: TaskMarkers = TaskMarkers.Default,
        hash: (String) -> Long = NoteIndexer::lineHash,
    ): MergeResult {
        val oh = LongArray(original.size) { hash(original[it]) }
        val ch = LongArray(copy.size) { hash(copy[it]) }
        // A base equal to one side cannot be the common state: Syncthing only makes a copy when
        // both sides changed. It is the state of one side, so it would silently undo the other.
        val usable = base != null && !base.contentEquals(oh) && !base.contentEquals(ch) &&
            cells(base, oh) <= MAX_CELLS && cells(base, ch) <= MAX_CELLS
        return if (usable) threeWay(base!!, original, oh, copy, ch, markers, hash) else union(original, oh, copy, ch)
    }

    private fun cells(a: LongArray, b: LongArray): Long {
        val (start, endA, endB) = trim(a, b)
        return (endA - start + 1).toLong() * (endB - start + 1)
    }

    // ---- three-way -------------------------------------------------------------------------

    private sealed interface Op {
        data class Keep(val line: String) : Op
        data object Delete : Op
        data class Replace(val line: String) : Op
    }

    /** What one side did to each base line, and what it inserted before base line j (slot j, 0..n). */
    private class Edits(val ops: Array<Op>, val inserts: Array<MutableList<String>>)

    private fun edits(base: LongArray, side: List<String>, sideHashes: LongArray): Edits {
        val n = base.size
        val match = align(base, sideHashes)
        val ops = Array<Op>(n) { Op.Delete }
        val inserts = Array(n + 1) { mutableListOf<String>() }
        var prevB = -1
        var prevS = -1
        fun gap(nextB: Int, nextS: Int) {
            val k = nextB - prevB - 1
            val m = nextS - prevS - 1
            val paired = minOf(k, m)
            for (t in 0 until paired) ops[prevB + 1 + t] = Op.Replace(side[prevS + 1 + t])
            for (t in paired until k) ops[prevB + 1 + t] = Op.Delete
            for (t in paired until m) inserts[nextB].add(side[prevS + 1 + t])
        }
        for (b in 0 until n) {
            val s = match[b]
            if (s < 0) continue
            gap(b, s)
            ops[b] = Op.Keep(side[s])
            prevB = b
            prevS = s
        }
        gap(n, side.size)
        return Edits(ops, inserts)
    }

    private fun threeWay(
        base: LongArray,
        original: List<String>,
        oh: LongArray,
        copy: List<String>,
        ch: LongArray,
        markers: TaskMarkers,
        hash: (String) -> Long,
    ): MergeResult {
        val o = edits(base, original, oh)
        val c = edits(base, copy, ch)
        val parts = PartBuilder()
        for (j in 0..base.size) {
            val a = o.inserts[j]
            val b = c.inserts[j]
            when {
                a.isEmpty() && b.isEmpty() -> Unit
                a == b -> parts.take(a, MergeSource.BOTH)
                b.isEmpty() -> parts.take(a, MergeSource.ORIGINAL)
                a.isEmpty() -> parts.take(b, MergeSource.COPY)
                else -> parts.take(a + b.filter { it !in a }, MergeSource.ADDED_BOTH)
            }
            if (j == base.size) break
            val x = o.ops[j]
            val y = c.ops[j]
            when {
                x is Op.Keep && y is Op.Keep -> parts.take(listOf(x.line), MergeSource.BOTH)
                x is Op.Keep && y is Op.Delete -> parts.take(emptyList(), MergeSource.COPY)
                x is Op.Keep && y is Op.Replace -> parts.take(listOf(y.line), MergeSource.COPY)
                x is Op.Delete && y is Op.Keep -> parts.take(emptyList(), MergeSource.ORIGINAL)
                x is Op.Replace && y is Op.Keep -> parts.take(listOf(x.line), MergeSource.ORIGINAL)
                x is Op.Delete && y is Op.Delete -> parts.take(emptyList(), MergeSource.BOTH)
                x is Op.Replace && y is Op.Replace -> when {
                    x.line == y.line -> parts.take(listOf(x.line), MergeSource.BOTH)
                    else -> mergeFields(base[j], x.line, y.line, markers, hash)
                        ?.let { parts.take(listOf(it), MergeSource.FIELDS) }
                        ?: parts.ask(listOf(x.line), listOf(y.line))
                }
                x is Op.Delete && y is Op.Replace -> parts.ask(emptyList(), listOf(y.line))
                x is Op.Replace && y is Op.Delete -> parts.ask(listOf(x.line), emptyList())
            }
        }
        return MergeResult(parts.build(), hasBase = true)
    }

    // ---- without base ----------------------------------------------------------------------

    private fun union(original: List<String>, oh: LongArray, copy: List<String>, ch: LongArray): MergeResult {
        val parts = PartBuilder()
        if (cells(oh, ch) > MAX_CELLS) {
            if (original == copy) parts.take(original, MergeSource.BOTH) else parts.ask(original, copy)
            return MergeResult(parts.build(), hasBase = false)
        }
        val match = align(oh, ch)
        var prevO = -1
        var prevC = -1
        fun gap(nextO: Int, nextC: Int) {
            val a = original.subList(prevO + 1, nextO)
            val b = copy.subList(prevC + 1, nextC)
            when {
                a.isEmpty() && b.isEmpty() -> Unit
                b.isEmpty() -> parts.take(a, MergeSource.ORIGINAL)
                a.isEmpty() -> parts.take(b, MergeSource.COPY)
                else -> parts.ask(a.toList(), b.toList())
            }
        }
        for (i in original.indices) {
            val j = match[i]
            if (j < 0) continue
            gap(i, j)
            parts.take(listOf(original[i]), MergeSource.BOTH)
            prevO = i
            prevC = j
        }
        gap(original.size, copy.size)
        return MergeResult(parts.build(), hasBase = false)
    }

    // ---- fields of one task ----------------------------------------------------------------

    private val taskLine = Regex("""^([ \t]*)- \[(.)] (.*)$""")
    private val priorities = listOf("🔺", "⏫", "🔼", "🔽", "⏬")

    /**
     * Merges a task changed on both sides: finds which side kept each field by rebuilding the
     * base line from the two sides' fields and comparing its hash. Null when that does not work
     * out exactly once, or a line is no task.
     */
    internal fun mergeFields(baseHash: Long, a: String, b: String, markers: TaskMarkers, hash: (String) -> Long): String? {
        val fa = fields(a, markers) ?: return null
        val fb = fields(b, markers) ?: return null
        val keys = orderKeys(fa.keys.toList(), fb.keys.toList())
        val diffs = keys.filter { fa[it] != fb[it] }
        if (diffs.isEmpty() || diffs.size > MAX_FIELD_DIFFS) return null
        var found: Int? = null
        for (mask in 0 until (1 shl diffs.size)) {
            val candidate = build(keys) { key ->
                val i = diffs.indexOf(key)
                if (i < 0) fa[key] else if (mask and (1 shl i) != 0) fa[key] else fb[key]
            }
            if (hash(candidate) == baseHash) {
                if (found != null) return null
                found = mask
            }
        }
        val baseMask = found ?: return null
        // Each differing field comes from the side that changed it, i.e. not the base's side.
        return build(keys) { key ->
            val i = diffs.indexOf(key)
            if (i < 0) fa[key] else if (baseMask and (1 shl i) != 0) fb[key] else fa[key]
        }
    }

    private const val INDENT = "\u0000indent"
    private const val MARK = "\u0000mark"
    private const val TEXT = "\u0000text"

    /** A task line split into indent, checkbox, text and its fields, each field with its leading space. */
    private fun fields(line: String, markers: TaskMarkers): LinkedHashMap<String, String>? {
        val match = taskLine.matchEntire(line) ?: return null
        val remainder = match.groupValues[3]
        val names = listOf(
            REMINDER_MARKER, TASK_ID_MARKER, "🏁", markers.created, markers.start, markers.scheduled,
            markers.due, markers.done, markers.recurrence,
        ) + priorities
        val starts = ArrayList<Pair<Int, String>>()
        var i = 1
        while (i < remainder.length) {
            if (remainder[i - 1].isWhitespace()) {
                names.firstOrNull { remainder.startsWith(it, i) }?.let { starts.add(i to it) }
            }
            i++
        }
        val result = LinkedHashMap<String, String>()
        result[INDENT] = match.groupValues[1]
        result[MARK] = match.groupValues[2]
        // Each field starts at the whitespace before its marker.
        val cuts = starts.map { (pos, name) ->
            var start = pos
            while (start > 0 && remainder[start - 1].isWhitespace()) start--
            start to name
        }
        result[TEXT] = remainder.substring(0, cuts.firstOrNull()?.first ?: remainder.length)
        cuts.forEachIndexed { k, (start, name) ->
            val end = cuts.getOrNull(k + 1)?.first ?: remainder.length
            // The same field twice: too unusual to merge by field.
            if (result.put(name, remainder.substring(start, end)) != null) return null
        }
        return result.takeIf { build(it.keys.toList()) { key -> it[key] } == line }
    }

    private fun build(keys: List<String>, value: (String) -> String?): String = buildString {
        append(value(INDENT).orEmpty())
        append("- [")
        append(value(MARK) ?: " ")
        append("] ")
        append(value(TEXT).orEmpty())
        keys.filter { it != INDENT && it != MARK && it != TEXT }.forEach { key -> value(key)?.let(::append) }
    }

    /** [a]'s order, with keys only [b] has placed after their predecessor in [b]. */
    private fun orderKeys(a: List<String>, b: List<String>): List<String> {
        val result = a.toMutableList()
        b.forEachIndexed { index, key ->
            if (key in result) return@forEachIndexed
            val after = b.subList(0, index).lastOrNull { it in result }
            result.add(if (after == null) 0 else result.indexOf(after) + 1, key)
        }
        return result
    }

    // ---- alignment -------------------------------------------------------------------------

    /** Equal ends of [a] and [b]: first differing index and the last differing index of each. */
    private fun trim(a: LongArray, b: LongArray): Triple<Int, Int, Int> {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size - 1
        var endB = b.size - 1
        while (endA >= start && endB >= start && a[endA] == b[endB]) {
            endA--
            endB--
        }
        return Triple(start, endA, endB)
    }

    /** Longest common subsequence: for each index of [a] the matched index of [b], or -1. */
    private fun align(a: LongArray, b: LongArray): IntArray {
        val match = IntArray(a.size) { -1 }
        val (start, endA, endB) = trim(a, b)
        for (i in 0 until start) match[i] = i
        val tail = a.size - 1 - endA
        for (t in 0 until tail) match[endA + 1 + t] = endB + 1 + t
        val n = endA - start + 1
        val m = endB - start + 1
        if (n <= 0 || m <= 0) return match
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            table[i][j] = if (a[start + i] == b[start + j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[start + i] == b[start + j] -> {
                    match[start + i] = start + j
                    i++
                    j++
                }
                table[i + 1][j] >= table[i][j + 1] -> i++
                else -> j++
            }
        }
        return match
    }

    /** Collects parts, joining neighbours of the same kind. */
    private class PartBuilder {
        private val parts = ArrayList<MergePart>()
        private var nextId = 0

        fun take(lines: List<String>, source: MergeSource) {
            val last = parts.lastOrNull()
            if (last is MergePart.Taken && last.source == source) {
                parts[parts.size - 1] = last.copy(lines = last.lines + lines)
            } else {
                parts.add(MergePart.Taken(lines, source))
            }
        }

        fun ask(original: List<String>, copy: List<String>) {
            val last = parts.lastOrNull()
            if (last is MergePart.Contradiction) {
                parts[parts.size - 1] = last.copy(original = last.original + original, copy = last.copy + copy)
            } else {
                parts.add(MergePart.Contradiction(nextId++, original, copy))
            }
        }

        fun build(): List<MergePart> = parts
    }
}
