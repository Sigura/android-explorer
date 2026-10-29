package com.mediaviewer.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TextSearchTest {
    @Test fun findIgnoresCaseByDefault() {
        assertArrayEquals(intArrayOf(0, 8), TextSearch.find("Foo bar foo", "foo", matchCase = false))
        assertArrayEquals(intArrayOf(8), TextSearch.find("Foo bar foo", "foo", matchCase = true))
        assertArrayEquals(intArrayOf(), TextSearch.find("abc", "", matchCase = false))
        assertArrayEquals(intArrayOf(), TextSearch.find("ab", "abc", matchCase = false))
    }

    @Test fun matchesDontOverlap() {
        assertArrayEquals(intArrayOf(0, 2), TextSearch.find("aaaaa", "aa", matchCase = true))
    }

    @Test fun cyrillicCase() {
        assertArrayEquals(intArrayOf(0, 7), TextSearch.find("Привет привет", "ПРИВЕТ", matchCase = false))
    }

    @Test fun limit() {
        assertEquals(3, TextSearch.find("x".repeat(100), "x", matchCase = true, limit = 3).size)
        assertEquals(TextSearch.LIMIT, TextSearch.find("x".repeat(20_000), "x", matchCase = true).size)
    }

    @Test fun nearestAndStep() {
        val m = intArrayOf(5, 10, 20)
        assertEquals(0, TextSearch.nearest(m, 0))
        assertEquals(1, TextSearch.nearest(m, 10))
        assertEquals(2, TextSearch.nearest(m, 11))
        assertEquals(0, TextSearch.nearest(m, 21))
        assertEquals(-1, TextSearch.nearest(intArrayOf(), 0))
        assertEquals(0, TextSearch.step(2, 3, forward = true))
        assertEquals(2, TextSearch.step(0, 3, forward = false))
        assertEquals(2, TextSearch.step(-1, 3, forward = false))
        assertEquals(-1, TextSearch.step(0, 0, forward = true))
    }

    @Test fun lines() {
        val t = "ab\ncd\n\nef"
        val ls = TextSearch.lineStarts(t)
        assertArrayEquals(intArrayOf(0, 3, 6, 7), ls)
        assertEquals(0, TextSearch.lineOf(ls, 2))
        assertEquals(1, TextSearch.lineOf(ls, 3))
        assertEquals(2, TextSearch.lineOf(ls, 6))
        assertEquals(3, TextSearch.lineOf(ls, 8))
    }
}
