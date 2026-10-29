package com.mediaviewer.domain

/**
 * The bracket matching the one next to the cursor (todo §10.8): `()[]{}`, and in markup the tag
 * whose name the cursor is in (`<div` ↔ `</div`). A bounded scan of ±[BOUND] characters around the
 * cursor that skips strings and comments using the lexer's spans; with no spans (plain text, or
 * over `TextFile.COLOUR_LIMIT`) every bracket counts. Pure Kotlin, tested in `BracketsTest`.
 */
object Brackets {
    const val BOUND = 20_000

    private const val OPEN = "([{"
    private const val CLOSE = ")]}"

    /** The two highlighted ranges, [openStart, openEnd) and [closeStart, closeEnd). */
    data class Match(val openStart: Int, val openEnd: Int, val closeStart: Int, val closeEnd: Int)

    /**
     * The pair for a cursor at [offset]: the bracket right after the cursor first, then the one
     * right before it. Null when there is none, it is in a string or comment, or its partner is
     * missing or beyond the bound.
     */
    fun match(text: CharSequence, offset: Int, spans: Spans, markup: Boolean = false): Match? {
        if (offset < 0 || offset > text.length) return null
        val lo = maxOf(0, offset - BOUND)
        val hi = minOf(text.length, offset + BOUND)
        if (markup) tag(text, offset, spans, lo, hi)?.let { return it }
        for (at in intArrayOf(offset, offset - 1)) {
            if (at < 0 || at >= text.length || inert(spans, at) >= 0) continue
            val c = text[at]
            val open = OPEN.indexOf(c)
            val close = CLOSE.indexOf(c)
            val found = when {
                open >= 0 -> forward(text, spans, at + 1, hi, c, CLOSE[open])?.let { Match(at, at + 1, it, it + 1) }
                close >= 0 -> backward(text, spans, at - 1, lo, c, OPEN[close])?.let { Match(it, it + 1, at, at + 1) }
                else -> null
            }
            if (found != null) return found
        }
        return null
    }

    /** The partner of an opening bracket in [from, hi), at the same depth. */
    private fun forward(t: CharSequence, spans: Spans, from: Int, hi: Int, open: Char, close: Char): Int? {
        var depth = 0
        var j = firstEndingAfter(spans, from)
        var i = from
        while (i < hi) {
            while (j < spans.size && spans.end(j) <= i) j++
            if (j < spans.size && spans.start(j) <= i && isInert(spans.role(j))) {
                i = spans.end(j)
                continue
            }
            val c = t[i]
            if (c == open) {
                depth++
            } else if (c == close) {
                if (depth == 0) return i
                depth--
            }
            i++
        }
        return null
    }

    /** The partner of a closing bracket in [lo, from], at the same depth. */
    private fun backward(t: CharSequence, spans: Spans, from: Int, lo: Int, close: Char, open: Char): Int? {
        var depth = 0
        var j = lastStartingAtOrBefore(spans, from)
        var i = from
        while (i >= lo) {
            while (j >= 0 && spans.start(j) > i) j--
            if (j >= 0 && spans.end(j) > i && isInert(spans.role(j))) {
                i = spans.start(j) - 1
                continue
            }
            val c = t[i]
            if (c == close) {
                depth++
            } else if (c == open) {
                if (depth == 0) return i
                depth--
            }
            i--
        }
        return null
    }

    // ------------------------------------------------------------------ markup tags

