package com.mediaviewer.domain

import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SlideNavTest {
    private val any: (Int) -> Boolean = { true }

    /** A slideshow of [n] items with a [SlideNav], driven like the controller drives it. */
    private class Show(n: Int, random: Boolean = false) {
        val nav = SlideNav(maxVisits = 15, minLeftMs = 2_000)
        val order = SlideOrder((0 until n).map { FileEntry("/d/$it.jpg", "$it.jpg", MediaType.IMAGE, 1, 1) }, compareBy { it.name }, random, 1L, null, "/d/0.jpg")
        val reachable: (Int) -> Boolean = { o -> order.peek(o) != null && kotlin.math.abs(o) < order.size }
        val current get() = order.current!!.path

        /** The item on screen is left after [elapsed] ms of 14 s at [position]; the target is shown and resumed. */
        fun step(dir: Int, elapsed: Long, position: Long = 0): SlideNav.Visit? {
            val a = nav.press(dir, reachable)
            if (a !is SlideNav.Action.Go) return null
            val target = order.peek(a.offset)!!
            nav.leave(current, elapsed, 14_000, position)
            order.moveTo(target.path)
            nav.done()
            return nav.resume(target.path)
        }
    }

    @Test fun backThenNextGoesOnWhereItWasLeft() {
        val s = Show(5)
        assertNull(s.step(+1, elapsed = 3_000)) // 0 → 1: first visit of 1
        // 1 left at 6 s, video at 6 s; back to 0, which was left at 3 s.
        val back = s.step(-1, elapsed = 6_000, position = 6_000)
        assertEquals(3_000L, back!!.elapsedMs)
        // Next: 1 goes on at 6 s and its position, not from the start.
        val fwd = s.step(+1, elapsed = 1_000)!!
        assertEquals(6_000L, fwd.elapsedMs)
        assertEquals(6_000L, fwd.positionMs)
        assertEquals("/d/1.jpg", s.current)
    }

    @Test fun severalBacksThenNextsRestoreEachItem() {
        val s = Show(6)
        s.step(+1, 1_000); s.step(+1, 2_000); s.step(+1, 3_000) // on 3; 0, 1, 2 left at 1, 2, 3 s
        assertEquals(3_000L, s.step(-1, 4_000)!!.elapsedMs) // back to 2
        assertEquals(2_000L, s.step(-1, 5_000)!!.elapsedMs) // back to 1
        assertEquals(5_000L, s.step(+1, 500)!!.elapsedMs) // forward to 2: where Back left it
        assertEquals(4_000L, s.step(+1, 600)!!.elapsedMs) // forward to 3
        assertEquals("/d/3.jpg", s.current)
    }

    @Test fun nearlyFinishedItemsStartOver() {
        val s = Show(3)
        s.step(+1, 12_500) // 0 left 1.5 s before its end
        assertNull(s.step(-1, 2_000))
        // Exactly 2 s left still goes on.
        s.step(+1, 12_000)
        assertNotNull(s.step(-1, 1_000))
    }

    @Test fun visitsAreUsedOnceAndOnlyTheLastOnesAreKept() {
        val nav = SlideNav(maxVisits = 2, minLeftMs = 2_000)
        nav.leave("a", 1_000, 14_000, 0)
        nav.leave("b", 1_000, 14_000, 0)
        nav.leave("c", 1_000, 14_000, 0)
        assertNull(nav.resume("a")) // dropped: only the last 2
        assertNotNull(nav.peekResume("b"))
        assertNotNull(nav.resume("b"))
        assertNull(nav.resume("b"))
        nav.leave("d", 0, 14_000, 0) // never on screen: not remembered
        assertNull(nav.resume("d"))
    }

    @Test fun secondPressWhileWaitingMovesTheTargetOn() {
        val nav = SlideNav()
        assertEquals(SlideNav.Action.Go(1), nav.press(+1, any))
        assertEquals(SlideNav.Action.Go(2), nav.press(+1, any))
        assertEquals(SlideNav.Action.Go(3), nav.press(+1, any))
        nav.done()
        assertNull(nav.wait)
        assertEquals(SlideNav.Action.Go(-1), nav.press(-1, any))
        assertEquals(SlideNav.Action.Go(-2), nav.press(-1, any))
    }

    @Test fun oppositePressCancelsTheWait() {
        val nav = SlideNav()
        nav.press(+1, any)
        nav.press(+1, any)
        assertEquals(SlideNav.Action.Cancel, nav.press(-1, any))
        assertNull(nav.wait)
        // The next press starts a new wait from the item on screen.
        assertEquals(SlideNav.Action.Go(-1), nav.press(-1, any))
    }

    @Test fun noItemThatWayStays() {
        val s = Show(3)
        // At the first item, Previous has nothing.
        assertEquals(SlideNav.Action.Stay, s.nav.press(-1, s.reachable))
        assertNull(s.nav.wait)
        // Next, Next, Next in a 3-item folder: the third would come round to the item on screen.
        assertEquals(SlideNav.Action.Go(1), s.nav.press(+1, s.reachable))
        assertEquals(SlideNav.Action.Go(2), s.nav.press(+1, s.reachable))
        assertEquals(SlideNav.Action.Stay, s.nav.press(+1, s.reachable))
        assertEquals(2, s.nav.wait!!.offset)
    }

    @Test fun failedTargetIsSkipped() {
        val s = Show(4)
        s.nav.press(+1, s.reachable)
        assertEquals(SlideNav.Action.Go(2), s.nav.failed(s.reachable))
        assertEquals(SlideNav.Action.Go(3), s.nav.failed(s.reachable))
        // Nothing left to skip to: the wait ends and the item on screen stays.
        assertEquals(SlideNav.Action.Cancel, s.nav.failed(s.reachable))
        assertNull(s.nav.wait)
        assertEquals("/d/0.jpg", s.current)
    }

    @Test fun peekDoesNotMoveAndWrapsOnlyInNameOrder() {
        val s = Show(3)
        assertEquals("/d/2.jpg", s.order.peek(2)!!.path)
        assertEquals("/d/0.jpg", s.order.peek(3)!!.path) // name order wraps
        assertNull(s.order.peek(-1))
        assertEquals("/d/0.jpg", s.current)
        val r = Show(3, random = true)
        val toEnd = r.order.size - 1 - r.order.position
        assertNotNull(r.order.peek(toEnd))
        assertNull(r.order.peek(toEnd + 1)) // a new random cycle isn't known yet
    }
}
