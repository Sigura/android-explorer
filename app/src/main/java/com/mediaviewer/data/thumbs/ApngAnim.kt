package com.mediaviewer.data.thumbs

import com.mediaviewer.data.tags.ByteSource
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** A chunk's data in the file: [offset] of the data, [length] bytes. */
class PngChunk(val type: String, val offset: Long, val length: Int)

/** One APNG frame (todo §10.10). Offsets are in canvas pixels. */
class ApngFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val delayMs: Int,
    /** 0 = leave it, 1 = clear its rectangle, 2 = put back what was there before it. */
    val dispose: Int,
    /** False = replace its rectangle (`APNG_BLEND_OP_SOURCE`); true = draw over (`OVER`). */
    val over: Boolean,
    /** Its image data: `IDAT` chunks for the default image, `fdAT` (with a 4-byte sequence number first) for the others. */
    val data: List<PngChunk>,
) {
    fun covers(w: Int, h: Int) = x == 0 && y == 0 && width == w && height == h
}

class ApngInfo(
    val width: Int,
    val height: Int,
    /** `IHDR`'s 13 bytes; frames get a copy with their own size. */
    val ihdr: ByteArray,
    /** Chunks every frame needs (`PLTE`, `tRNS`, `gAMA`, `cHRM`, `sRGB`, `iCCP`, `sBIT`), copied as they are. */
    val shared: List<PngChunk>,
    val frames: List<ApngFrame>,
) {
    val durationMs: Long get() = frames.sumOf { it.delayMs.toLong() }
}

/**
 * The drawing steps of an APNG strip, done by the caller on a full-size canvas. [ApngAnim.render]
 * decides the order; Android draws with a `Canvas`, the tests record the calls.
 */
interface FrameOps {
    /** Start over: the canvas cleared to transparent. */
    fun reset()

    /** Clear a rectangle to transparent (dispose to background). */
    fun clear(x: Int, y: Int, w: Int, h: Int)

    /** Keep a copy of the canvas, for a frame that disposes to previous. */
    fun save()

    /** Put the copy back. */
    fun restore()

    /** Decode frame [index] and draw it at its place; [replace] = blend source (its pixels replace the canvas). */
    fun draw(index: Int, frame: ApngFrame, replace: Boolean)

    /** The canvas now shows a target frame. */
    fun emit(index: Int)
}

/**
 * Animated PNG for tile strips (todo §10.10, research doc §2). The platform shows only the first
 * frame of an APNG, so the chunks are read here: a scan finds the frames without decoding; each
 * needed frame is rebuilt as a standalone PNG for the platform decoder, and [render] composites
 * them with the frames' blend and dispose rules, from the nearest key frame. Pure Kotlin.
 */
object ApngAnim {
    private const val MAX_FRAMES = 100_000
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val SHARED = setOf("PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT")

    /**
     * True when the chunks in [head] (the first few KB of a file) reach an `acTL` before the image
     * data; false for a still PNG, or when the head ends first (then [scan] decides). Encoders put
     * `acTL` right after `IHDR` or after a few small chunks (`gAMA`, `sRGB`, `pHYs`).
     */
    fun looksAnimated(head: ByteArray): Boolean {
        if (head.size < 16 || (0 until 8).any { head[it] != SIGNATURE[it] }) return false
        var pos = 8
        while (pos + 8 <= head.size) {
            val type = type(head, pos + 4)
            if (type == "acTL") return true
            if (type == "IDAT" || type == "IEND") return false
            val len = u32(head, pos)
            if (len > head.size) return false
            pos += 12 + len.toInt()
        }
        return false
    }

    /**
     * The frames of an APNG; null when the file is a still PNG (no `acTL` before the image data),
     * not a PNG, or an animation without one complete frame.
     */
    fun scan(src: ByteSource): ApngInfo? {
        val sig = ByteArray(8)
        if (src.read(0, sig) < 8 || !sig.contentEquals(SIGNATURE)) return null
        var pos = 8L
        var ihdr: ByteArray? = null
        var animated = false
        val shared = ArrayList<PngChunk>()
        val frames = ArrayList<ApngFrame>()
        var control: ByteArray? = null // the fcTL waiting for its data
        var data = ArrayList<PngChunk>()
        fun close() {
            val c = control ?: return
            if (data.isNotEmpty()) frame(c, data)?.let { frames.add(it) }
            control = null
            data = ArrayList()
        }
        val h = ByteArray(8)
        while (pos + 12 <= src.size && frames.size < MAX_FRAMES) {
            if (src.read(pos, h) < 8) break
            val len = u32(h, 0)
            val type = type(h, 4)
            val dataAt = pos + 8
            if (len < 0 || dataAt + len + 4 > src.size) break // truncated: keep the complete frames
            when (type) {
                "IHDR" -> ihdr = read(src, dataAt, 13)
                "acTL" -> animated = true
                "fcTL" -> {
                    close()
                    control = read(src, dataAt, 26)
                }
                "IDAT" -> {
                    // No acTL before the image data: a still PNG; stop reading at once.
                    if (!animated) return null
                    if (control != null) data.add(PngChunk(type, dataAt, len.toInt()))
                }
                "fdAT" -> if (control != null && len > 4) data.add(PngChunk(type, dataAt, len.toInt()))
                "IEND" -> break
                else -> if (type in SHARED && frames.isEmpty() && control == null) shared.add(PngChunk(type, dataAt, len.toInt()))
            }
            pos = dataAt + len + 4
        }
        close()
        val head = ihdr ?: return null
        if (!animated || frames.isEmpty()) return null
        val w = u32(head, 0).toInt()
        val hgt = u32(head, 4).toInt()
        if (w <= 0 || hgt <= 0) return null
        // A frame outside the canvas is broken; drop it and the ones after it.
        val valid = frames.takeWhile { it.x + it.width <= w && it.y + it.height <= hgt }
        return if (valid.isEmpty()) null else ApngInfo(w, hgt, head, shared, valid)
    }

