package com.mediaviewer.data.thumbs

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** One `ANMF` frame of an animated WebP (todo §10.2). Offsets are in canvas pixels. */
class WebpFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    /** Alpha-blend over the canvas; false = replace the frame's rectangle. */
    val blend: Boolean,
    /** After showing, clear the frame's rectangle to transparent. */
    val disposeToBackground: Boolean,
    /** The frame's own chunks (`ALPH`, `VP8 `, `VP8L`, …) in the file, after the 16-byte `ANMF` header. */
    val dataOffset: Long,
    val dataSize: Int,
    val hasAlpha: Boolean,
) {
    fun covers(canvasW: Int, canvasH: Int) = x == 0 && y == 0 && width == canvasW && height == canvasH
}

class WebpInfo(val width: Int, val height: Int, val frames: List<WebpFrame>) {
    val durationMs: Long get() = frames.sumOf { it.durationMs.toLong() }
}

/**
 * Animated WebP for tile strips (todo §10.2). `AnimatedImageDrawable` can't seek, so the container
 * is read here: a chunk scan finds the frames without decoding them; each needed frame is rebuilt
 * as a standalone WebP for the platform decoder and composited by the caller. Pure Kotlin.
 */
object WebpAnim {
    private const val MAX_FRAMES = 100_000

    /** True when [head] (the first 21+ bytes of a file) is an animated WebP: `VP8X` with the animation flag. */
    fun isAnimated(head: ByteArray): Boolean =
        head.size >= 21 && fourcc(head, 0) == "RIFF" && fourcc(head, 8) == "WEBP" && fourcc(head, 12) == "VP8X" &&
            (head[20].toInt() and 0x02) != 0

    /** The frames of an animated WebP; null when the file is a still WebP (or not a WebP at all). */
    fun scan(file: File): WebpInfo? = RandomAccessFile(file, "r").use { scan(it) }

    fun scan(f: RandomAccessFile): WebpInfo? {
        val len = f.length()
        val head = ByteArray(30)
        if (len < 30) return null
        f.seek(0)
        f.readFully(head)
        if (!isAnimated(head)) return null
        val canvasW = u24(head, 24) + 1
        val canvasH = u24(head, 27) + 1
        // The RIFF size may be wrong in truncated files: never read past the real end.
        val end = minOf(len, 8L + u32(head, 4))
        val frames = ArrayList<WebpFrame>()
        var pos = 12L
        val hdr = ByteArray(8)
        val anmf = ByteArray(16)
        while (pos + 8 <= end && frames.size < MAX_FRAMES) {
            f.seek(pos)
            f.readFully(hdr)
            val id = fourcc(hdr, 0)
            val size = u32(hdr, 4)
            val body = pos + 8
            if (id == "ANMF" && size >= 16 && body + size <= end) {
                f.seek(body)
                f.readFully(anmf)
                val flags = anmf[15].toInt()
                val dataOffset = body + 16
                val dataSize = (size - 16).toInt()
                frames.add(
                    WebpFrame(
                        x = u24(anmf, 0) * 2,
                        y = u24(anmf, 3) * 2,
                        width = u24(anmf, 6) + 1,
                        height = u24(anmf, 9) + 1,
                        durationMs = u24(anmf, 12),
                        blend = (flags and 0x02) == 0,
                        disposeToBackground = (flags and 0x01) != 0,
                        dataOffset = dataOffset,
                        dataSize = dataSize,
                        hasAlpha = frameHasAlpha(f, dataOffset, dataSize),
                    ),
                )
            }
            pos = body + size + (size and 1) // chunks are padded to an even size
        }
        if (frames.isEmpty()) throw IOException("animated WebP without frames")
        return WebpInfo(canvasW, canvasH, frames)
    }

