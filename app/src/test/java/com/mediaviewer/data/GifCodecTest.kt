package com.mediaviewer.data

import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.thumbs.GifCodec
import com.mediaviewer.data.thumbs.GifFormatException
import com.mediaviewer.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class GifCodecTest {
    /** A w×h GIF with [frames] frames; frame i is filled with palette color (i % 4), delay [delayCs]. */
    private fun gif(w: Int, h: Int, frames: Int, delayCs: Int, interlaced: Boolean = false): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) {
            o.write(v and 0xff)
            o.write(v shr 8)
        }
        o.write("GIF89a".toByteArray())
        u16(w)
        u16(h)
        o.write(0x81) // global table, 4 colors
        o.write(0)
        o.write(0)
        val palette = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)
        for (c in palette) {
            o.write(c shr 16 and 0xff)
            o.write(c shr 8 and 0xff)
            o.write(c and 0xff)
        }
        // NETSCAPE loop extension, to check that other extensions are skipped.
        o.write(byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0))
        repeat(frames) { i ->
            o.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
            u16(delayCs)
            o.write(0)
            o.write(0)
            o.write(0x2C)
            u16(0); u16(0); u16(w); u16(h)
            o.write(if (interlaced) 0x40 else 0)
            o.write(2) // LZW min code size
            o.write(lzw(IntArray(w * h) { i % 4 }))
        }
        o.write(0x3B)
        return o.toByteArray()
    }

    /** Uncompressed-style LZW stream: a clear code before every pixel keeps the code size at 3. */
    private fun lzw(pixels: IntArray): ByteArray {
        val bits = ArrayList<Int>()
        fun code(c: Int) = repeat(3) { bits.add(c shr it and 1) }
        for (p in pixels) {
            code(4) // clear
            code(p)
        }
        code(5) // end of information
        val bytes = ByteArrayOutputStream()
        var cur = 0
        var n = 0
        for (b in bits) {
            cur = cur or (b shl n)
            n++
            if (n == 8) {
                bytes.write(cur)
                cur = 0
                n = 0
            }
        }
        if (n > 0) bytes.write(cur)
        val data = bytes.toByteArray()
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val len = minOf(255, data.size - i)
            out.write(len)
            out.write(data, i, len)
            i += len
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun tmp(bytes: ByteArray): File = File.createTempFile("gif-test", ".gif").apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    @Test fun scanCountsFramesAndDuration() {
        val info = GifCodec.scan(tmp(gif(8, 6, 240, 8)))
        assertEquals(8, info.width)
        assertEquals(6, info.height)
        assertEquals(240, info.frameCount)
        assertEquals(240 * 80L, info.durationMs)
    }

    @Test fun zeroDelayCountsAs100ms() {
        assertEquals(6 * 100L, GifCodec.scan(tmp(gif(4, 4, 6, 0))).durationMs)
    }

    @Test fun truncatedKeepsCompleteFrames() {
        val full = gif(4, 4, 10, 10)
        val info = GifCodec.scan(tmp(full.copyOf(full.size * 6 / 10)))
        assertEquals(true, info.frameCount in 5..6)
    }

    @Test fun emptyAndJunkFail() {
        assertThrows(GifFormatException::class.java) { GifCodec.scan(tmp(ByteArray(0))) }
        assertThrows(GifFormatException::class.java) { GifCodec.scan(tmp("<html>".toByteArray())) }
    }

    @Test fun decodesOnlyTargetFrames() {
        val f = tmp(gif(5, 7, 20, 10, interlaced = true))
        val got = HashMap<Int, Int>()
        val decoded = GifCodec.decodeFrames(f, intArrayOf(2, 6, 11), { false }) { i, canvas, w, h ->
            assertEquals(5, w)
            assertEquals(7, h)
            got[i] = canvas[w * h - 1]
        }
        assertEquals(12, decoded) // stops after the last target
        assertEquals(0xFF0000FF.toInt(), got[2]) // color 2 = blue
        assertEquals(0xFF0000FF.toInt(), got[6])
        assertEquals(0xFFFFFFFF.toInt(), got[11]) // color 3 = white
    }

    @Test fun sniffJunkExtensions() {
        val f = File.createTempFile("junk-test", ".php?id=300737").apply { deleteOnExit(); writeBytes(gif(2, 2, 1, 1)) }
        assertNull(FileTypes.byExtension("name.php?id=300737"))
        assertNull(FileTypes.byExtension("name.mp4,gif"))
        assertEquals(MediaType.ANIMATED, FileTypes.detect(f).type)
        assertEquals(MediaType.ANIMATED, FileTypes.byExtension("clip.mp4.gif")?.type)
        assertEquals(MediaType.PARTIAL, FileTypes.byExtension("x.mp4.crdownload")?.type)
    }
}
