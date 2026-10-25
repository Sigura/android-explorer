package com.mediaviewer.data.thumbs

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.min

/** Result of the GIF block scan (README §4.3, step 1). */
data class GifInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val durationMs: Long,
)

class GifFormatException(msg: String) : IOException(msg)

/** Buffered byte reader with cheap skips; GIF sub-blocks are at most 255 bytes. */
private class ByteReader(file: File) : Closeable {
    private val input = FileInputStream(file)
    private val buf = ByteArray(64 * 1024)
    private var pos = 0
    private var len = 0
    var offset = 0L
        private set

    private fun fill(): Boolean {
        len = input.read(buf)
        pos = 0
        return len > 0
    }

    fun u8(): Int {
        if (pos >= len && !fill()) throw EOFException()
        offset++
        return buf[pos++].toInt() and 0xff
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun read(dst: ByteArray, n: Int) {
        var done = 0
        while (done < n) {
            if (pos >= len && !fill()) throw EOFException()
            val c = min(n - done, len - pos)
            System.arraycopy(buf, pos, dst, done, c)
            pos += c
            done += c
            offset += c
        }
    }

    fun skip(n: Int) {
        var left = n
        while (left > 0) {
            if (pos >= len && !fill()) throw EOFException()
            val c = min(left, len - pos)
            pos += c
            left -= c
            offset += c
        }
    }

    fun skipSubBlocks() {
        while (true) {
            val n = u8()
            if (n == 0) return
            skip(n)
        }
    }

    override fun close() = input.close()
}

object GifCodec {
    /** Browsers show 0–1 cs delays as 10 cs; use the same so durations match what is seen. */
    fun normalizeDelayMs(cs: Int): Int = if (cs <= 1) 100 else cs * 10

    /**
     * Walks the block structure, skipping LZW data by its length bytes. Reads, never decodes.
     * A truncated file returns the frames counted so far, or throws if there are none.
     */
    fun scan(file: File, isCancelled: () -> Boolean = { false }): GifInfo {
        ByteReader(file).use { r ->
            val (w, h) = readHeader(r)
            var frames = 0
            var duration = 0L
            var delay = 100
            try {
                loop@ while (true) {
                    if (isCancelled()) throw InterruptedException()
                    when (r.u8()) {
                        0x21 -> {
                            val label = r.u8()
                            if (label == 0xF9) {
                                val size = r.u8()
                                r.u8() // packed
                                delay = normalizeDelayMs(r.u16())
                                r.skip(size - 3)
                                r.skipSubBlocks()
                            } else {
                                r.skipSubBlocks()
                            }
                        }
                        0x2C -> {
                            r.skip(8)
                            val packed = r.u8()
                            if (packed and 0x80 != 0) r.skip(3 * (1 shl ((packed and 7) + 1)))
                            r.u8() // LZW min code size
                            r.skipSubBlocks()
                            frames++
                            duration += delay
                            delay = 100
                        }
                        0x3B -> break@loop
                        else -> break@loop // junk after the last frame
                    }
                }
            } catch (e: EOFException) {
                if (frames == 0) throw GifFormatException("truncated GIF")
            }
            if (frames == 0) throw GifFormatException("GIF has no frames")
            return GifInfo(w, h, frames, duration)
        }
    }

    private fun readHeader(r: ByteReader): Pair<Int, Int> {
        val sig = ByteArray(6)
        try {
            r.read(sig, 6)
        } catch (e: EOFException) {
            throw GifFormatException("empty GIF")
        }
        if (sig[0] != 'G'.code.toByte() || sig[1] != 'I'.code.toByte() || sig[2] != 'F'.code.toByte()) {
            throw GifFormatException("not a GIF")
        }
        val w = r.u16()
        val h = r.u16()
        val packed = r.u8()
        r.u8() // background index
        r.u8() // aspect
        if (packed and 0x80 != 0) r.skip(3 * (1 shl ((packed and 7) + 1)))
        if (w <= 0 || h <= 0) throw GifFormatException("bad GIF size")
        return w to h
    }

    /**
     * One sequential decode pass (README §4.3, step 2). Calls [onFrame] with the composited canvas
     * (ARGB, width × height) for each frame index in [targets], then stops; frames after the last
     * target are never decoded. Only one canvas (plus one backup for "restore previous") is held.
     *
     * @return the number of frames decoded.
     */
    fun decodeFrames(
        file: File,
        targets: IntArray,
        isCancelled: () -> Boolean,
        onFrame: (index: Int, canvas: IntArray, width: Int, height: Int) -> Unit,
    ): Int {
        if (targets.isEmpty()) return 0
        val lastTarget = targets.max()
        FrameReader(file).use { g ->
            while (g.frames <= lastTarget) {
                if (isCancelled()) throw InterruptedException()
                if (!g.next()) break
                val i = g.frames - 1
                if (targets.contains(i)) onFrame(i, g.canvas, g.width, g.height)
            }
            return g.frames
        }
    }

