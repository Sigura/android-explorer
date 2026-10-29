package com.mediaviewer.data.tags

import com.mediaviewer.domain.Chapter
import com.mediaviewer.domain.TimedLine
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/** Random access to a file's bytes, so the readers run on byte arrays in tests. */
interface ByteSource {
    val size: Long

    /** Reads up to [len] bytes at [pos]; fewer only at the end. */
    fun read(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size): Int
}

class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= bytes.size || pos < 0) return 0
        val n = minOf(len.toLong(), bytes.size - pos).toInt()
        System.arraycopy(bytes, pos.toInt(), buf, off, n)
        return n
    }
}

/**
 * A file through one 64 KB window: walking Matroska clusters reads thousands of 12-byte headers,
 * mostly close together; larger reads go to the file directly.
 */
class FileSource(file: File) : ByteSource, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()
    private val window = ByteArray(WINDOW)
    private var windowPos = -1L
    private var windowLen = 0

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size || pos < 0) return 0
        if (len > WINDOW / 4) return readDirect(pos, buf, off, len)
        if (windowPos < 0 || pos < windowPos || pos + len > windowPos + windowLen) {
            windowLen = readDirect(pos, window, 0, minOf(WINDOW.toLong(), size - pos).toInt())
            windowPos = pos
        }
        val n = minOf(len.toLong(), windowPos + windowLen - pos).toInt().coerceAtLeast(0)
        System.arraycopy(window, (pos - windowPos).toInt(), buf, off, n)
        return n
    }

    private fun readDirect(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        raf.seek(pos)
        var done = 0
        while (done < len) {
            val n = raf.read(buf, off + done, len - done)
            if (n <= 0) break
            done += n
        }
        return done
    }

    override fun close() = raf.close()

    private companion object {
        const val WINDOW = 64 * 1024
    }
}

/**
 * Chapters and lyrics inside audio and video files (todo §4.4, §4.5), read without a decoder:
 * MP4 / M4B (`chpl` and the QuickTime chapter text track, `©lyr`), MP3 (ID3v2.3 / 2.4 `CHAP`,
 * `SYLT`, `USLT`), Matroska / WebM (`Chapters`), FLAC and Ogg Vorbis / Opus (comments: `LYRICS`,
 * `UNSYNCEDLYRICS`, `CHAPTERxxx` + `CHAPTERxxxNAME`). Synced `SYLT` lyrics come back as LRC text. Every length read from the file is checked against the
 * file size and a cap before anything is allocated; malformed input gives an empty result.
 * Blocking: call from `Dispatchers.IO`. Pure Kotlin (JVM).
 */
object MediaTags {
    /** No tag or chapter list we read is larger; a bigger length means a broken file. */
    private const val MAX_BLOCK = 8 * 1024 * 1024
    private const val MAX_CHAPTERS = 5_000
    /** Samples in one MP4 text track (chapters or subtitles), and subtitle lines from Matroska. */
    private const val MAX_SAMPLES = 50_000

