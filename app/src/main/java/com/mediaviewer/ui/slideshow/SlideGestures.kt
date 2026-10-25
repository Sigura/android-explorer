package com.mediaviewer.ui.slideshow

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Pinch zoom and pan of the current image (README §5.2). */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val zoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun apply(zoom: Float, pan: Offset, size: IntSize) {
        scale = (scale * zoom).coerceIn(1f, 8f)
        val maxX = (scale - 1f) * size.width / 2f
        val maxY = (scale - 1f) * size.height / 2f
        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
        if (scale <= 1.01f) offset = Offset.Zero
    }
}

enum class TapZone { LEFT, CENTER, RIGHT }

/**
 * Slideshow pointer input (README §5.2):
 * - tap / click in the left ⅓ = previous, right ⅓ = next, at once (no double-tap wait);
 * - tap, double tap or long press in the center ⅓ opens the menu; the second tap of a double
 *   tap is swallowed so it doesn't close the menu it just opened; with the menu open, any tap
 *   outside it (the center of the image too, also a slow one) closes it;
 * - a two-finger horizontal slide changes the item (one finger would collide with the system
 *   Back edge gesture); pinch zooms; when zoomed, drags pan;
 * - the mouse wheel steps through the items, at most one step per [WHEEL_STEP_MS] so a trackpad
 *   fling doesn't skip a dozen items (README §2, "Desktops").
 */
fun Modifier.slideGestures(
    zoom: ZoomState,
    isMenuOpen: () -> Boolean,
    isInMenu: (Offset) -> Boolean,
    onZone: (TapZone) -> Unit,
    onCloseMenu: () -> Unit,
    onSwipe: (dx: Float) -> Unit,
    onWheel: (forward: Boolean) -> Unit = {},
): Modifier = pointerInput(Unit) {
    var menuOpenedAt = 0L
    val swipeMin = 64.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        // Touches on the menu itself belong to the menu.
        if (isMenuOpen() && isInMenu(down.position)) return@awaitEachGesture
        val x = down.position.x
        val zone = when {
            x < size.width / 3f -> TapZone.LEFT
            x > size.width * 2f / 3f -> TapZone.RIGHT
            else -> TapZone.CENTER
        }
        val slop = viewConfiguration.touchSlop
        var last = down.position

        // 0 = tap, 1 = drag, 2 = multi-touch, 3 = released after a hold; null = still held.
        suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.press(): Int {
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.count { it.pressed } > 1) return 2
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return 3
                if (!ch.pressed) return 0
                last = ch.position
                if ((ch.position - down.position).getDistance() > slop) return 1
            }
        }

        var result = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { press() }
        if (result == null) {
            // Long press: opens the menu in the center zone only; does nothing at the sides.
            if (zone == TapZone.CENTER && !isMenuOpen()) {
                onZone(TapZone.CENTER)
                menuOpenedAt = SystemClock.uptimeMillis()
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { it.consume() }
                } while (ev.changes.any { it.pressed })
                return@awaitEachGesture
            }
            // With the menu open, a slow tap anywhere outside it still closes it.
            result = press().let { if (it == 0 && !isMenuOpen()) 3 else it }
        }
        when (result) {
            0 -> {
                down.consume()
                val now = SystemClock.uptimeMillis()
                when {
                    isMenuOpen() -> {
                        // The second tap of the double tap that opened the menu: ignore it.
                        if (now - menuOpenedAt > viewConfiguration.doubleTapTimeoutMillis) onCloseMenu()
                    }
                    else -> {
                        if (zone == TapZone.CENTER) menuOpenedAt = now
                        onZone(zone)
                    }
                }
            }
            1, 2 -> {
                // One finger only pans a zoomed image: a one-finger edge swipe is the system Back
                // gesture and would fire both Back and a step, so stepping takes two fingers. They
                // either pinch or slide; whichever passes its threshold first wins the gesture.
                var mode = if (zoom.zoomed) TwoFinger.PINCH else TwoFinger.UNDECIDED
                var span = 1f
                var pan = Offset.Zero
                do {
                    val ev = awaitPointerEvent()
                    val two = ev.changes.count { it.pressed } > 1
                    if (two && mode != TwoFinger.PINCH) {
                        span *= ev.calculateZoom()
                        pan += ev.calculatePan()
                        if (mode == TwoFinger.UNDECIDED) {
                            if (abs(span - 1f) > PINCH_MIN) {
                                mode = TwoFinger.PINCH
                                zoom.apply(span, Offset.Zero, size)
                            } else if (abs(pan.x) > slop * 2 && abs(pan.x) > abs(pan.y)) {
                                mode = TwoFinger.SLIDE
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    } else if (mode == TwoFinger.PINCH || zoom.zoomed) {
                        zoom.apply(ev.calculateZoom(), ev.calculatePan(), size)
                        ev.changes.forEach { it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
                if (mode == TwoFinger.SLIDE && !isMenuOpen() && abs(pan.x) > swipeMin && abs(pan.x) > abs(pan.y) * 1.5f) onSwipe(pan.x)
            }
            else -> Unit
        }
    }
}.pointerInput(Unit) {
    // Ctrl + wheel zooms (README §5.2); the plain wheel steps (README §2, "Desktops").
    var wheelAt = 0L
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.type != PointerEventType.Scroll) continue
            val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (ev.keyboardModifiers.isCtrlPressed) {
                if (dy != 0f) zoom.apply(if (dy < 0) 1.15f else 1f / 1.15f, Offset.Zero, size)
            } else if (dy != 0f && !isMenuOpen() && !zoom.zoomed) {
                val now = SystemClock.uptimeMillis()
                if (now - wheelAt >= WHEEL_STEP_MS) {
                    wheelAt = now
                    onWheel(dy > 0)
                }
            }
            ev.changes.forEach { it.consume() }
        }
    }
}

private const val WHEEL_STEP_MS = 250L

/** Span change (±10 %) after which two fingers are a pinch rather than a slide. */
private const val PINCH_MIN = 0.1f

private enum class TwoFinger { UNDECIDED, PINCH, SLIDE }