    /** `ALPH` before the bitstream, or a lossless bitstream with its alpha bit set. */
    private fun frameHasAlpha(f: RandomAccessFile, offset: Long, size: Int): Boolean {
        var pos = offset
        val end = offset + size
        val b = ByteArray(13)
        while (pos + 8 <= end) {
            f.seek(pos)
            val n = minOf(13L, end - pos).toInt()
            f.readFully(b, 0, n)
            val id = fourcc(b, 0)
            val sz = u32(b, 4)
            when (id) {
                "ALPH" -> return true
                "VP8 " -> return false
                // VP8L: signature 0x2f, then 14 + 14 bits of size, then the alpha bit.
                "VP8L" -> return n >= 13 && (b[12].toInt() and 0x10) != 0
            }
            pos += 8 + sz + (sz and 1)
        }
        return false
    }

    /**
     * Frame [i] as a standalone still WebP: `VP8X` (when there is an `ALPH` chunk) + the frame's own
     * chunks, which the platform decoder reads like any WebP file.
     */
    fun standalone(f: RandomAccessFile, frame: WebpFrame): ByteArray {
        val data = ByteArray(frame.dataSize)
        f.seek(frame.dataOffset)
        f.readFully(data)
        val alph = data.size >= 4 && fourcc(data, 0) == "ALPH"
        val vp8x = if (alph) 18 else 0
        val riffSize = 4 + vp8x + data.size
        val out = ByteArray(8 + riffSize)
        put4cc(out, 0, "RIFF")
        putU32(out, 4, riffSize.toLong())
        put4cc(out, 8, "WEBP")
        var p = 12
        if (alph) {
            put4cc(out, p, "VP8X")
            putU32(out, p + 4, 10)
            out[p + 8] = 0x10 // alpha
            putU24(out, p + 12, frame.width - 1)
            putU24(out, p + 15, frame.height - 1)
            p += 18
        }
        System.arraycopy(data, 0, out, p, data.size)
        return out
    }

    /**
     * Key frames: frames that don't depend on the ones before, so decoding can start there
     * (libwebp's `IsKeyFrame` rule): the first frame; a full-canvas frame that is opaque or not
     * blended; a frame after one disposed to background that covered the canvas or was itself a key frame.
     */
    fun keyFrames(info: WebpInfo): BooleanArray {
        val fr = info.frames
        val keys = BooleanArray(fr.size)
        for (i in fr.indices) {
            val c = fr[i]
            keys[i] = when {
                i == 0 -> true
                (!c.hasAlpha || !c.blend) && c.covers(info.width, info.height) -> true
                else -> fr[i - 1].disposeToBackground && (fr[i - 1].covers(info.width, info.height) || keys[i - 1])
            }
        }
        return keys
    }

    /** One step of the strip pass: composite frame [index] (after clearing the canvas if [reset]); [emit] = a target. */
    data class Step(val index: Int, val reset: Boolean, val emit: Boolean)

    /**
     * The frames to composite for [targets] (ascending frame indices): from the nearest key frame
     * at or before each target, unless the canvas already holds an earlier frame after that key
     * frame, in which case decoding just continues.
     */
    fun plan(keys: BooleanArray, targets: IntArray): List<Step> {
        val out = ArrayList<Step>()
        var at = -1 // the frame the canvas shows
        for (t in targets.distinct().sorted()) {
            if (t !in keys.indices) continue
            var k = t
            while (k > 0 && !keys[k]) k--
            val resume = at in k until t
            val from = if (resume) at + 1 else k
            for (i in from..t) out.add(Step(i, reset = !resume && i == k, emit = i == t))
            at = t
        }
        return out
    }

    private fun fourcc(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)
    private fun u24(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16)
    private fun u32(b: ByteArray, o: Int) = u24(b, o).toLong() or ((b[o + 3].toLong() and 0xff) shl 24)
    private fun put4cc(b: ByteArray, o: Int, s: String) = s.toByteArray(Charsets.ISO_8859_1).copyInto(b, o)
    private fun putU24(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
        b[o + 2] = (v shr 16).toByte()
    }
    private fun putU32(b: ByteArray, o: Int, v: Long) {
        putU24(b, o, v.toInt())
        b[o + 3] = (v shr 24).toByte()
    }
}