    fun chapters(file: File): List<Chapter> = try {
        FileSource(file).use { chapters(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun lyrics(file: File): String? = try {
        FileSource(file).use { lyrics(it) }
    } catch (e: Exception) {
        null
    }

    fun chapters(src: ByteSource): List<Chapter> = try {
        when (kind(src)) {
            Kind.ID3 -> Id3.read(src).chapters
            Kind.MP4 -> Mp4.chapters(src)
            Kind.MATROSKA -> Ebml.chapters(src)
            Kind.FLAC, Kind.OGG -> VorbisComments.chapters(VorbisComments.read(src, kind(src) == Kind.FLAC))
            null -> emptyList()
        }.take(MAX_CHAPTERS)
    } catch (e: Exception) {
        emptyList()
    }

    fun lyrics(src: ByteSource): String? = try {
        when (kind(src)) {
            Kind.ID3 -> Id3.read(src).lyrics
            Kind.MP4 -> Mp4.lyrics(src)
            Kind.FLAC, Kind.OGG -> VorbisComments.lyrics(VorbisComments.read(src, kind(src) == Kind.FLAC))
            else -> null
        }?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * The subtitle track inside a video (todo §4.6): Matroska / WebM `S_TEXT/UTF8`, `S_TEXT/ASS`,
     * `S_TEXT/SSA`, `S_TEXT/WEBVTT`, or an MP4 `tx3g` track. [language] (ISO 639, `en` or `eng`)
     * picks among several; then the default track, then the first. Empty when there is none.
     */
    fun subtitles(file: File, language: String = ""): List<TimedLine> = try {
        FileSource(file).use { subtitles(it, language) }
    } catch (e: Exception) {
        emptyList()
    }

    fun subtitles(src: ByteSource, language: String = ""): List<TimedLine> = try {
        when (kind(src)) {
            Kind.MATROSKA -> Ebml.subtitles(src, language)
            Kind.MP4 -> Mp4.subtitles(src, language)
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** `en` matches `eng` and `en-US`; three-letter codes match their two-letter forms for the common languages. */
    internal fun languageMatches(track: String, wanted: String): Boolean {
        if (wanted.isEmpty() || track.isEmpty()) return false
        fun norm(l: String) = l.lowercase().substringBefore('-').substringBefore('_').let { THREE_TO_TWO[it] ?: it }
        return norm(track) == norm(wanted)
    }

    private val THREE_TO_TWO = mapOf(
        "eng" to "en", "rus" to "ru", "ukr" to "uk", "deu" to "de", "ger" to "de", "fra" to "fr", "fre" to "fr",
        "spa" to "es", "ita" to "it", "por" to "pt", "pol" to "pl", "nld" to "nl", "dut" to "nl", "jpn" to "ja",
        "zho" to "zh", "chi" to "zh", "kor" to "ko", "tur" to "tr", "ces" to "cs", "cze" to "cs", "swe" to "sv",
    )

    private enum class Kind { ID3, MP4, MATROSKA, FLAC, OGG }

    private fun kind(src: ByteSource): Kind? {
        val h = ByteArray(12)
        if (src.read(0, h) < 8) return null
        return when {
            h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte() -> Kind.ID3
            h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() && h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte() -> Kind.MP4
            u32(h, 0) == 0x1A45DFA3L -> Kind.MATROSKA
            String(h, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> Kind.FLAC
            String(h, 0, 4, Charsets.ISO_8859_1) == "OggS" -> Kind.OGG
            else -> null
        }
    }

    // ------------------------------------------------------------------ helpers

    internal fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    internal fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
    internal fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    /** Reads exactly [len] bytes at [pos], or null when the file is shorter or [len] is too large. */
    internal fun bytes(src: ByteSource, pos: Long, len: Long): ByteArray? {
        if (len < 0 || len > MAX_BLOCK || pos < 0 || pos + len > src.size) return null
        val b = ByteArray(len.toInt())
        return if (src.read(pos, b) == b.size) b else null
    }

    // ------------------------------------------------------------------ ID3v2 (MP3)

    internal class Id3Result(val chapters: List<Chapter>, val lyrics: String?)

    internal object Id3 {
        fun read(src: ByteSource): Id3Result {
            val h = bytes(src, 0, 10) ?: return Id3Result(emptyList(), null)
            val major = h[3].toInt()
            if (major != 3 && major != 4) return Id3Result(emptyList(), null) // v2.2 has 3-letter frames: rare, skipped
            val flags = h[5].toInt()
            val size = syncsafe(h, 6)
            var tag = bytes(src, 10, size.toLong()) ?: return Id3Result(emptyList(), null)
            if (major == 3 && flags and 0x80 != 0) tag = unsync(tag)
            var pos = 0
            if (flags and 0x40 != 0 && tag.size >= 4) {
                // Extended header: v2.3 size excludes its own 4 bytes, v2.4 includes them (syncsafe).
                pos = if (major == 3) 4 + u32(tag, 0).toInt() else syncsafe(tag, 0)
            }
            val chapters = ArrayList<Chapter>()
            var lyrics: String? = null
            var synced: String? = null
            forEachFrame(tag, pos, tag.size, major) { id, body ->
                when (id) {
                    "CHAP" -> chap(body, major)?.let { chapters.add(it) }
                    "USLT" -> if (lyrics == null) lyrics = uslt(body)
                    "SYLT" -> if (synced == null) synced = sylt(body)
                }
            }
            // Synced lyrics win over plain ones.
            return Id3Result(chapters.sortedBy { it.startMs }, synced ?: lyrics)
        }

        private inline fun forEachFrame(tag: ByteArray, from: Int, to: Int, major: Int, onFrame: (String, ByteArray) -> Unit) {
            var pos = from
            while (pos + 10 <= to) {
                if (tag[pos].toInt() == 0) break // padding
                val id = String(tag, pos, 4, Charsets.ISO_8859_1)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
                // iTunes wrote v2.4 frame sizes as plain integers; a byte with the top bit set gives that away.
                val plain = u32(tag, pos + 4)
                val size = if (major == 4 && (0..3).none { tag[pos + 4 + it].toInt() and 0x80 != 0 }) syncsafe(tag, pos + 4).toLong() else plain
                val fflags = u16(tag, pos + 8)
                val start = pos + 10
                if (size < 0 || start + size > to) break
                var body = tag.copyOfRange(start, start + size.toInt())
                var skip = false
                if (major == 4) {
                    if (fflags and 0x000C != 0) skip = true // compressed or encrypted
                    if (fflags and 0x0040 != 0 && body.isNotEmpty()) body = body.copyOfRange(1, body.size) // group id
                    if (fflags and 0x0001 != 0 && body.size >= 4) body = body.copyOfRange(4, body.size) // data length
                    if (fflags and 0x0002 != 0) body = unsync(body)
                } else {
                    if (fflags and 0x00C0 != 0) skip = true // compressed or encrypted
                    if (fflags and 0x0020 != 0 && body.isNotEmpty()) body = body.copyOfRange(1, body.size) // group id
                }
                if (!skip) onFrame(id, body)
                pos = start + size.toInt()
            }
        }

        /** `CHAP`: element id, start / end ms, byte offsets, then sub-frames (`TIT2` = the title). */
        private fun chap(b: ByteArray, major: Int): Chapter? {
            val idEnd = b.indexOf(0)
            if (idEnd < 0 || idEnd + 17 > b.size) return null
            val start = u32(b, idEnd + 1)
            var title: String? = null
            forEachFrame(b, idEnd + 17, b.size, major) { id, body ->
                if (id == "TIT2" && title == null) title = text(body)
            }
            return Chapter(start, title?.takeIf { it.isNotBlank() } ?: "")
        }

        /** `USLT`: encoding, language, a description, then the text. */
        private fun uslt(b: ByteArray): String? {
            if (b.size < 5) return null
            val enc = b[0].toInt()
            val descEnd = terminator(b, 4, enc)
            if (descEnd < 0) return null
            val textStart = descEnd + if (enc == 1 || enc == 2) 2 else 1
            return decode(b, textStart, b.size, enc)
        }

        /**
         * `SYLT`: encoding, language, stamp format (1 = MPEG frames, 2 = ms), content type, a
         * description, then text + 4-byte stamp pairs. Returned as LRC (`[mm:ss.xx]line`), so the
         * player highlights it like an `.lrc`. MPEG-frame stamps assume 1152 samples at 44.1 kHz.
         */
        private fun sylt(b: ByteArray): String? {
            if (b.size < 7) return null
            val enc = b[0].toInt()
            val format = b[4].toInt()
            if (format != 1 && format != 2) return null
            val step = if (enc == 1 || enc == 2) 2 else 1
            var p = terminator(b, 6, enc).takeIf { it >= 0 }?.plus(step) ?: return null
            val out = StringBuilder()
            var lines = 0
            while (p < b.size && lines < 20_000) {
                val end = terminator(b, p, enc)
                if (end < 0 || end + step + 4 > b.size) break
                // UTF-16 strings after the first may start with their own BOM.
                val text = decode(b, p, end, enc)?.removePrefix("\uFEFF")?.trim('\n', '\r', ' ') ?: ""
                val stamp = u32(b, end + step)
                val ms = if (format == 2) stamp else stamp * 1152 * 1000 / 44_100
                out.append('[').append(lrcTime(ms)).append(']').append(text).append('\n')
                lines++
                p = end + step + 4
            }
            return if (lines >= 2) out.toString() else null
        }

        private fun lrcTime(ms: Long): String = "%02d:%02d.%02d".format(java.util.Locale.ROOT, ms / 60_000, ms / 1000 % 60, ms / 10 % 100)

        private fun text(b: ByteArray): String? {
            if (b.isEmpty()) return null
            val enc = b[0].toInt()
            val end = terminator(b, 1, enc).let { if (it < 0) b.size else it }
            return decode(b, 1, end, enc)
        }

        /** Index of the string terminator from [from] (one zero byte, or two aligned for UTF-16), or -1. */
        private fun terminator(b: ByteArray, from: Int, enc: Int): Int {
            if (enc == 1 || enc == 2) {
                var i = from
                while (i + 1 < b.size) {
                    if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i
                    i += 2
                }
                return -1
            }
            for (i in from until b.size) if (b[i].toInt() == 0) return i
            return -1
        }

        private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String? {
            if (from > to || from < 0 || to > b.size) return null
            val cs: Charset = when (enc) {
                0 -> Charsets.ISO_8859_1
                1 -> Charsets.UTF_16 // with a BOM
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> return null
            }
            return String(b, from, to - from, cs).trimEnd('\u0000').removePrefix("﻿")
        }

        fun syncsafe(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
                ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

        /** Undoes unsynchronisation: `FF 00` → `FF`. */
        fun unsync(b: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(b.size)
            var i = 0
            while (i < b.size) {
                out.write(b[i].toInt())
                i += if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) 2 else 1
            }
            return out.toByteArray()
        }
    }

    // ------------------------------------------------------------------ FLAC / Ogg comments

    internal object VorbisComments {
        /** Comment keys (upper case) to their values, in file order; empty when there are none. */
        fun read(src: ByteSource, flac: Boolean): List<Pair<String, String>> {
            val block = (if (flac) flacBlock(src) else oggCommentPacket(src)) ?: return emptyList()
            return parse(block)
        }

        /** FLAC: metadata blocks after `fLaC`; type 4 is the Vorbis comment block. */
        private fun flacBlock(src: ByteSource): ByteArray? {
            var pos = 4L
            repeat(128) {
                val h = bytes(src, pos, 4) ?: return null
                val type = h[0].toInt() and 0x7F
                val len = (u32(h, 0) and 0xFFFFFF)
                if (type == 4) return bytes(src, pos + 4, len)
                if (h[0].toInt() and 0x80 != 0) return null // the last block
                pos += 4 + len
            }
            return null
        }

        /**
         * Ogg: the second packet of the first stream (Vorbis `\u0003vorbis…`, Opus `OpusTags…`),
         * put together from the page segments; the comment structure follows the signature.
         */
        private fun oggCommentPacket(src: ByteSource): ByteArray? {
            var pos = 0L
            var serial = -1L
            var packet = java.io.ByteArrayOutputStream()
            var packets = 0
            repeat(512) {
                val h = bytes(src, pos, 27) ?: return null
                if (String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") return null
                val pageSerial = u32le(h, 14)
                val segs = h[26].toInt() and 0xFF
                val table = bytes(src, pos + 27, segs.toLong()) ?: return null
                var dataPos = pos + 27 + segs
                val mine = serial < 0 || pageSerial == serial
                if (serial < 0) serial = pageSerial
                for (i in 0 until segs) {
                    val len = table[i].toInt() and 0xFF
                    if (mine) {
                        if (packets == 1) {
                            if (packet.size() + len > MAX_BLOCK) return null
                            packet.write(bytes(src, dataPos, len.toLong()) ?: return null)
                        }
                        if (len < 255) {
                            if (packets == 1) {
                                val p = packet.toByteArray()
                                return when {
                                    p.size >= 7 && p[0].toInt() == 3 && String(p, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> p.copyOfRange(7, p.size)
                                    p.size >= 8 && String(p, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> p.copyOfRange(8, p.size)
                                    else -> null
                                }
                            }
                            packets++
                            packet = java.io.ByteArrayOutputStream()
                        }
                    }
                    dataPos += len
                }
                pos = dataPos
            }
            return null
        }

        /** Vendor string, then `KEY=value` entries; all lengths little-endian. */
        private fun parse(b: ByteArray): List<Pair<String, String>> {
            if (b.size < 8) return emptyList()
            var p = 4 + u32le(b, 0).toInt()
            if (p < 4 || p + 4 > b.size) return emptyList()
            val count = u32le(b, p)
            p += 4
            val out = ArrayList<Pair<String, String>>()
            var i = 0L
            while (i < count && p + 4 <= b.size && out.size < 10_000) {
                val len = u32le(b, p)
                p += 4
                if (len < 0 || p + len > b.size) break
                val entry = String(b, p, len.toInt(), Charsets.UTF_8)
                p += len.toInt()
                val eq = entry.indexOf('=')
                if (eq > 0) out.add(entry.substring(0, eq).uppercase() to entry.substring(eq + 1))
                i++
            }
            return out
        }

        fun lyrics(comments: List<Pair<String, String>>): String? =
            comments.firstOrNull { it.first == "LYRICS" }?.second ?: comments.firstOrNull { it.first == "UNSYNCEDLYRICS" }?.second

        private val CHAPTER = Regex("^CHAPTER(\\d{1,4})$")

        /** `CHAPTER001=00:01:02.500` + `CHAPTER001NAME=Title`. */
        fun chapters(comments: List<Pair<String, String>>): List<Chapter> {
            val names = comments.filter { it.first.startsWith("CHAPTER") && it.first.endsWith("NAME") }.associate { it.first.removeSuffix("NAME") to it.second }
            return comments.mapNotNull { (k, v) ->
                if (!CHAPTER.matches(k)) return@mapNotNull null
                val ms = time(v.trim()) ?: return@mapNotNull null
                Chapter(ms, names[k] ?: "")
            }.sortedBy { it.startMs }
        }

        /** `HH:MM:SS(.mmm)` or `MM:SS(.mmm)`. */
        private fun time(s: String): Long? {
            val parts = s.split(':')
            if (parts.size !in 2..3) return null
            val sec = parts.last().toDoubleOrNull() ?: return null
            val min = parts[parts.size - 2].toLongOrNull() ?: return null
            val h = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
            return (h * 3600 + min * 60) * 1000 + (sec * 1000).toLong()
        }

        private fun u32le(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    }

    // ------------------------------------------------------------------ MP4 / M4B

    internal class Box(val type: String, val start: Long, val dataStart: Long, val end: Long)

    internal object Mp4 {
        /** Boxes inside [from, to). Stops at the first broken header. */
        fun children(src: ByteSource, from: Long, to: Long): List<Box> {
            val out = ArrayList<Box>()
            var pos = from
            val h = ByteArray(16)
            while (pos + 8 <= to && out.size < 10_000) {
                if (src.read(pos, h, 0, 16) < 8) break
                var size = u32(h, 0)
                val type = String(h, 4, 4, Charsets.ISO_8859_1)
                var header = 8L
                when (size) {
                    1L -> {
                        size = u64(h, 8)
                        header = 16
                    }
                    0L -> size = to - pos
                }
                if (size < header || pos + size > to) break
                out.add(Box(type, pos, pos + header, pos + size))
                pos += size
            }
            return out
        }

        private fun child(src: ByteSource, parent: Box, type: String): Box? = children(src, parent.dataStart, parent.end).firstOrNull { it.type == type }

        private fun path(src: ByteSource, parent: Box, vararg types: String): Box? {
            var b = parent
            for (t in types) b = child(src, b, t) ?: return null
            return b
        }

        private fun moov(src: ByteSource): Box? = children(src, 0, src.size).firstOrNull { it.type == "moov" }

        fun chapters(src: ByteSource): List<Chapter> {
            val moov = moov(src) ?: return emptyList()
            // The QuickTime chapter track (iTunes, most audiobook tools) has the better titles; Nero's `chpl` is the fallback.
            val track = textTrackChapters(src, moov)
            if (track.size >= 2) return track
            return chpl(src, moov)
        }

        /** Nero `moov/udta/chpl`: version, flags, (v1: 4 reserved), count, then 100 ns start + Pascal title. */
        private fun chpl(src: ByteSource, moov: Box): List<Chapter> {
            val box = path(src, moov, "udta", "chpl") ?: return emptyList()
            val b = bytes(src, box.dataStart, box.end - box.dataStart) ?: return emptyList()
            if (b.size < 5) return emptyList()
            var p = 4
            if (b[0].toInt() != 0) p += 4
            if (p >= b.size) return emptyList()
            val count = b[p].toInt() and 0xFF
            p++
            val out = ArrayList<Chapter>()
            repeat(count) {
                if (p + 9 > b.size) return out
                val start = u64(b, p) / 10_000
                val len = b[p + 8].toInt() and 0xFF
                p += 9
                if (p + len > b.size) return out
                out.add(Chapter(start, String(b, p, len, Charsets.UTF_8)))
                p += len
            }
            return out
        }

        /** The text track that another track points at through `tref/chap`. */
        private fun textTrackChapters(src: ByteSource, moov: Box): List<Chapter> {
            val traks = children(src, moov.dataStart, moov.end).filter { it.type == "trak" }
            val ids = HashSet<Long>()
            for (t in traks) {
                val chap = path(src, t, "tref", "chap") ?: continue
                val b = bytes(src, chap.dataStart, chap.end - chap.dataStart) ?: continue
                for (i in 0 until b.size / 4) ids.add(u32(b, i * 4))
            }
            if (ids.isEmpty()) return emptyList()
            val track = traks.firstOrNull { trackId(src, it) in ids } ?: return emptyList()
            return textSamples(src, track, MAX_CHAPTERS).map { Chapter(it.startMs, it.text) }
        }

        /** Track ids that `tref/chap` points at: chapter tracks, never subtitles. */
        private fun chapterTrackIds(src: ByteSource, traks: List<Box>): Set<Long> {
            val ids = HashSet<Long>()
            for (t in traks) {
                val chap = path(src, t, "tref", "chap") ?: continue
                val b = bytes(src, chap.dataStart, chap.end - chap.dataStart) ?: continue
                for (i in 0 until b.size / 4) ids.add(u32(b, i * 4))
            }
            return ids
        }

        /** Every sample of a text track as a line: start, end (start + duration), text; up to [max]. */
        private fun textSamples(src: ByteSource, track: Box, max: Int): List<TimedLine> {
            val mdia = child(src, track, "mdia") ?: return emptyList()
            val timescale = timescale(src, mdia) ?: return emptyList()
            val stbl = path(src, mdia, "minf", "stbl") ?: return emptyList()
            val durations = stts(src, stbl) ?: return emptyList()
            val sizes = stsz(src, stbl) ?: return emptyList()
            val offsets = sampleOffsets(src, stbl, sizes.size) ?: return emptyList()
            val n = minOf(durations.size, sizes.size, offsets.size, max)
            val out = ArrayList<TimedLine>(n)
            var t = 0L
            for (i in 0 until n) {
                val start = t * 1000 / timescale
                t += durations[i]
                out.add(TimedLine(start, t * 1000 / timescale, sampleText(src, offsets[i], sizes[i])))
            }
            return out
        }

        /** The 4-character handler of a track (`soun`, `vide`, `text`, `sbtl`, `subt`) and its first sample entry type (`tx3g`, `mp4a`…). */
        private fun handlerAndEntry(src: ByteSource, trak: Box): Pair<String, String>? {
            val mdia = child(src, trak, "mdia") ?: return null
            val hdlr = child(src, mdia, "hdlr") ?: return null
            val h = bytes(src, hdlr.dataStart, minOf(12, hdlr.end - hdlr.dataStart)) ?: return null
            if (h.size < 12) return null
            val handler = String(h, 8, 4, Charsets.ISO_8859_1)
            val stsd = path(src, mdia, "minf", "stbl", "stsd") ?: return handler to ""
            val e = bytes(src, stsd.dataStart, minOf(16, stsd.end - stsd.dataStart)) ?: return handler to ""
            return handler to if (e.size >= 16) String(e, 12, 4, Charsets.ISO_8859_1) else ""
        }

        private fun language(src: ByteSource, trak: Box): String {
            val mdhd = child(src, child(src, trak, "mdia") ?: return "", "mdhd") ?: return ""
            val b = bytes(src, mdhd.dataStart, minOf(32, mdhd.end - mdhd.dataStart)) ?: return ""
            // Packed ISO 639-2: three 5-bit letters after the times (v0: offset 20, v1: offset 32 — read the first 32 only for v0).
            val at = if (b[0].toInt() == 1) return "" else 20
            if (b.size < at + 2) return ""
            val v = u16(b, at)
            return String(charArrayOf(('`'.code + (v shr 10 and 31)).toChar(), ('`'.code + (v shr 5 and 31)).toChar(), ('`'.code + (v and 31)).toChar()))
        }

        /** A `tx3g` subtitle track (not a chapter track); empty samples are the gaps between lines. */
        fun subtitles(src: ByteSource, wanted: String): List<TimedLine> {
            val moov = moov(src) ?: return emptyList()
            val traks = children(src, moov.dataStart, moov.end).filter { it.type == "trak" }
            val chapterIds = chapterTrackIds(src, traks)
            val subs = traks.filter { t ->
                trackId(src, t) !in chapterIds && handlerAndEntry(src, t)?.let { (h, e) -> e == "tx3g" && (h == "sbtl" || h == "subt" || h == "text") } == true
            }
            val track = subs.firstOrNull { languageMatches(language(src, it), wanted) } ?: subs.firstOrNull() ?: return emptyList()
            return textSamples(src, track, MAX_SAMPLES).filter { it.text.isNotBlank() }
        }

        private fun trackId(src: ByteSource, trak: Box): Long? {
            val tkhd = child(src, trak, "tkhd") ?: return null
            val b = bytes(src, tkhd.dataStart, minOf(32, tkhd.end - tkhd.dataStart)) ?: return null
            val at = if (b[0].toInt() == 1) 20 else 12
            return if (b.size >= at + 4) u32(b, at) else null
        }

        private fun timescale(src: ByteSource, mdia: Box): Long? {
            val mdhd = child(src, mdia, "mdhd") ?: return null
            val b = bytes(src, mdhd.dataStart, minOf(32, mdhd.end - mdhd.dataStart)) ?: return null
            val at = if (b[0].toInt() == 1) 20 else 12
            return if (b.size >= at + 4) u32(b, at).takeIf { it > 0 } else null
        }

        private fun table(src: ByteSource, stbl: Box, type: String): ByteArray? {
            val box = child(src, stbl, type) ?: return null
            return bytes(src, box.dataStart, box.end - box.dataStart)
        }

        /** Sample durations in track time units. */
        private fun stts(src: ByteSource, stbl: Box): LongArray? {
            val b = table(src, stbl, "stts") ?: return null
            val entries = u32(b, 4).toInt()
            if (entries < 0 || 8 + entries.toLong() * 8 > b.size) return null
            val out = ArrayList<Long>()
            for (i in 0 until entries) {
                val count = u32(b, 8 + i * 8)
                val delta = u32(b, 12 + i * 8)
                if (count > MAX_SAMPLES || out.size + count > MAX_SAMPLES) return null
                repeat(count.toInt()) { out.add(delta) }
            }
            return out.toLongArray()
        }

        private fun stsz(src: ByteSource, stbl: Box): IntArray? {
            val b = table(src, stbl, "stsz") ?: return null
            val fixed = u32(b, 4)
            val count = u32(b, 8)
            if (count > MAX_SAMPLES) return null
            if (fixed != 0L) return IntArray(count.toInt()) { fixed.toInt() }
            if (12 + count * 4 > b.size) return null
            return IntArray(count.toInt()) { u32(b, 12 + it * 4).toInt() }
        }

        /** File offset of every sample, from the chunk offsets and the samples-per-chunk runs. */
        private fun sampleOffsets(src: ByteSource, stbl: Box, samples: Int): LongArray? {
            val sizes = stsz(src, stbl) ?: return null
            val chunks: LongArray = table(src, stbl, "stco")?.let { b ->
                val n = u32(b, 4)
                if (n > MAX_SAMPLES || 8 + n * 4 > b.size) return null
                LongArray(n.toInt()) { u32(b, 8 + it * 4) }
            } ?: table(src, stbl, "co64")?.let { b ->
                val n = u32(b, 4)
                if (n > MAX_SAMPLES || 8 + n * 8 > b.size) return null
                LongArray(n.toInt()) { u64(b, 8 + it * 8) }
            } ?: return null
            val stsc = table(src, stbl, "stsc") ?: return null
            val runs = u32(stsc, 4).toInt()
            if (runs <= 0 || 8 + runs.toLong() * 12 > stsc.size) return null
            val out = LongArray(samples)
            var sample = 0
            for (r in 0 until runs) {
                val firstChunk = u32(stsc, 8 + r * 12).toInt() - 1
                val perChunk = u32(stsc, 12 + r * 12).toInt()
                val nextFirst = if (r + 1 < runs) u32(stsc, 8 + (r + 1) * 12).toInt() - 1 else chunks.size
                if (perChunk <= 0 || firstChunk < 0) return null
                for (c in firstChunk until minOf(nextFirst, chunks.size)) {
                    var off = chunks[c]
                    repeat(perChunk) {
                        if (sample >= samples) return out
                        out[sample] = off
                        off += sizes[sample]
                        sample++
                    }
                }
            }
            return if (sample == samples) out else out.copyOf(sample)
        }

        /** A text sample: a 16-bit length, then UTF-8 (or UTF-16 with a BOM). */
        private fun sampleText(src: ByteSource, offset: Long, size: Int): String {
            val b = bytes(src, offset, minOf(size, 1024).toLong()) ?: return ""
            if (b.size < 2) return ""
            val len = minOf(u16(b, 0), b.size - 2)
            if (len >= 2 && b[2] == 0xFE.toByte() && b[3] == 0xFF.toByte()) return String(b, 2, len, Charsets.UTF_16)
            return String(b, 2, len, Charsets.UTF_8)
        }

        /** iTunes lyrics: `moov/udta/meta/ilst/©lyr/data`. */
        fun lyrics(src: ByteSource): String? {
            val moov = moov(src) ?: return null
            val meta = path(src, moov, "udta", "meta") ?: return null
            // `meta` is a full box: 4 bytes of version and flags before its children.
            val ilst = children(src, meta.dataStart + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return null
            val lyr = children(src, ilst.dataStart, ilst.end).firstOrNull { it.type == "©lyr" } ?: return null
            val data = child(src, lyr, "data") ?: return null
            val b = bytes(src, data.dataStart, data.end - data.dataStart) ?: return null
            if (b.size < 8) return null
            return String(b, 8, b.size - 8, Charsets.UTF_8)
        }
    }

    // ------------------------------------------------------------------ Matroska / WebM

    internal object Ebml {
        private const val SEGMENT = 0x18538067L
        private const val SEEK_HEAD = 0x114D9B74L
        private const val SEEK = 0x4DBBL
        private const val SEEK_ID = 0x53ABL
        private const val SEEK_POSITION = 0x53ACL
        private const val CHAPTERS = 0x1043A770L
        private const val CLUSTER = 0x1F43B675L
        private const val EDITION = 0x45B9L
        private const val EDITION_DEFAULT = 0x45DBL
        private const val EDITION_HIDDEN = 0x45BDL
        private const val ATOM = 0xB6L
        private const val TIME_START = 0x91L
        private const val HIDDEN = 0x98L
        private const val ENABLED = 0x4598L
        private const val DISPLAY = 0x80L
        private const val STRING = 0x85L

        /** An element: [id], its data at [dataStart] until [end] (the parent's end when the size is unknown). */
        class El(val id: Long, val dataStart: Long, val end: Long)

        /** The element at [pos] inside a parent ending at [limit], or null. */
        fun element(src: ByteSource, pos: Long, limit: Long): El? {
            val h = ByteArray(12)
            val n = src.read(pos, h, 0, 12)
            if (n < 2) return null
            val idLen = vintLength(h[0])
            if (idLen !in 1..4 || idLen >= n) return null
            var id = 0L
            for (i in 0 until idLen) id = (id shl 8) or (h[i].toLong() and 0xFF)
            val sizeLen = vintLength(h[idLen])
            if (sizeLen !in 1..8 || idLen + sizeLen > n) return null
            var size = (h[idLen].toLong() and 0xFF) and (0xFF shr sizeLen).toLong()
            var allOnes = size == (0xFF shr sizeLen).toLong()
            for (i in 1 until sizeLen) {
                val v = h[idLen + i].toLong() and 0xFF
                if (v != 0xFFL) allOnes = false
                size = (size shl 8) or v
            }
            val dataStart = pos + idLen + sizeLen
            val end = if (allOnes) limit else dataStart + size
            if (end > limit || end < dataStart) return null
            return El(id, dataStart, end)
        }

        private fun vintLength(first: Byte): Int {
            val b = first.toInt() and 0xFF
            for (i in 0 until 8) if (b and (0x80 shr i) != 0) return i + 1
            return 0
        }

        fun children(src: ByteSource, from: Long, to: Long, stopAt: Long = -1, max: Int = 10_000): List<El> {
            val out = ArrayList<El>()
            var pos = from
            while (pos < to && out.size < max) {
                val e = element(src, pos, to) ?: break
                if (e.id == stopAt) break
                out.add(e)
                pos = e.end
            }
            return out
        }

        private fun uint(src: ByteSource, e: El): Long {
            val b = bytes(src, e.dataStart, minOf(8, e.end - e.dataStart)) ?: return 0
            var v = 0L
            for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
            return v
        }

        private const val INFO = 0x1549A966L
        private const val TIMESTAMP_SCALE = 0x2AD7B1L
        private const val TRACKS = 0x1654AE6BL
        private const val TRACK_ENTRY = 0xAEL
        private const val TRACK_NUMBER = 0xD7L
        private const val TRACK_TYPE = 0x83L
        private const val CODEC_ID = 0x86L
        private const val LANGUAGE = 0x22B59CL
        private const val FLAG_DEFAULT = 0x88L
        private const val CLUSTER_TIMESTAMP = 0xE7L
        private const val SIMPLE_BLOCK = 0xA3L
        private const val BLOCK_GROUP = 0xA0L
        private const val BLOCK = 0xA1L
        private const val BLOCK_DURATION = 0x9BL
        private val TEXT_CODECS = setOf("S_TEXT/UTF8", "S_TEXT/ASS", "S_TEXT/SSA", "S_TEXT/WEBVTT")

        private fun string(src: ByteSource, e: El): String =
            bytes(src, e.dataStart, minOf(256, e.end - e.dataStart))?.let { String(it, Charsets.UTF_8).trimEnd('\u0000') } ?: ""

        /**
         * The text subtitle track: `Tracks` → a `TrackEntry` of type 17 with a text codec, then every
         * cluster's blocks of that track. A line without `BlockDuration` lasts until the next one (5 s at most).
         */
        fun subtitles(src: ByteSource, wanted: String): List<TimedLine> {
            val top = children(src, 0, src.size, max = 16)
            val segment = top.firstOrNull { it.id == SEGMENT } ?: return emptyList()
            var scale = 1_000_000L
            var track = -1L
            var codec = ""
            val out = ArrayList<TimedLine>()
            var pos = segment.dataStart
            var elements = 0
            while (pos < segment.end && elements++ < 1_000_000 && out.size < MAX_SAMPLES) {
                val e = element(src, pos, segment.end) ?: break
                when (e.id) {
                    INFO -> children(src, e.dataStart, e.end).firstOrNull { it.id == TIMESTAMP_SCALE }?.let { scale = uint(src, it).takeIf { v -> v > 0 } ?: scale }
                    TRACKS -> {
                        val subs = children(src, e.dataStart, e.end).filter { it.id == TRACK_ENTRY }.mapNotNull { t ->
                            val parts = children(src, t.dataStart, t.end)
                            if (parts.firstOrNull { it.id == TRACK_TYPE }?.let { uint(src, it) } != 17L) return@mapNotNull null
                            val c = parts.firstOrNull { it.id == CODEC_ID }?.let { string(src, it) } ?: return@mapNotNull null
                            if (c !in TEXT_CODECS) return@mapNotNull null
                            val number = parts.firstOrNull { it.id == TRACK_NUMBER }?.let { uint(src, it) } ?: return@mapNotNull null
                            val lang = parts.firstOrNull { it.id == LANGUAGE }?.let { string(src, it) } ?: "eng" // the Matroska default
                            val default = parts.firstOrNull { it.id == FLAG_DEFAULT }?.let { uint(src, it) } != 0L
                            SubTrack(number, c, lang, default)
                        }
                        val pick = subs.firstOrNull { languageMatches(it.language, wanted) } ?: subs.firstOrNull { it.default } ?: subs.firstOrNull()
                        if (pick == null) return emptyList()
                        track = pick.number
                        codec = pick.codec
                    }
                    CLUSTER -> if (track >= 0) cluster(src, e, track, codec, scale, out)
                }
                pos = e.end
            }
            if (track < 0) return emptyList()
            out.sortBy { it.startMs }
            // A line without a duration ends where the next one starts.
            return out.mapIndexed { i, l ->
                if (l.endMs >= 0) l else l.copy(endMs = minOf(out.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE, l.startMs + 5_000))
            }
        }

        private class SubTrack(val number: Long, val codec: String, val language: String, val default: Boolean)

        private fun cluster(src: ByteSource, cluster: El, track: Long, codec: String, scale: Long, out: MutableList<TimedLine>) {
            var base = 0L
            for (e in children(src, cluster.dataStart, cluster.end, max = 100_000)) {
                when (e.id) {
                    CLUSTER_TIMESTAMP -> base = uint(src, e)
                    SIMPLE_BLOCK -> block(src, e, track, codec, base, -1, scale)?.let(out::add)
                    BLOCK_GROUP -> {
                        val parts = children(src, e.dataStart, e.end)
                        val b = parts.firstOrNull { it.id == BLOCK } ?: continue
                        val dur = parts.firstOrNull { it.id == BLOCK_DURATION }?.let { uint(src, it) } ?: -1
                        block(src, b, track, codec, base, dur, scale)?.let(out::add)
                    }
                }
                if (out.size >= MAX_SAMPLES) return
            }
        }

        /** A block: the track number (a vint), a signed 16-bit time from the cluster's, flags, then the text. Other tracks' blocks are skipped unread. */
        private fun block(src: ByteSource, e: El, track: Long, codec: String, base: Long, duration: Long, scale: Long): TimedLine? {
            val h = ByteArray(4)
            if (src.read(e.dataStart, h) < 4) return null
            val len = vintLength(h[0])
            if (len != 1) return null // track numbers above 127: not a subtitle track we picked
            if ((h[0].toLong() and 0x7F) != track) return null
            val tc = ((h[1].toInt() shl 8) or (h[2].toInt() and 0xFF)).toShort().toLong()
            val data = bytes(src, e.dataStart + 4, e.end - e.dataStart - 4) ?: return null
            val text = clean(String(data, Charsets.UTF_8), codec).takeIf { it.isNotBlank() } ?: return null
            val start = (base + tc) * scale / 1_000_000
            val end = if (duration >= 0) start + duration * scale / 1_000_000 else -1
            return TimedLine(start, end, text)
        }

        /** ASS / SSA blocks: `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`; override tags and `\N` handled. */
        internal fun clean(raw: String, codec: String): String {
            var t = raw.trimEnd('\u0000')
            if (codec == "S_TEXT/ASS" || codec == "S_TEXT/SSA") {
                t = t.split(',', limit = 9).getOrNull(8) ?: return ""
                t = t.replace(Regex("\\{[^}]*}"), "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            } else {
                t = t.replace(Regex("<[^>]*>"), "")
            }
            return t.lines().joinToString("\n") { it.trim() }.trim()
        }

        fun chapters(src: ByteSource): List<Chapter> {
            val top = children(src, 0, src.size, max = 16)
            val segment = top.firstOrNull { it.id == SEGMENT } ?: return emptyList()
            // Top-level elements up to the first cluster; the seek head points past the clusters.
            val head = children(src, segment.dataStart, segment.end, stopAt = CLUSTER, max = 64)
            val chapters = head.firstOrNull { it.id == CHAPTERS } ?: run {
                val seekHead = head.firstOrNull { it.id == SEEK_HEAD } ?: return emptyList()
                val pos = children(src, seekHead.dataStart, seekHead.end).filter { it.id == SEEK }.firstNotNullOfOrNull { seek ->
                    val parts = children(src, seek.dataStart, seek.end)
                    val id = parts.firstOrNull { it.id == SEEK_ID }?.let { uint(src, it) }
                    if (id == CHAPTERS) parts.firstOrNull { it.id == SEEK_POSITION }?.let { uint(src, it) } else null
                } ?: return emptyList()
                element(src, segment.dataStart + pos, segment.end)?.takeIf { it.id == CHAPTERS } ?: return emptyList()
            }
            if (chapters.end - chapters.dataStart > MAX_BLOCK) return emptyList()
            val editions = children(src, chapters.dataStart, chapters.end).filter { it.id == EDITION }
            val visible = editions.filter { ed -> children(src, ed.dataStart, ed.end).none { it.id == EDITION_HIDDEN && uint(src, it) == 1L } }
            val edition = visible.firstOrNull { ed -> children(src, ed.dataStart, ed.end).any { it.id == EDITION_DEFAULT && uint(src, it) == 1L } }
                ?: visible.firstOrNull() ?: return emptyList()
            val out = ArrayList<Chapter>()
            for (atom in children(src, edition.dataStart, edition.end).filter { it.id == ATOM }) {
                val parts = children(src, atom.dataStart, atom.end)
                if (parts.any { it.id == HIDDEN && uint(src, it) == 1L }) continue
                if (parts.any { it.id == ENABLED && uint(src, it) == 0L }) continue
                val start = parts.firstOrNull { it.id == TIME_START }?.let { uint(src, it) / 1_000_000 } ?: continue
                val title = parts.firstOrNull { it.id == DISPLAY }?.let { d ->
                    children(src, d.dataStart, d.end).firstOrNull { it.id == STRING }?.let { s ->
                        bytes(src, s.dataStart, s.end - s.dataStart)?.let { String(it, Charsets.UTF_8).trimEnd('\u0000') }
                    }
                } ?: ""
                out.add(Chapter(start, title))
                if (out.size >= MAX_CHAPTERS) break
            }
            return out.sortedBy { it.startMs }
        }
    }
}
