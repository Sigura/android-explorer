package com.mediaviewer.data.thumbs

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Text detection and decoding for tile previews (README §3.10). Pure JVM code, so it is unit-tested;
 * the platform charset detector is passed in as [Detector].
 */
object TextDecode {
    /** Bytes read for a preview: enough for a tile full of lines. */
    const val READ_BYTES = 8192

    /** More than this share of control characters (other than tab, CR, LF, FF, ESC) means binary. */
    private const val MAX_CONTROL = 0.02

    /** A charset guess from the bytes, or null when not confident. */
    fun interface Detector {
        fun detect(bytes: ByteArray, length: Int): Charset?
    }

    /** First [READ_BYTES] of [file] as text, or null if it looks binary. Blocking: call from the decode dispatcher. */
    fun read(file: File, fallback: Charset, detector: Detector? = null): String? {
        val buf = ByteArray(READ_BYTES)
        val n = RandomAccessFile(file, "r").use { raf ->
            var total = 0
            while (total < buf.size) {
                val r = raf.read(buf, total, buf.size - total)
                if (r < 0) break
                total += r
            }
            total
        }
        return decode(buf, n, fallback, detector)
    }

    /**
     * 1. BOM → UTF-8 / UTF-16. 2. A NUL byte → binary. 3. Strict UTF-8 (a character cut at the end
     * of the buffer is fine). 4. Otherwise legacy 8-bit text: the charset the file declares, then
     * [detector], then [fallback].
     */
    fun decode(bytes: ByteArray, length: Int, fallback: Charset, detector: Detector? = null): String? =
        decodeFull(bytes, length, fallback, detector)?.text

    /** Decoded text plus what the editor needs to write it back the same way (todo §6.4). */
    class Decoded(val text: String, val charset: Charset, val bom: Boolean)

    /** [decode], also telling the charset and whether the text had a BOM. */
    fun decodeFull(bytes: ByteArray, length: Int, fallback: Charset, detector: Detector? = null): Decoded? {
        if (length <= 0) return Decoded("", Charsets.UTF_8, false)
        fun u(i: Int) = bytes[i].toInt() and 0xff
        when {
            length >= 3 && u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF -> return Decoded(String(bytes, 3, length - 3, Charsets.UTF_8), Charsets.UTF_8, true)
            length >= 2 && u(0) == 0xFF && u(1) == 0xFE -> return Decoded(String(bytes, 2, (length - 2) and 1.inv(), Charsets.UTF_16LE), Charsets.UTF_16LE, true)
            length >= 2 && u(0) == 0xFE && u(1) == 0xFF -> return Decoded(String(bytes, 2, (length - 2) and 1.inv(), Charsets.UTF_16BE), Charsets.UTF_16BE, true)
        }
        var control = 0
        for (i in 0 until length) {
            val b = u(i)
            if (b == 0) return null
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x1B) control++
        }
        if (control > length * MAX_CONTROL) return null
        strictUtf8(bytes, length)?.let { return Decoded(it, Charsets.UTF_8, false) }
        val charset = declared(bytes, length) ?: detector?.detect(bytes, length) ?: fallback
        return Decoded(String(bytes, 0, length, charset), charset, false)
    }

    private fun strictUtf8(bytes: ByteArray, length: Int): String? {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(length)
        // endOfInput = false: an incomplete sequence at the end of the buffer is left over, not an error.
        val r = dec.decode(ByteBuffer.wrap(bytes, 0, length), out, false)
        if (r.isError) return null
        out.flip()
        return out.toString()
    }

    private val CYRILLIC_CHARSETS = listOf("windows-1251", "KOI8-R", "IBM866")
    /** The most frequent Russian / Ukrainian lowercase letters: correct decoding puts most letters here. */
    private const val FREQUENT = "оеаинтсрвлкмдпуяыьгзбчйіїє"

    /**
     * Picks Windows-1251, KOI8-R or CP866 for Cyrillic text by letter frequency. Null for text that
     * isn't mostly high-bit letters (Western 8-bit text has only a few accented letters), so the
     * setting decides.
     */
    val cyrillicDetector = Detector { bytes, length ->
        var high = 0
        var visible = 0
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xff
            if (b > 0x20) visible++
            if (b >= 0x80) high++
        }
        if (high < 8 || high < visible * 0.3) return@Detector null
        var best: Charset? = null
        var bestScore = 0
        for (name in CYRILLIC_CHARSETS) {
            val cs = try {
                Charset.forName(name)
            } catch (e: Exception) {
                continue
            }
            val text = String(bytes, 0, length, cs)
            var score = 0
            for (ch in text) if (ch in FREQUENT) score++
            if (score > bestScore) {
                best = cs
                bestScore = score
            }
        }
        // In the right charset roughly two thirds of the letters are frequent ones.
        best.takeIf { bestScore >= high * 0.4 }
    }

    // Explicit ASCII classes: Android's ICU regex rejects `[-\w.:]` (a leading `-` next to a class
    // escape), which the JVM accepts, so unit tests don't catch it.
    private val CODING = Regex("""coding[:=]\s*["']?([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE)
    private val META_CHARSET = Regex("""charset\s*=\s*["']?([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE)

    /**
     * A charset the file names itself: `<?xml encoding="…"?>`, HTML `<meta charset>`, Python and
     * Emacs `coding: …`, Vim `fileencoding=…`. Only the first 1 KB is searched.
     */
    fun declared(bytes: ByteArray, length: Int): Charset? {
        val head = String(bytes, 0, minOf(length, 1024), Charsets.ISO_8859_1)
        val name = (CODING.find(head) ?: META_CHARSET.find(head))?.groupValues?.get(1) ?: return null
        return try {
            Charset.forName(name).takeIf { it != Charsets.UTF_8 }
        } catch (e: Exception) {
            null
        }
    }

    /** "Text files without encoding" default: Windows-1251 for Cyrillic-script languages, Windows-1252 otherwise. */
    fun defaultLegacyCharset(locale: Locale = Locale.getDefault()): String =
        if (locale.language in setOf("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "tg", "mn")) "windows-1251" else "windows-1252"

    /**
     * Preview lines: leading blank lines dropped, tabs expanded to 4 columns, control characters
     * removed, at most [maxLines]. The last line is dropped if the buffer ended inside it.
     */
    fun lines(text: String, maxLines: Int, truncated: Boolean): List<String> {
        var all = text.split('\n')
        if (truncated && all.size > 1) all = all.dropLast(1)
        val out = ArrayList<String>(minOf(maxLines, all.size))
        for (raw in all) {
            if (out.size >= maxLines) break
            val line = clean(raw)
            if (out.isEmpty() && line.isBlank()) continue
            out.add(line)
        }
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
        return out
    }

    private fun clean(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '\t' -> do sb.append(' ') while (sb.length % 4 != 0)
                ch < ' ' || ch == '\u007f' || ch == '\uFEFF' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString().trimEnd()
    }
}
