package com.mediaviewer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BracketsTest {
    private fun m(text: String, offset: Int, lang: Language = Language.KOTLIN): Brackets.Match? =
        Brackets.match(text, offset, Highlight.spans(text, lang), markup = lang == Language.HTML || lang == Language.XML)

    /** The pair as the two bracketed substrings' starts, for readable checks. */
    private fun pair(text: String, offset: Int, lang: Language = Language.KOTLIN) = m(text, offset, lang)?.let { it.openStart to it.closeStart }

    @Test fun nesting() {
        val t = "f(a(b)[c]{d})"
        assertEquals(1 to 12, pair(t, 1)) // cursor before the outer (
        assertEquals(1 to 12, pair(t, 13)) // cursor after the outer )
        assertEquals(3 to 5, pair(t, 3))
        assertEquals(6 to 8, pair(t, 7)) // after [
        assertEquals(9 to 11, pair(t, 11)) // before }: the bracket after the cursor wins
    }

    @Test fun afterTheCursorFirstThenBefore() {
        // Between ) and (: the ( after the cursor is taken.
        val t = "(a)(b)"
        assertEquals(3 to 5, pair(t, 3))
        assertNull(pair("abc", 1))
    }

    @Test fun stringsAndCommentsAreSkipped() {
        val t = "f(\")\", ')' /* ) */ // )\n x)"
        assertEquals(1 to t.lastIndexOf(')'), pair(t, 1))
        assertEquals(1 to t.lastIndexOf(')'), pair(t, t.length))
        // A bracket inside a string has no partner.
        assertNull(pair(t, 3))
    }

    @Test fun plainTextHasNoSpansSoEveryBracketCounts() {
        val t = "(\")\")"
        assertEquals(0 to 2, pair(t, 0, Language.PLAIN))
        assertEquals(0 to 2, Brackets.match(t, 0, Spans.EMPTY)?.let { it.openStart to it.closeStart })
    }

    @Test fun unmatched() {
        assertNull(pair("f(a", 1))
        assertNull(pair("a)", 2))
        assertNull(pair("(]", 0))
        assertNull(pair("", 0))
        assertNull(pair("()", 5))
    }

    @Test fun theScanIsBounded() {
        val near = "(" + "x".repeat(Brackets.BOUND - 10) + ")"
        assertEquals(0 to near.length - 1, pair(near, 0, Language.PLAIN))
        val far = "(" + "x".repeat(Brackets.BOUND + 10) + ")"
        assertNull(pair(far, 0, Language.PLAIN))
        assertNull(pair(far, far.length, Language.PLAIN))
    }

    @Test fun markupTags() {
        val t = "<div class=\"a\"><p>x</p><div/><div>y</div><!-- </div> --></div>"
        val outer = t.lastIndexOf("</div>")
        for (cursor in listOf(0, 1, 3, 4)) {
            assertEquals("cursor $cursor", Brackets.Match(0, 4, outer, outer + 5), m(t, cursor, Language.HTML))
        }
        assertEquals(0 to outer, pair(t, outer + 2, Language.HTML))
        val p = t.indexOf("<p>")
        assertEquals(p to t.indexOf("</p>"), pair(t, p + 2, Language.HTML))
        // A self-closing tag has no partner.
        assertNull(m(t, t.indexOf("<div/>") + 2, Language.HTML))
        // Brackets still match in markup text.
        assertEquals(0 to 2, pair("(a)", 0, Language.XML))
    }

    @Test fun unclosedTag() {
        assertNull(m("<item><b>", 2, Language.XML))
    }
}
