package com.mediaviewer.ui.browser

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Pointer, swipe and key scrub state of one tile (README §4.1 and §4.3). */
@Stable
class TileInput {
    /** Horizontal swipe position inside the tile, 0..1, while the finger is down. */
    var swipe by mutableStateOf<Float?>(null)
    /** Mouse hover position, 0..1. */
    var hoverX by mutableStateOf<Float?>(null)
    /** `Shift` + ←/→ (or long-press ←/→ on TV) steps. */
    var keyStep by mutableStateOf<Int?>(null)
    var focused by mutableStateOf(false)
    /** Uptime of the last pointer move; the pointer "rests" ~600 ms after it. */
    var lastMove by mutableLongStateOf(0L)
    /** The file has no thumbnail of its own (broken or timed out), so its name is always shown. */
    var noThumb by mutableStateOf(false)

    val hovered: Boolean get() = hoverX != null
    val scrubbing: Boolean get() = swipe != null || hoverX != null || keyStep != null

    /** The scrub position mapped onto [n] slots, or null when not scrubbing. */
    fun index(n: Int, useHover: Boolean = true): Int? {
        if (n <= 0) return null
        swipe?.let { return (it * n).toInt().coerceIn(0, n - 1) }
        keyStep?.let { return it.mod(n) }
        if (useHover) hoverX?.let { return (it * n).toInt().coerceIn(0, n - 1) }
        return null
    }
}

/** Thumbnail work waits until the grid has stopped scrolling for ~150 ms (README §2, rule 4). */
@Stable
class ScrollGate(private val grid: LazyGridState) {
    /** Set while the fast-scroll thumb is dragged. */
    var dragging by mutableStateOf(false)

    suspend fun awaitIdle() {
        while (true) {
            snapshotFlow { grid.isScrollInProgress || dragging }.first { !it }
            delay(150)
            if (!grid.isScrollInProgress && !dragging) return
        }
    }
}

/** Mouse hover: horizontal position scrubs (README §4.1, §4.3). */
fun Modifier.tileHover(input: TileInput): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull() ?: continue
            if (ch.type != PointerType.Mouse) continue
            when (ev.type) {
                PointerEventType.Enter, PointerEventType.Move -> if (!ch.pressed) {
                    input.hoverX = (ch.position.x / size.width).coerceIn(0f, 1f)
                    input.lastMove = SystemClock.uptimeMillis()
                }
                PointerEventType.Exit -> input.hoverX = null
            }
        }
    }
}

/**
 * Tap, long press, double-click, right-click, horizontal swipe scrub and drag-to-select for a
 * tile (README §4.5 and §4.6).
 *
 * - Touch tap opens at once. A mouse click outside selection mode waits for the double-click
 *   timeout, because a double-click enters selection mode (README §12).
 * - In selection mode a tap or click toggles; the second tap of a double tap is ignored, so a
 *   double tap also toggles exactly once.
 * - After a long press, dragging across tiles selects a range.
 * - With desktop clicks (README §2, "Desktops", mouse only): a click focuses the tile, or applies
 *   [onDesktopClick] (`Ctrl` / `Shift` / selection mode); a double-click opens; right-click opens
 *   the context menu; pressing and dragging drags the file out to another app.
 */
fun Modifier.tileGestures(
    scrubbable: Boolean,
    input: TileInput,
    coords: () -> LayoutCoordinates?,
    isSelectionMode: () -> Boolean,
    onTap: () -> Unit,
    onSelect: () -> Unit,
    onDragSelect: (Offset) -> Unit,
    onDragSelectEnd: () -> Unit,
    desktop: () -> Boolean = { false },
    onDesktopClick: (ctrl: Boolean, shift: Boolean) -> Unit = { _, _ -> },
    onContextMenu: (Offset) -> Unit = {},
    onDragOut: () -> Unit = {},
): Modifier = pointerInput(scrubbable) {
    var lastToggle = 0L
    var lastClick = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val mouse = down.type == PointerType.Mouse
        val desktopClick = mouse && desktop()
        if (mouse && currentEvent.buttons.isSecondaryPressed) {
            down.consume()
            if (desktopClick) onContextMenu(down.position) else onSelect()
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        val slop = viewConfiguration.touchSlop
        if (desktopClick) {
            val mods = currentEvent.keyboardModifiers
            var dragged = false
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!ch.pressed) break
                if ((ch.position - down.position).getDistance() > slop) {
                    dragged = true
                    break
                }
            }
            if (dragged) {
                onDragOut()
                return@awaitEachGesture
            }
            val now = SystemClock.uptimeMillis()
            val plain = !mods.isCtrlPressed && !mods.isShiftPressed
            if (plain && now - lastClick < viewConfiguration.doubleTapTimeoutMillis) {
                lastClick = 0L
                if (!isSelectionMode()) onTap()
            } else {
                lastClick = now
                onDesktopClick(mods.isCtrlPressed, mods.isShiftPressed)
            }
            return@awaitEachGesture
        }
        // 0 = cancelled (scroll, pinch), 1 = tap, 2 = horizontal swipe; null = long press.
        val result = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var r = 0
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.count { it.pressed } > 1) break
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    if (!ch.isConsumed) r = 1
                    break
                }
                if (ch.isConsumed) break
                val d = ch.position - down.position
                if (scrubbable && abs(d.x) > slop && abs(d.x) > abs(d.y) * 1.5f) {
                    r = 2
                    break
                }
                if (d.getDistance() > slop) break
            }
            r
        }
        when (result) {
            null -> {
                onSelect()
                lastToggle = SystemClock.uptimeMillis()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    ch.consume()
                    coords()?.let { if (it.isAttached) onDragSelect(it.localToRoot(ch.position)) }
                }
                onDragSelectEnd()
            }
            1 -> {
                val now = SystemClock.uptimeMillis()
                if (isSelectionMode()) {
                    if (now - lastToggle > viewConfiguration.doubleTapTimeoutMillis) onTap()
                    lastToggle = now
                } else if (mouse) {
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                        awaitFirstDown(requireUnconsumed = false)
                    }
                    if (second != null) {
                        second.consume()
                        onSelect()
                        lastToggle = SystemClock.uptimeMillis()
                        waitForUpOrCancellation()
                    } else {
                        onTap()
                    }
                } else {
                    onTap()
                }
            }
            2 -> {
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    ch.consume()
                    input.swipe = (ch.position.x / size.width).coerceIn(0f, 1f)
                }
                input.swipe = null
            }
        }
    }
}