    /**
     * Decodes a GIF frame by frame, in order: [next] composites the next frame into [canvas]. Used
     * by [decodeFrames] and by the slideshow, which plays GIFs from a position (README §5.1).
     */
    class FrameReader(file: File) : Closeable {
        private val r = ByteReader(file)
        val width: Int
        val height: Int
        private val globalTable: IntArray?

        /** The composited frame, ARGB, [width] × [height]; overwritten by the next [next]. */
        val canvas: IntArray

        /** Display time of the frame [next] decoded last (0–1 cs count as 100 ms). */
        var delayMs = 100
            private set

        /** Frames decoded so far. */
        var frames = 0
            private set

        private var backup: IntArray? = null
        private val lzw = Lzw()
        private var prevDisposal = 0
        private var prevRect = IntArray(4)
        private var pixels = ByteArray(0)
        private var done = false

        init {
            try {
                val sig = ByteArray(6)
                r.read(sig, 6)
                if (sig[0] != 'G'.code.toByte() || sig[1] != 'I'.code.toByte()) throw GifFormatException("not a GIF")
                width = r.u16()
                height = r.u16()
                if (width <= 0 || height <= 0 || width.toLong() * height > 40_000_000L) throw GifFormatException("bad GIF size ${width}x$height")
                val packed = r.u8()
                r.u8()
                r.u8()
                globalTable = if (packed and 0x80 != 0) readColorTable(r, 1 shl ((packed and 7) + 1)) else null
                canvas = IntArray(width * height)
            } catch (e: EOFException) {
                r.close()
                throw GifFormatException("empty GIF")
            } catch (e: Throwable) {
                r.close()
                throw e
            }
        }

        /** Decodes the next frame; false after the last one (or a truncated one). */
        fun next(): Boolean {
            if (done) return false
            var disposal = 0
            var transparentIndex = -1
            var delay = 100
            try {
                while (true) {
                    when (r.u8()) {
                        0x21 -> {
                            if (r.u8() == 0xF9) {
                                val size = r.u8()
                                val p = r.u8()
                                disposal = (p shr 2) and 7
                                delay = normalizeDelayMs(r.u16())
                                val ti = r.u8()
                                transparentIndex = if (p and 1 != 0) ti else -1
                                r.skip(size - 4)
                            }
                            r.skipSubBlocks()
                        }
                        0x2C -> {
                            val fx = r.u16()
                            val fy = r.u16()
                            val fw = r.u16()
                            val fh = r.u16()
                            val p = r.u8()
                            val interlaced = p and 0x40 != 0
                            val table = if (p and 0x80 != 0) readColorTable(r, 1 shl ((p and 7) + 1)) else globalTable
                                ?: IntArray(256) { 0xFF000000.toInt() or (it * 0x010101) }

                            // Dispose of the previous frame.
                            when (prevDisposal) {
                                2 -> clearRect(canvas, width, height, prevRect)
                                3 -> backup?.copyInto(canvas)
                            }
                            if (disposal == 3) {
                                val b = backup ?: IntArray(canvas.size).also { backup = it }
                                canvas.copyInto(b)
                            }

                            val count = fw * fh
                            if (pixels.size < count) pixels = ByteArray(count)
                            val minCode = r.u8()
                            val decoded = lzw.decode(r, minCode, count, pixels)
                            // Missing pixels of a truncated frame are drawn transparent.
                            if (decoded < count) pixels.fill(if (transparentIndex >= 0) transparentIndex.toByte() else 0, decoded, count)
                            draw(canvas, width, height, pixels, fx, fy, fw, fh, interlaced, table, transparentIndex)

                            prevDisposal = disposal
                            prevRect = intArrayOf(fx, fy, fw, fh)
                            delayMs = delay
                            frames++
                            return true
                        }
                        else -> {
                            // 0x3B trailer, or junk after the last frame.
                            done = true
                            return false
                        }
                    }
                }
            } catch (e: EOFException) {
                done = true
                if (frames == 0) throw GifFormatException("truncated GIF")
                return false
            }
        }

        override fun close() = r.close()
    }

    private fun readColorTable(r: ByteReader, n: Int): IntArray {
        val raw = ByteArray(n * 3)
        r.read(raw, raw.size)
        return IntArray(256) { i ->
            if (i < n) {
                0xFF000000.toInt() or
                    ((raw[i * 3].toInt() and 0xff) shl 16) or
                    ((raw[i * 3 + 1].toInt() and 0xff) shl 8) or
                    (raw[i * 3 + 2].toInt() and 0xff)
            } else {
                0xFF000000.toInt()
            }
        }
    }

