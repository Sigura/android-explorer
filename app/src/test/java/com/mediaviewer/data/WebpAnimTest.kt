package com.mediaviewer.data

import com.mediaviewer.data.thumbs.WebpAnim
import com.mediaviewer.data.thumbs.WebpAnim.Step
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** The container scan on synthetic files; the bitstreams are dummies (the platform decodes them). */
class WebpAnimTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Frame(val x: Int, val y: Int, val w: Int, val h: Int, val ms: Int, val blend: Boolean, val dispose: Boolean, val chunks: ByteArray)

    private fun le(v: Int, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray())
        out.write(le(body.size, 4))
        out.write(body)
        if (body.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }
    /** VP8L with a 14+14-bit size and the alpha bit; padded with junk to an odd length. */
    private fun vp8l(w: Int, h: Int, alpha: Boolean): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14) or ((if (alpha) 1 else 0) shl 28)
        return chunk("VP8L", byteArrayOf(0x2f) + le(bits, 4) + byteArrayOf(1, 2))
    }
    private fun vp8() = chunk("VP8 ", byteArrayOf(9, 9, 9))
    private fun alph() = chunk("ALPH", byteArrayOf(0, 7))

    private fun file(cw: Int, ch: Int, frames: List<Frame>, animated: Boolean = true): File {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        body.write(chunk("VP8X", byteArrayOf(if (animated) 0x12 else 0x10, 0, 0, 0) + le(cw - 1, 3) + le(ch - 1, 3)))
        body.write(chunk("ANIM", le(0, 4) + le(0, 2)))
        for (f in frames) {
            val flags = (if (f.blend) 0 else 2) or (if (f.dispose) 1 else 0)
            body.write(chunk("ANMF", le(f.x / 2, 3) + le(f.y / 2, 3) + le(f.w - 1, 3) + le(f.h - 1, 3) + le(f.ms, 3) + byteArrayOf(flags.toByte()) + f.chunks))
        }
        val bytes = body.toByteArray()
        return tmp.newFile().apply { writeBytes("RIFF".toByteArray() + le(bytes.size, 4) + bytes) }
    }

    @Test fun scanReadsFramesWithoutDecoding() {
        val f = file(
            300, 200,
            listOf(
                Frame(0, 0, 300, 200, 70, blend = true, dispose = false, vp8l(300, 200, alpha = true)),
                Frame(10, 20, 33, 41, 120, blend = false, dispose = true, alph() + vp8()),
                Frame(0, 0, 300, 200, 0, blend = true, dispose = false, vp8()),
            ),
        )
        val info = WebpAnim.scan(f)!!
        assertEquals(300, info.width)
        assertEquals(200, info.height)
        assertEquals(190L, info.durationMs)
        val (a, b, c) = info.frames
        assertTrue(a.hasAlpha && a.blend && !a.disposeToBackground)
        assertEquals(listOf(10, 20, 33, 41), listOf(b.x, b.y, b.width, b.height))
        assertTrue(b.hasAlpha && !b.blend && b.disposeToBackground)
        assertFalse(c.hasAlpha)
        // Opaque full frame → key; the partial one isn't.
        assertArrayEquals(booleanArrayOf(true, false, true), WebpAnim.keyFrames(info))
    }

    @Test fun stillsAreNotAnimated() {
        assertNull(WebpAnim.scan(file(10, 10, emptyList(), animated = false)))
        // Animated flag, but no complete frame (a cut-off download): treated as a still.
        assertNull(WebpAnim.scan(file(10, 10, emptyList(), animated = true)))
        assertFalse(WebpAnim.isAnimated("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
    }

    @Test fun keyFrameAfterFullDispose() {
        val full = Frame(0, 0, 8, 8, 10, blend = true, dispose = true, vp8l(8, 8, alpha = true))
        val part = Frame(2, 2, 4, 4, 10, blend = true, dispose = false, vp8l(4, 4, alpha = true))
        val info = WebpAnim.scan(file(8, 8, listOf(full, full, part, part)))!!
        // 1: blended with alpha, but frame 0 was cleared; 2: frame 1 cleared the canvas; 3: depends on 2.
        assertArrayEquals(booleanArrayOf(true, true, true, false), WebpAnim.keyFrames(info))
    }

    @Test fun standaloneFrames() {
        val f = file(
            64, 64,
            listOf(
                Frame(0, 0, 64, 64, 10, blend = true, dispose = false, vp8l(64, 64, alpha = false)),
                Frame(4, 4, 9, 7, 10, blend = true, dispose = false, alph() + vp8()),
            ),
        )
        val info = WebpAnim.scan(f)!!
        RandomAccessFile(f, "r").use { raf ->
            val lossless = WebpAnim.standalone(raf, info.frames[0])
            assertEquals("RIFF", String(lossless, 0, 4))
            assertEquals(lossless.size - 8, le32(lossless, 4))
            assertEquals("WEBPVP8L", String(lossless, 8, 8))
            val alpha = WebpAnim.standalone(raf, info.frames[1])
            assertEquals("WEBPVP8X", String(alpha, 8, 8))
            assertEquals(0x10, alpha[20].toInt())
            assertEquals(8, le32(alpha, 24) and 0xffffff) // width - 1
            assertEquals(6, le32(alpha, 27) and 0xffffff) // height - 1
            assertEquals("ALPH", String(alpha, 30, 4))
            assertEquals(alpha.size - 8, le32(alpha, 4))
        }
    }

    private fun le32(b: ByteArray, o: Int) = (0..3).sumOf { (b[o + it].toInt() and 0xff) shl (8 * it) }

    @Test fun planStartsAtKeyFramesAndContinues() {
        val keys = booleanArrayOf(true, false, false, false, true, false, false, false, false, false)
        val steps = WebpAnim.plan(keys, intArrayOf(9, 0, 2, 7))
        assertEquals(
            listOf(
                Step(0, reset = true, emit = true),
                // 2 continues from 0.
                Step(1, reset = false, emit = false), Step(2, reset = false, emit = true),
                // 7 starts again at key frame 4 instead of decoding 3.
                Step(4, reset = true, emit = false), Step(5, false, false), Step(6, false, false), Step(7, false, true),
                // 9 continues from 7.
                Step(8, false, false), Step(9, false, true),
            ),
            steps,
        )
        assertEquals(listOf(Step(3, reset = true, emit = true)), WebpAnim.plan(BooleanArray(5) { true }, intArrayOf(3, 3, 99)))
    }
}