    private fun frame(c: ByteArray, data: List<PngChunk>): ApngFrame? {
        if (c.size < 26) return null
        val fw = u32(c, 4).toInt()
        val fh = u32(c, 8).toInt()
        if (fw <= 0 || fh <= 0) return null
        val num = u16(c, 20)
        val den = u16(c, 22).let { if (it == 0) 100 else it } // 0 means 1/100 s
        return ApngFrame(
            x = u32(c, 12).toInt(), y = u32(c, 16).toInt(), width = fw, height = fh,
            delayMs = (num * 1000L / den).toInt(),
            dispose = (c[24].toInt() and 0xFF).coerceAtMost(2),
            over = c[25].toInt() == 1,
            data = data,
        )
    }

    /** Frame [frame] as a still PNG: `IHDR` with the frame's size, the shared chunks, its data as `IDAT`, `IEND`. */
    fun standalone(src: ByteSource, info: ApngInfo, frame: ApngFrame): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = info.ihdr.copyOf()
        putU32(ihdr, 0, frame.width.toLong())
        putU32(ihdr, 4, frame.height.toLong())
        chunk(out, "IHDR", ihdr)
        for (c in info.shared) chunk(out, c.type, read(src, c.offset, c.length) ?: throw java.io.IOException("short ${c.type}"))
        for (d in frame.data) {
            val bytes = read(src, d.offset, d.length) ?: throw java.io.IOException("short ${d.type}")
            // fdAT = a sequence number, then the same data as an IDAT.
            chunk(out, "IDAT", if (d.type == "fdAT") bytes.copyOfRange(4, bytes.size) else bytes)
        }
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * Key frames, where decoding can start on a cleared canvas: the first frame; a full-canvas
     * frame that replaces (blend source); a frame after one that covered the canvas and was
     * cleared (dispose to background), or after a key frame that was.
     */
    fun keyFrames(info: ApngInfo): BooleanArray {
        val fr = info.frames
        val keys = BooleanArray(fr.size)
        for (i in fr.indices) {
            val c = fr[i]
            keys[i] = when {
                i == 0 -> true
                !c.over && c.covers(info.width, info.height) -> true
                else -> fr[i - 1].dispose == 1 && (fr[i - 1].covers(info.width, info.height) || keys[i - 1])
            }
        }
        return keys
    }

    /**
     * Composites the frames [targets] needs, through [ops]. Disposal follows the PNG rules: after a
     * frame is shown, *background* clears its rectangle and *previous* puts back the canvas as it
     * was before it; the first frame's *previous* counts as *background*.
     */
    fun render(info: ApngInfo, targets: IntArray, ops: FrameOps) {
        var prev: Int = -1
        var saved = false
        for (step in WebpAnim.plan(keyFrames(info), targets)) {
            val f = info.frames[step.index]
            if (step.reset) {
                ops.reset()
                saved = false
            } else if (prev >= 0) {
                val p = info.frames[prev]
                val dispose = if (prev == 0 && p.dispose == 2) 1 else p.dispose
                when {
                    dispose == 1 -> ops.clear(p.x, p.y, p.width, p.height)
                    dispose == 2 && saved -> ops.restore()
                }
            }
            if (f.dispose == 2 && step.index > 0) {
                ops.save()
                saved = true
            } else {
                saved = false
            }
            // The first frame, or one drawn on a cleared canvas, has nothing to blend with.
            ops.draw(step.index, f, replace = !f.over || step.index == 0)
            if (step.emit) ops.emit(step.index)
            prev = step.index
        }
    }

    // ------------------------------------------------------------------ bytes

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val head = ByteArray(8)
        putU32(head, 0, data.size.toLong())
        type.toByteArray(Charsets.ISO_8859_1).copyInto(head, 4)
        out.write(head)
        out.write(data)
        val crc = CRC32().apply {
            update(head, 4, 4)
            update(data)
        }.value
        val c = ByteArray(4)
        putU32(c, 0, crc)
        out.write(c)
    }

    private fun read(src: ByteSource, pos: Long, len: Int): ByteArray? {
        if (len < 0 || len > 64 * 1024 * 1024 || pos + len > src.size) return null
        val b = ByteArray(len)
        return if (src.read(pos, b) == len) b else null
    }

    private fun type(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()
    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }
}