    private fun isName(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_' || c == ':' || c == '.'

    /** The tag whose `<name` / `</name` holds the cursor (on `<`, in the name or right after it). */
    private fun tag(t: CharSequence, offset: Int, spans: Spans, lo: Int, hi: Int): Match? {
        val n = t.length
        var a = offset
        while (a > 0 && isName(t[a - 1])) a--
        val lt = when {
            a >= 2 && t[a - 1] == '/' && t[a - 2] == '<' -> a - 2
            a >= 1 && t[a - 1] == '<' -> a - 1
            offset < n && t[offset] == '<' -> offset
            offset in 1 until n && t[offset] == '/' && t[offset - 1] == '<' -> offset - 1
            else -> return null
        }
        if (inert(spans, lt) >= 0) return null
        val closing = lt + 1 < n && t[lt + 1] == '/'
        val nameStart = lt + if (closing) 2 else 1
        if (nameStart >= n || !t[nameStart].isLetter()) return null
        var nameEnd = nameStart
        while (nameEnd < n && isName(t[nameEnd])) nameEnd++
        val name = t.subSequence(nameStart, nameEnd).toString()
        return if (closing) {
            openingTag(t, spans, name, lt - 1, lo, hi)?.let { Match(it, it + 1 + name.length, lt, nameEnd) }
        } else {
            if (selfClosing(t, nameEnd, hi)) return null
            closingTag(t, spans, name, nameEnd, hi)?.let { Match(lt, nameEnd, it, it + 2 + name.length) }
        }
    }

    /** `<name` or `</name` at [at] (`slash` says which), followed by a character outside names. */
    private fun tagAt(t: CharSequence, at: Int, name: String, slash: Boolean): Boolean {
        var i = at + 1
        if (slash) {
            if (i >= t.length || t[i] != '/') return false
            i++
        }
        if (i + name.length > t.length) return false
        for (k in name.indices) if (t[i + k] != name[k]) return false
        val after = i + name.length
        return after == t.length || !isName(t[after])
    }

    /** Whether the tag whose name ends at [from] ends with `/>`. Quoted values may hold `>`. */
    private fun selfClosing(t: CharSequence, from: Int, hi: Int): Boolean {
        var i = from
        var quote = 0.toChar()
        while (i < hi) {
            val c = t[i]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i > from && t[i - 1] == '/'
                c == '<' -> return false // a broken tag
            }
            i++
        }
        return false
    }

    private fun closingTag(t: CharSequence, spans: Spans, name: String, from: Int, hi: Int): Int? {
        var depth = 0
        var i = from
        while (i < hi) {
            i = indexOf(t, '<', i, hi)
            if (i < 0) return null
            if (inert(spans, i) < 0) {
                if (tagAt(t, i, name, slash = true)) {
                    if (depth == 0) return i
                    depth--
                } else if (tagAt(t, i, name, slash = false) && !selfClosing(t, i + 1 + name.length, hi)) {
                    depth++
                }
            }
            i++
        }
        return null
    }

    private fun openingTag(t: CharSequence, spans: Spans, name: String, from: Int, lo: Int, hi: Int): Int? {
        var depth = 0
        var i = from
        while (i >= lo) {
            if (t[i] == '<' && inert(spans, i) < 0) {
                if (tagAt(t, i, name, slash = true)) {
                    depth++
                } else if (tagAt(t, i, name, slash = false) && !selfClosing(t, i + 1 + name.length, hi)) {
                    if (depth == 0) return i
                    depth--
                }
            }
            i--
        }
        return null
    }

    private fun indexOf(t: CharSequence, c: Char, from: Int, hi: Int): Int {
        for (i in from until hi) if (t[i] == c) return i
        return -1
    }

    // ------------------------------------------------------------------ spans

    private fun isInert(r: Role) = r == Role.STRING || r == Role.COMMENT

    /** The index of the string or comment span holding [pos], or -1. */
    private fun inert(spans: Spans, pos: Int): Int {
        val j = lastStartingAtOrBefore(spans, pos)
        return if (j >= 0 && spans.end(j) > pos && isInert(spans.role(j))) j else -1
    }

    private fun lastStartingAtOrBefore(spans: Spans, pos: Int): Int {
        var lo = 0
        var hi = spans.size - 1
        var r = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (spans.start(m) <= pos) {
                r = m
                lo = m + 1
            } else {
                hi = m - 1
            }
        }
        return r
    }

    private fun firstEndingAfter(spans: Spans, pos: Int): Int {
        var lo = 0
        var hi = spans.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (spans.end(m) <= pos) lo = m + 1 else hi = m
        }
        return lo
    }
}
