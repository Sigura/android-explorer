package com.mediaviewer.domain

import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartCropTest {
    private fun crop(w: Int, h: Int) = SmartCrop.shouldCrop(w, h, 4f, 3f, CropMode.SMART, 10)

    /** The examples from README §4.2, for a 4:3 tile. */
    @Test fun readmeExamples() {
        assertFalse("3:2 loses 11 % → fit", crop(3000, 2000))
        assertFalse("1:1 loses 25 % → fit", crop(1000, 1000))
        assertTrue("800×600 loses 0 % → crop", crop(800, 600))
        assertFalse("836×470 loses 25 % → fit", crop(836, 470))
        assertFalse("2048×2048 → fit", crop(2048, 2048))
        assertTrue("1.4:1 loses 5 % → crop", crop(1400, 1000))
    }

    @Test fun lostFraction() {
        assertEquals(0.111f, SmartCrop.lostFraction(3, 2, 4f, 3f), 0.001f)
        assertEquals(0.25f, SmartCrop.lostFraction(1, 1, 4f, 3f), 0.001f)
    }

    @Test fun modes() {
        assertTrue(SmartCrop.shouldCrop(1, 1, 4f, 3f, CropMode.CROP, 10))
        assertFalse(SmartCrop.shouldCrop(4, 3, 4f, 3f, CropMode.FIT, 10))
    }
}

class SlideTimingTest {
    private val r = 14_000L

    /** The table from README §5.1 (R = 14 s). */
    @Test fun readmeTable() {
        assertEquals(SlideTiming(r, loop = true, longerThanInterval = false), SlideTimer.timing(600, r))
        assertEquals(SlideTiming(r, loop = true, longerThanInterval = false), SlideTimer.timing(1100, r))
        assertEquals(SlideTiming(10_300, loop = false, longerThanInterval = false), SlideTimer.timing(10_300, r))
        assertEquals(SlideTiming(20_000, loop = false, longerThanInterval = false), SlideTimer.timing(20_000, r))
        assertEquals(SlideTiming(32_000, loop = false, longerThanInterval = true), SlideTimer.timing(32_000, r))
        assertEquals(SlideTiming(152_000, loop = false, longerThanInterval = true), SlideTimer.timing(152_000, r))
    }

    @Test fun boundaries() {
        assertTrue("d < 2R/3 loops", SlideTimer.timing(7_000, r).loop)
        assertTrue(SlideTimer.timing(9_333, r).loop)
        assertFalse("d ≥ 2R/3 plays once", SlideTimer.timing(9_334, r).loop)
        assertFalse("d = 3R/2 is not 'longer'", SlideTimer.timing(21_000, r).longerThanInterval)
        assertTrue(SlideTimer.timing(21_001, r).longerThanInterval)
        assertEquals(r, SlideTimer.timing(null, r).showMs)
    }
}

class NaturalOrderTest {
    @Test fun numbers() {
        val sorted = listOf("img10.jpg", "img2.jpg", "IMG1.jpg", "img02.jpg", "a.gif").sortedWith(NaturalOrder)
        assertEquals(listOf("a.gif", "IMG1.jpg", "img2.jpg", "img02.jpg", "img10.jpg"), sorted)
    }

    @Test fun totalOrder() {
        assertTrue(NaturalOrder.compare("a", "A") != 0)
        assertEquals(0, NaturalOrder.compare("x1", "x1"))
    }
}

class SortingTest {
    private fun e(name: String, type: MediaType) = FileEntry("/d/$name", name, type, 0, 0)

    @Test fun groupsThenName() {
        val list = listOf(
            e("z.txt", MediaType.OTHER),
            e("b10.jpg", MediaType.IMAGE),
            e("dir", MediaType.FOLDER),
            e("b2.gif", MediaType.ANIMATED),
        ).sortedWith(Sorting.comparator(SortSpec(SortBy.NAME, descending = false)))
        assertEquals(listOf("dir", "b2.gif", "b10.jpg", "z.txt"), list.map { it.name })
    }

    @Test fun folderWithoutImagesSortsByName() {
        val global = SortSpec(SortBy.DATE, descending = true)
        val spec = Sorting.effective(global, own = null, hasImages = false)
        assertEquals(SortSpec(SortBy.NAME, descending = false), spec)
        val list = listOf("a10.mp3", "a2.mp3", "a1.mp3").mapIndexed { i, n -> FileEntry("/d/$n", n, MediaType.OTHER, 0, i.toLong()) }
            .sortedWith(Sorting.comparator(spec))
        assertEquals(listOf("a1.mp3", "a2.mp3", "a10.mp3"), list.map { it.name })
        assertEquals(global, Sorting.effective(global, own = null, hasImages = true))
    }

    @Test fun folderOwnSortWins() {
        val global = SortSpec(SortBy.SIZE, descending = false, foldersFirst = false)
        val own = FolderSort(SortBy.DATE, descending = false)
        assertEquals(SortSpec(SortBy.DATE, false, foldersFirst = false), Sorting.effective(global, own, hasImages = false))
        assertEquals(SortSpec(SortBy.DATE, false, foldersFirst = false), Sorting.effective(global, own, hasImages = true))
    }
}

