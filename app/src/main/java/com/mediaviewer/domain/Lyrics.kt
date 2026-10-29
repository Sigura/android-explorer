package com.mediaviewer.domain

/** One line of lyrics or one subtitle cue; [endMs] = -1 when it lasts until the next line (LRC). */
data class TimedLine(val startMs: Long, val endMs: Long, val text: String)

/**
 * Lyrics or subtitles for the player screen (todo §4.4). [synced] text has a time per line, and the
 * line playing is highlighted; unsynced text (an ID3 `USLT` frame without timestamps) is shown as is,
 * with every [TimedLine.startMs] = 0. Pure Kotlin.
 */
class Lyrics(val lines: List<TimedLine>, val synced: Boolean) {
    /** The line playing at [ms]: the last one started, or -1 before the first or in a gap between subtitles. */
    fun indexAt(ms: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= ms) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return -1
        val end = lines[found].endMs
        return if (end in 0..ms) -1 else found
    }

    companion object {
        /** Enough lines for a long album track or a lecture; more is a broken file. */
        private const val MAX_LINES = 20_000

        private val LRC_STAMP = Regex("""\[(\d{1,4}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val LRC_TAG = Regex("""^\[([a-zA-Z#]+):(.*)]\s*$""")
        private val LRC_WORD_STAMP = Regex("""<\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?>""")
        private val CUE_TIME = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{1,2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{1,2}[.,]\d{1,3})""")
        private val TAG = Regex("""<[^>]*>|\{\\[^}]*}""")

        /** Text with two or more `[mm:ss.xx]` lines is LRC, also when it came from an unsynced tag. */
        fun looksLikeLrc(text: String): Boolean =
            text.lineSequence().take(200).count { LRC_STAMP.find(it.trimStart())?.range?.first == 0 } >= 2

        /** Synced lyrics when [text] is LRC, otherwise the text as plain lines; null when it is empty. */
        fun fromText(text: String): Lyrics? =
            if (looksLikeLrc(text)) parseLrc(text) else plain(text)

        fun plain(text: String): Lyrics? {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').trim().lines().take(MAX_LINES)
            if (lines.all { it.isBlank() }) return null
            return Lyrics(lines.map { TimedLine(0, -1, it.trimEnd()) }, synced = false)
        }

        /**
         * `.lrc`: `[mm:ss.xx] line`, several stamps on one line (a repeated chorus), `[offset:±ms]`
         * (positive = the lines come sooner), word stamps `<mm:ss.xx>` (enhanced LRC) removed,
         * `[ar:]` / `[ti:]` and the like skipped. An empty stamped line is a pause: nothing is highlighted.
         */
        fun parseLrc(text: String): Lyrics? {
            var offset = 0L
            val out = ArrayList<TimedLine>()
            for (raw in text.lineSequence()) {
                val line = raw.trim().removePrefix("﻿")
                if (line.isEmpty()) continue
                var rest = line
                val starts = ArrayList<Long>(1)
                while (true) {
                    val m = LRC_STAMP.find(rest) ?: break
                    if (m.range.first != 0) break
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.toLong()
                    }
                    starts.add(min * 60_000 + sec * 1000 + ms)
                    rest = rest.substring(m.range.last + 1).trimStart()
                }
                if (starts.isEmpty()) {
                    val tag = LRC_TAG.find(line)
                    if (tag != null && tag.groupValues[1].equals("offset", ignoreCase = true)) {
                        offset = tag.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0L
                    }
                    continue
                }
                val words = rest.replace(LRC_WORD_STAMP, "").replace(Regex("\\s{2,}"), " ").trim()
                for (s in starts) out.add(TimedLine(s, -1, words))
                if (out.size >= MAX_LINES) break
            }
            if (out.none { it.text.isNotEmpty() }) return null
            out.sortBy { it.startMs } // stable: lines with the same time keep their order
            val shifted = out.map { it.copy(startMs = (it.startMs - offset).coerceAtLeast(0)) }
            // An empty line ends the one before it: a gap with nothing highlighted.
            val withGaps = shifted.mapIndexedNotNull { i, l ->
                if (l.text.isEmpty()) null else l.copy(endMs = shifted.getOrNull(i + 1)?.takeIf { it.text.isEmpty() }?.startMs ?: -1)
            }
            return Lyrics(withGaps, synced = true)
        }

        /**
         * `.srt` and `.vtt` subtitles: cues with a start and an end; numbers, `NOTE` / `STYLE` /
         * `REGION` blocks, cue settings and formatting tags dropped. YouTube's automatic captions
         * repeat the previous line at the top of each cue; that repeat is removed.
         */
        fun parseSubtitles(text: String): Lyrics? {
            val out = ArrayList<TimedLine>()
            val blocks = text.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿").split(Regex("\n[ \t]*\n"))
            var lastLine: String? = null
            for (block in blocks) {
                val lines = block.lines()
                val at = lines.indexOfFirst { CUE_TIME.containsMatchIn(it) }
                if (at < 0) continue
                val m = CUE_TIME.find(lines[at]) ?: continue
                val start = cueTime(m.groupValues[1]) ?: continue
                val end = cueTime(m.groupValues[2]) ?: continue
                var body = lines.drop(at + 1).map { clean(it) }.filter { it.isNotBlank() }
                if (body.size > 1 && body.first() == lastLine) body = body.drop(1)
                if (body.isEmpty()) continue
                // A cue that only repeats the last line (rolling captions) adds nothing.
                if (body.size == 1 && body[0] == lastLine && out.isNotEmpty()) {
                    val prev = out.last()
                    if (end > prev.endMs) out[out.lastIndex] = prev.copy(endMs = end)
                    continue
                }
                out.add(TimedLine(start, end.coerceAtLeast(start), body.joinToString("\n")))
                lastLine = body.last()
                if (out.size >= MAX_LINES) break
            }
            if (out.isEmpty()) return null
            out.sortBy { it.startMs }
            return Lyrics(out, synced = true)
        }

        /** `hh:mm:ss,mmm`, `mm:ss.mmm`. */
        private fun cueTime(s: String): Long? {
            val parts = s.replace(',', '.').split(':')
            val secParts = parts.last().split('.')
            val sec = secParts[0].toLongOrNull() ?: return null
            val frac = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0
            val min = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0
            val hours = if (parts.size >= 3) parts[parts.size - 3].toLongOrNull() ?: return null else 0
            return ((hours * 60 + min) * 60 + sec) * 1000 + frac
        }

        private fun clean(line: String): String =
            line.replace(TAG, "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .trim()
    }
}

/** Which companion file holds the text for an audio or video file (todo §4.4). Pure Kotlin. */
object Companions {
    private val LYRIC_EXTENSIONS = listOf("lrc", "srt", "vtt")

    /**
     * The `.lrc`, `.srt` or `.vtt` beside [fileName] among the folder's [names]: the same base
     * name first (`07 Song.lrc`), then a language variant (`Lecture 1.en.vtt`, as `yt-dlp`
     * writes), preferring [language] and then English. Null when there is none.
     */
    fun textFor(fileName: String, names: Collection<String>, language: String = ""): String? {
        val base = fileName.substringBeforeLast('.')
        val byLower = names.associateBy { it.lowercase() }
        for (ext in LYRIC_EXTENSIONS) byLower["${base.lowercase()}.$ext"]?.let { return it }
        val prefix = base.lowercase() + "."
        val variants = names.filter { n ->
            val l = n.lowercase()
            l.startsWith(prefix) && l.substringAfterLast('.') in LYRIC_EXTENSIONS && l.length > prefix.length + 4
        }
        if (variants.isEmpty()) return null
        fun lang(n: String) = n.substring(prefix.length).substringBeforeLast('.').lowercase()
        val wanted = listOf(language.lowercase(), "en").filter { it.isNotEmpty() }
        for (w in wanted) variants.firstOrNull { lang(it) == w || lang(it).startsWith("$w-") }?.let { return it }
        return variants.minWithOrNull(NaturalOrder)
    }

    private val COVER_NAMES = listOf("cover", "folder", "front", "albumart", "album")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /**
     * The cover image among the folder's [imageNames] (todo §4.1): the image named like the file
     * itself ([fileName]; `yt-dlp` writes one per video), `cover` / `folder` / `front` (any case and
     * image extension), then names starting with `AlbumArt`, then the only image.
     */
    fun coverFor(imageNames: Collection<String>, fileName: String? = null): String? {
        val images = imageNames.filter { it.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
        fileName?.substringBeforeLast('.')?.lowercase()?.let { base -> images.firstOrNull { it.substringBeforeLast('.').lowercase() == base }?.let { return it } }
        for (want in COVER_NAMES) images.firstOrNull { it.substringBeforeLast('.').lowercase() == want }?.let { return it }
        images.filter { it.lowercase().startsWith("albumart") }.minWithOrNull(NaturalOrder)?.let { return it }
        return images.singleOrNull()
    }
}
