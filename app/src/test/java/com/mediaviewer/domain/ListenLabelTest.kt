package com.mediaviewer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenLabelTest {
    private fun st(index: Int = 11, total: Int = 30, pos: Long = 245_000, dur: Long = 1_510_000, finished: Boolean = false) =
        ListenStatus(index, total, pos, dur, finished)

    @Test fun shortToLong() {
        assertEquals(listOf("12/30", "12/30 · 4:05", "File 12 of 30 · 4:05 / 25:10"), ListenLabel.variants(st()))
    }

    @Test fun v1RowsWithoutIndex() {
        assertEquals(listOf("4:05"), ListenLabel.variants(st(index = -1, total = 0, dur = 0)))
        assertEquals(listOf("4:05", "4:05 / 25:10"), ListenLabel.variants(st(index = -1, total = 0)))
    }

    @Test fun finished() {
        assertEquals(listOf("", "Finished", "Finished · 30 files"), ListenLabel.variants(st(finished = true)))
        assertEquals(listOf("", "Finished"), ListenLabel.variants(st(total = 0, finished = true)))
        assertEquals(1f, ListenLabel.progress(st(finished = true)))
    }

    @Test fun progress() {
        assertEquals(0.5f, ListenLabel.progress(st(index = 1, total = 3, pos = 500, dur = 1000))!!, 1e-6f)
        assertEquals(0f, ListenLabel.progress(st(index = 0, total = 3, pos = 0, dur = 0))!!, 1e-6f)
        assertNull(ListenLabel.progress(st(index = -1)))
    }

    @Test fun time() {
        assertEquals("0:00", ListenLabel.time(-5))
        assertEquals("59:59", ListenLabel.time(3_599_999))
        assertEquals("1:02:05", ListenLabel.time(3_725_000))
    }
}