class SlideOrderTest {
    private val cmp = Sorting.comparator(SortSpec(SortBy.NAME, descending = false))
    private fun media(n: Int) = (1..n).map { FileEntry("/d/f$it.gif", "f$it.gif", MediaType.ANIMATED, 0, 0) }

    @Test fun sequentialWrapsAndPreviousStopsAtStart() {
        val o = SlideOrder(media(3), cmp, random = false, seed = 0, pinnedFirst = null, startPath = "/d/f1.gif")
        assertNull(o.previous())
        assertEquals("f2.gif", o.next()?.name)
        assertEquals("f3.gif", o.next()?.name)
        assertEquals("f1.gif", o.next()?.name)
    }

    @Test fun randomCycleHasNoRepeats() {
        val n = 500
        val o = SlideOrder(media(n), cmp, random = true, seed = 42, pinnedFirst = "/d/f7.gif", startPath = "/d/f7.gif", rng = Random(1))
        assertEquals("f7.gif", o.current?.name)
        val seen = HashSet<String>()
        seen.add(o.current!!.path)
        repeat(n - 1) { assertTrue(seen.add(o.next()!!.path)) }
        assertEquals(n, seen.size)
        // New cycle: never the same file twice in a row.
        val last = o.current!!.path
        repeat(50) {
            val before = o.current!!.path
            val nx = o.next()!!.path
            if (o.position == 0) assertTrue(nx != before)
        }
        assertTrue(last.isNotEmpty())
    }

    @Test fun restoreRebuildsSameOrder() {
        val list = media(1000)
        val a = SlideOrder(list, cmp, random = true, seed = 1234, pinnedFirst = "/d/f5.gif", startPath = "/d/f5.gif")
        repeat(300) { a.next() }
        val cur = a.current!!.path
        val upcoming = (0 until 20).map { a.next()!!.path }
        // Restart: only seed, pinned first file and current file are stored.
        val b = SlideOrder(list.shuffled(Random(9)).sortedWith(cmp), cmp, random = true, seed = 1234, pinnedFirst = "/d/f5.gif", startPath = cur)
        assertEquals(301, b.position + 1)
        assertEquals(upcoming, (0 until 20).map { b.next()!!.path })
    }

    @Test fun restoreSurvivesDeletedCurrent() {
        val list = media(100)
        val a = SlideOrder(list, cmp, random = true, seed = 99, pinnedFirst = null, startPath = null)
        repeat(10) { a.next() }
        val cur = a.current!!.path
        val next = a.next()!!.path
        val b = SlideOrder(list.filter { it.path != cur }, cmp, random = true, seed = 99, pinnedFirst = null, startPath = cur)
        assertEquals(next, b.current!!.path)
    }

    @Test fun previousGoesBackThroughShownItems() {
        val o = SlideOrder(media(50), cmp, random = true, seed = 3, pinnedFirst = null, startPath = null)
        val shown = mutableListOf(o.current!!.path)
        repeat(5) { shown.add(o.next()!!.path) }
        repeat(5) { i -> assertEquals(shown[4 - i], o.previous()!!.path) }
    }

    @Test fun addGoesToUnshownPartAndRemoveSkips() {
        val o = SlideOrder(media(10), cmp, random = true, seed = 5, pinnedFirst = null, startPath = null, rng = Random(2))
        repeat(4) { o.next() }
        val pos = o.position
        val extra = FileEntry("/d/new.gif", "new.gif", MediaType.ANIMATED, 0, 0)
        o.add(extra)
        assertTrue(o.indexOf(extra.path) > pos)
        val cur = o.current!!.path
        o.remove(cur)
        assertTrue(o.current!!.path != cur)
        assertEquals(10, o.size)
    }

    @Test fun toggleRandomKeepsCurrent() {
        val o = SlideOrder(media(20), cmp, random = false, seed = 0, pinnedFirst = null, startPath = "/d/f9.gif")
        o.setRandom(true)
        assertEquals("f9.gif", o.current?.name)
        assertEquals(0, o.position)
        o.next()
        val c = o.current!!
        o.setRandom(false)
        assertEquals(c, o.current)
    }
}

class BookTimesTest {
    private val paths = listOf("/b/1.mp3", "/b/2.mp3", "/b/3.mp3")

    @Test fun progressWhenAllKnown() {
        val t = BookTimes(paths, listOf(60_000L, 120_000L, 180_000L))
        val p = t.progress(current = 1, positionMs = 30_000, currentMs = 120_000)
        assertEquals(90_000L, p.elapsedMs)
        assertEquals(360_000L, p.totalMs)
        assertTrue(p.exact)
        assertEquals(270_000L, p.leftMs(1f))
        assertEquals(180_000L, p.leftMs(1.5f))
    }

    @Test fun playerDurationFillsTheGap() {
        val t = BookTimes(paths, listOf(60_000L, null, 180_000L))
        assertTrue(t.progress(1, 0, 120_000).exact)
        val p = t.progress(1, 0, 0)
        assertFalse(p.exact)
        assertEquals(240_000L, p.totalMs)
    }

    @Test fun unknownSegmentsAreAverage() {
        val w = BookTimes(paths, listOf(100L, null, 300L)).weights(0, 0)
        assertEquals(listOf(100f, 200f, 300f), w)
        assertEquals(listOf(1f, 1f, 1f), BookTimes(paths, listOf(null, null, null)).weights(0, 0))
    }
}