    private fun clearRect(canvas: IntArray, w: Int, h: Int, rect: IntArray) {
        val x0 = rect[0].coerceIn(0, w)
        val y0 = rect[1].coerceIn(0, h)
        val x1 = (rect[0] + rect[2]).coerceIn(0, w)
        val y1 = (rect[1] + rect[3]).coerceIn(0, h)
        for (y in y0 until y1) canvas.fill(0, y * w + x0, y * w + x1)
    }

    private fun draw(
        canvas: IntArray, w: Int, h: Int, pixels: ByteArray,
        fx: Int, fy: Int, fw: Int, fh: Int, interlaced: Boolean, table: IntArray, transparent: Int,
    ) {
        var pass = 0
        var inc = 8
        var line = 0
        for (i in 0 until fh) {
            val row = if (interlaced) {
                while (line >= fh && pass < 3) {
                    pass++
                    when (pass) {
                        1 -> { line = 4; inc = 8 }
                        2 -> { line = 2; inc = 4 }
                        3 -> { line = 1; inc = 2 }
                    }
                }
                line.also { line += inc }
            } else {
                i
            }
            val y = fy + row
            if (y < 0 || y >= h) continue
            val src = i * fw
            val dst = y * w
            for (x in 0 until fw) {
                val cx = fx + x
                if (cx >= w) break
                val idx = pixels[src + x].toInt() and 0xff
                if (idx == transparent) continue
                canvas[dst + cx] = table[idx]
            }
        }
    }

    /**
     * GIF LZW decoder; tables are reused across frames. Each code's string is written straight
     * into the output, back to front along the prefix chain (no intermediate stack), and the
     * data sub-blocks are read whole.
     */
    private class Lzw {
        private val prefix = ShortArray(4096)
        private val suffix = ByteArray(4096)
        private val length = ShortArray(4096)
        private val block = ByteArray(256)

        /** Decodes up to [count] pixel indices; returns how many were produced. */
        fun decode(r: ByteReader, minCodeSize: Int, count: Int, out: ByteArray): Int {
            if (minCodeSize !in 1..11) {
                r.skipSubBlocks()
                return 0
            }
            val clear = 1 shl minCodeSize
            val eoi = clear + 1
            var codeSize = minCodeSize + 1
            var codeMask = (1 shl codeSize) - 1
            var available = clear + 2
            var oldCode = -1
            var datum = 0
            var bits = 0
            var pi = 0
            var blockLen = 0
            var blockPos = 0
            var ended = false
            for (c in 0 until clear) {
                prefix[c] = -1
                suffix[c] = c.toByte()
                length[c] = 1
            }
            outer@ while (pi < count) {
                while (bits < codeSize) {
                    if (blockPos == blockLen) {
                        blockLen = r.u8()
                        if (blockLen == 0) {
                            ended = true
                            break@outer
                        }
                        r.read(block, blockLen)
                        blockPos = 0
                    }
                    datum = datum or ((block[blockPos++].toInt() and 0xff) shl bits)
                    bits += 8
                }
                val code = datum and codeMask
                datum = datum ushr codeSize
                bits -= codeSize
                if (code == clear) {
                    codeSize = minCodeSize + 1
                    codeMask = (1 shl codeSize) - 1
                    available = clear + 2
                    oldCode = -1
                    continue
                }
                if (code == eoi || code > available || (code == available && oldCode < 0)) break
                // The string for this code; for code == available (KwKwK) it is old + first(old).
                val base = if (code < available) code else oldCode
                val len = length[base].toInt()
                val total = if (code < available) len else len + 1
                if (pi + total > count) {
                    // Last string runs past the frame: write what fits.
                    var c = base
                    val keep = count - pi
                    var k = len - 1
                    while (c >= 0 && k >= 0) {
                        if (k < keep) out[pi + k] = suffix[c]
                        c = prefix[c].toInt()
                        k--
                    }
                    if (code >= available && len < keep) out[pi + len] = out[pi]
                    pi = count
                    break
                }
                var c = base
                var k = pi + len - 1
                while (k >= pi) {
                    out[k--] = suffix[c]
                    c = prefix[c].toInt()
                }
                val firstByte = out[pi]
                if (code >= available) out[pi + len] = firstByte
                pi += total
                if (oldCode >= 0 && available < 4096) {
                    prefix[available] = oldCode.toShort()
                    suffix[available] = firstByte
                    length[available] = (length[oldCode] + 1).toShort()
                    available++
                    if (available and codeMask == 0 && available < 4096) {
                        codeSize++
                        codeMask += available
                    }
                }
                oldCode = code
            }
            // Skip the rest of this frame's data (the current sub-block was already read whole).
            if (!ended) r.skipSubBlocks()
            return pi
        }
    }
}
