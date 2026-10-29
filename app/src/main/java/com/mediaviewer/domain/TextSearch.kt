package com.mediaviewer.domain

/** Find in the text editor (todo §10.6): plain text, no regular expressions. Pure Kotlin. */
object TextSearch {
    /** More matches than this are not counted or marked (a one-letter query in a 1 MB file). */
    const val LIMIT = 10_000

    /** Start offsets of the non-overlapping matches of [query] in [text], in order; at most [limit]. */
    fun find(text: String, query: String, matchCase: Boolean, limit: Int = LIMIT): IntArray {
        if (query.isEmpty() || query.length > text.length) return IntArray(0)
        val out = IntArray(minOf(limit, text.length / query.length + 1))
        var n = 0
        var i = text.indexOf(query, 0, ignoreCase = !matchCase)
        while (i >= 0 && n < out.size) {
            out[n++] = i
            i = text.indexOf(query, i + query.length, ignoreCase = !matchCase)
        }
        return out.copyOf(n)
    }

    /** The match to show first: the first one at or after [caret], wrapping round to the first; -1 if none. */
    fun nearest(starts: IntArray, caret: Int): Int {
        if (starts.isEmpty()) return -1
        val i = starts.binarySearch(caret).let { if (it >= 0) it else -it - 1 }
        return if (i < starts.size) i else 0
    }

    /** The next (or previous) match, wrapping round at the ends. */
    fun step(current: Int, count: Int, forward: Boolean): Int = when {
        count == 0 -> -1
        current < 0 -> if (forward) 0 else count - 1
        forward -> (current + 1) % count
        else -> (current - 1 + count) % count
    }

    /**
     * [text] with each match ([starts], [length] long) replaced by [replacement], and the offset
     * just after the last replacement (where the caret goes).
     */
    fun replaceAll(text: String, starts: IntArray, length: Int, replacement: String): Pair<String, Int> {
        if (starts.isEmpty()) return text to 0
        val out = StringBuilder(text.length + starts.size * maxOf(0, replacement.length - length))
        var at = 0
        for (m in starts) {
            out.append(text, at, m).append(replacement)
            at = m + length
        }
        val caret = out.length
        out.append(text, at, text.length)
        return out.toString() to caret
    }

    /** Offsets where each line starts (line 0 at 0), for mapping a match to its line. */
    fun lineStarts(text: String): IntArray {
        var n = 1
        for (c in text) if (c == '\n') n++
        val out = IntArray(n)
        var k = 1
        for (i in text.indices) if (text[i] == '\n') out[k++] = i + 1
        return out
    }

    /** The line holding [offset], given [lineStarts]. */
    fun lineOf(lineStarts: IntArray, offset: Int): Int {
        val i = lineStarts.binarySearch(offset)
        return if (i >= 0) i else -i - 2
    }
}
