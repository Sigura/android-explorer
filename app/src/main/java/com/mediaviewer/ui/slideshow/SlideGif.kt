package com.mediaviewer.ui.slideshow

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mediaviewer.data.thumbs.GifCodec
import com.mediaviewer.data.thumbs.GifFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Slideshow GIF playback (README §5.1) that can start at a position and pause on the current
 * frame: the platform's animated drawables can do neither, they always start at frame 0. Frames
 * are decoded with [GifCodec.FrameReader] off the main thread into two bitmaps used in turn.
 */
@Stable
class SlideGif(private val file: File, private val startMs: Long) {
    enum class State { LOADING, PLAYING, UNSUPPORTED, FAILED }

    var state by mutableStateOf(State.LOADING)
        private set
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var error: String? = null
        private set

    /** Position in the animation of the frame on screen, in ms. */
    @Volatile var positionMs = startMs
        private set

    /** False while the slideshow is paused or its menu is open: the frame on screen stays. */
    val running = MutableStateFlow(true)

    /** Plays until cancelled. A file that isn't a GIF, or is too big, ends as [State.UNSUPPORTED]. */
    suspend fun play() = withContext(Dispatchers.Default) {
        try {
            loop()
        } catch (e: GifFormatException) {
            // Not a GIF inside (a renamed PNG, say): the platform decoder may still show it.
            if (state == State.LOADING) state = State.UNSUPPORTED
        } catch (e: OutOfMemoryError) {
            if (state == State.LOADING) state = State.UNSUPPORTED
        } catch (e: java.io.IOException) {
            if (state == State.LOADING) {
                error = e.message ?: "can't read"
                state = State.FAILED
            }
        }
    }

    private suspend fun loop() {
        val bitmaps = arrayOfNulls<Bitmap>(2)
        val images = arrayOfNulls<ImageBitmap>(2)
        var which = 0
        var skipTo = startMs
        /** Time left for the frame on screen when the next one is ready. */
        var leftMs = 0L
        while (coroutineContext.isActive) {
            var passMs = 0L
            var frames = 0
            GifCodec.FrameReader(file).use { g ->
                if (g.width.toLong() * g.height > MAX_PIXELS) throw GifFormatException("too big to play here")
                while (coroutineContext.isActive) {
                    val decodeStart = SystemClock.uptimeMillis()
                    if (!g.next()) break
                    frames++
                    val frameStart = passMs
                    passMs += g.delayMs
                    // Frames before the start position are decoded, not shown.
                    if (passMs <= skipTo) continue
                    val b = bitmaps[which] ?: Bitmap.createBitmap(g.width, g.height, Bitmap.Config.ARGB_8888).also {
                        bitmaps[which] = it
                        images[which] = it.asImageBitmap()
                    }
                    b.setPixels(g.canvas, 0, g.width, 0, 0, g.width, g.height)
                    // Decoding this frame used up part of the previous frame's time.
                    if (running.value) leftMs -= SystemClock.uptimeMillis() - decodeStart
                    waitPlaying(leftMs)
                    val shownAt = maxOf(frameStart, skipTo)
                    withContext(Dispatchers.Main) {
                        frame = images[which]
                        state = State.PLAYING
                    }
                    positionMs = shownAt
                    leftMs = passMs - shownAt
                    skipTo = 0
                    which = 1 - which
                }
            }
            // A still GIF has nothing more to show.
            if (passMs == 0L || (frames == 1 && skipTo == 0L)) return
            // A start position past the end (the GIF was left in a later loop) wraps around.
            if (skipTo > 0) skipTo %= passMs
        }
    }

    /** Waits until [ms] of playing time have passed; time while not [running] doesn't count. */
    private suspend fun waitPlaying(ms: Long) {
        var left = ms
        while (left > 0) {
            running.first { it }
            val start = SystemClock.uptimeMillis()
            withTimeoutOrNull(left) { running.first { !it } }
            left -= SystemClock.uptimeMillis() - start
        }
    }

    private companion object {
        /** Two ARGB frames of up to 4 MP (32 MB); bigger GIFs are left to the platform decoder. */
        const val MAX_PIXELS = 4_000_000L
    }
}
