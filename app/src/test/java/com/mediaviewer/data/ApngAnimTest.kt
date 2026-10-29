package com.mediaviewer.data

import com.mediaviewer.data.tags.ArraySource
import com.mediaviewer.data.thumbs.ApngAnim
import com.mediaviewer.data.thumbs.ApngFrame
import com.mediaviewer.data.thumbs.FrameOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO

/** Animated PNG strips (todo §10.10): scan, standalone frames, key frames and compositing order. */
class ApngAnimTest {
    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value
        return be32(data.size.toLong()) + t + data + be32(crc)
    }

    /** RGBA rows, each pixel [argb], filter byte 0, deflated. */
    private fun pixels(w: Int, h: Int, rgba: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        repeat(h) {
            raw.write(0)
            repeat(w) { raw.write(byteArrayOf((rgba ushr 24).toByte(), (rgba ushr 16).toByte(), (rgba ushr 8).toByte(), rgba.toByte())) }
        }
        val d = Deflater().apply { setInput(raw.toByteArray()); finish() }
        val out = ByteArray(65_536)
        val n = d.deflate(out)
        d.end()
        return out.copyOf(n)
    }

    private fun ihdr(w: Int, h: Int) = chunk("IHDR", be32(w.toLong()) + be32(h.toLong()) + byteArrayOf(8, 6, 0, 0, 0))

    private class F(val x: Int, val y: Int, val w: Int, val h: Int, val rgba: Int, val dispose: Int = 0, val over: Boolean = false, val delayMs: Int = 100)

    private fun fctl(seq: Int, f: F) = chunk(
        "fcTL",
        be32(seq.toLong()) + be32(f.w.toLong()) + be32(f.h.toLong()) + be32(f.x.toLong()) + be32(f.y.toLong()) +
            be16(f.delayMs) + be16(1000) + byteArrayOf(f.dispose.toByte(), if (f.over) 1 else 0),
    )

    /** An APNG of [frames] on a [w]×[h] canvas; the first frame is the default image (IDAT), the rest fdAT. */
    private fun apng(w: Int, h: Int, vararg frames: F, defaultOutside: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(ihdr(w, h))
        out.write(chunk("acTL", be32(frames.size.toLong()) + be32(0)))
        out.write(chunk("gAMA", be32(45455)))
        var seq = 0
        if (defaultOutside) out.write(chunk("IDAT", pixels(w, h, 0x000000FF)))
        frames.forEachIndexed { i, f ->
            out.write(fctl(seq++, f))
            if (i == 0 && !defaultOutside) {
                out.write(chunk("IDAT", pixels(f.w, f.h, f.rgba)))
            } else {
                out.write(chunk("fdAT", be32((seq++).toLong()) + pixels(f.w, f.h, f.rgba)))
            }
        }
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private val red = 0xFF0000FF.toInt()
    private val green = 0x00FF00FF
    private val blue = 0x0000FFFF

    @Test fun scanFindsFramesWithoutDecoding() {
        val file = apng(8, 6, F(0, 0, 8, 6, red), F(2, 1, 4, 3, green, dispose = 1, over = true, delayMs = 250), F(0, 0, 8, 6, blue))
        assertTrue(ApngAnim.looksAnimated(file.copyOf(64)))
        // acTL after another chunk is found too.
        val late = file.copyOf()
        assertTrue(ApngAnim.looksAnimated(late))
        val info = ApngAnim.scan(ArraySource(file))!!
        assertEquals(8 to 6, info.width to info.height)
        assertEquals(3, info.frames.size)
        assertEquals(listOf("IDAT", "fdAT", "fdAT"), info.frames.map { it.data.single().type })
        val f1 = info.frames[1]
        assertEquals(listOf(2, 1, 4, 3), listOf(f1.x, f1.y, f1.width, f1.height))
        assertEquals(250, f1.delayMs)
        assertEquals(1, f1.dispose)
        assertTrue(f1.over)
        assertEquals(450L, info.durationMs)
        assertEquals(listOf("gAMA"), info.shared.map { it.type })
    }

    @Test fun standaloneFramesAreValidPngs() {
        val file = apng(8, 6, F(0, 0, 8, 6, red), F(2, 1, 4, 3, green), F(0, 0, 8, 6, blue))
        val src = ArraySource(file)
        val info = ApngAnim.scan(src)!!
        val expect = listOf(Triple(8, 6, red), Triple(4, 3, green), Triple(8, 6, blue))
        info.frames.forEachIndexed { i, f ->
            // ImageIO checks every CRC and the zlib stream: a bad rebuild fails here.
            val img = ImageIO.read(ApngAnim.standalone(src, info, f).inputStream())!!
            val (w, h, rgba) = expect[i]
            assertEquals("frame $i size", w to h, img.width to img.height)
            val argb = img.getRGB(w / 2, h / 2)
            val wanted = ((rgba and 0xFF) shl 24) or (rgba ushr 8)
            assertEquals("frame $i colour", wanted, argb)
        }
    }

    @Test fun acTlAfterOtherChunks() {
        val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val f = F(0, 0, 4, 4, red)
        val file = sig + ihdr(4, 4) + chunk("sRGB", byteArrayOf(0)) + chunk("pHYs", ByteArray(9)) + chunk("acTL", be32(1) + be32(0)) +
            fctl(0, f) + chunk("IDAT", pixels(4, 4, red)) + chunk("IEND", ByteArray(0))
        assertTrue(ApngAnim.looksAnimated(file.copyOf(4096.coerceAtMost(file.size))))
        assertEquals(1, ApngAnim.scan(ArraySource(file))!!.frames.size)
    }

    @Test fun stillPngsAndBrokenOnes() {
        val still = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
            ihdr(4, 4) + chunk("IDAT", pixels(4, 4, red)) + chunk("IEND", ByteArray(0))
        assertFalse(ApngAnim.looksAnimated(still))
        assertNull(ApngAnim.scan(ArraySource(still)))
        assertNull(ApngAnim.scan(ArraySource("not a png at all, really not".toByteArray())))
        // Cut in the middle of the second frame: the first one is still there.
        val file = apng(8, 6, F(0, 0, 8, 6, red), F(0, 0, 8, 6, green))
        val cut = ApngAnim.scan(ArraySource(file.copyOf(file.size - 30)))!!
        assertEquals(1, cut.frames.size)
        // A frame that reaches outside the canvas is dropped.
        assertEquals(1, ApngAnim.scan(ArraySource(apng(8, 6, F(0, 0, 8, 6, red), F(6, 0, 4, 2, green))))!!.frames.size)
    }

    @Test fun defaultImageOutsideTheAnimation() {
        val info = ApngAnim.scan(ArraySource(apng(4, 4, F(0, 0, 4, 4, red), F(0, 0, 4, 4, green), defaultOutside = true)))!!
        assertEquals(2, info.frames.size)
        assertTrue(info.frames.all { it.data.single().type == "fdAT" })
    }

    // ------------------------------------------------------------------ compositing order

    private class Recorder : FrameOps {
        val log = ArrayList<String>()
        override fun reset() { log += "reset" }
        override fun clear(x: Int, y: Int, w: Int, h: Int) { log += "clear $x,$y ${w}x$h" }
        override fun save() { log += "save" }
        override fun restore() { log += "restore" }
        override fun draw(index: Int, frame: ApngFrame, replace: Boolean) { log += "draw $index" + if (replace) " src" else " over" }
        override fun emit(index: Int) { log += "emit $index" }
    }

    @Test fun disposeAndBlendInOrder() {
        val info = ApngAnim.scan(
            ArraySource(
                apng(
                    8, 8,
                    F(0, 0, 8, 8, red),
                    F(1, 1, 2, 2, green, dispose = 1, over = true),
                    F(4, 4, 2, 2, blue, dispose = 2, over = true),
                    F(0, 0, 2, 2, green, over = true),
                ),
            ),
        )!!
        val r = Recorder()
        ApngAnim.render(info, intArrayOf(0, 3), r)
        assertEquals(
            listOf(
                "reset", "draw 0 src", "emit 0",
                "draw 1 over",
                "clear 1,1 2x2", "save", "draw 2 over",
                "restore", "draw 3 over", "emit 3",
            ),
            r.log,
        )
    }

    @Test fun keyFramesSkipTheStart() {
        val info = ApngAnim.scan(
            ArraySource(
                apng(
                    8, 8,
                    F(0, 0, 8, 8, red),
                    F(1, 1, 2, 2, green, over = true),
                    F(0, 0, 8, 8, blue), // full canvas, replaces: a key frame
                    F(1, 1, 2, 2, green, over = true),
                ),
            ),
        )!!
        assertEquals(listOf(true, false, true, false), ApngAnim.keyFrames(info).toList())
        val r = Recorder()
        ApngAnim.render(info, intArrayOf(3), r)
        assertEquals(listOf("reset", "draw 2 src", "draw 3 over", "emit 3"), r.log)
    }
}
