package com.mediaviewer.domain

/** A chapter inside one file (todo §4.5). */
data class Chapter(val startMs: Long, val title: String)

/**
 * Chapter rules for the player: which one is playing, where ⏮ / ⏭ go, where the current one ends.
 * Lists come sorted from [normalize]. Pure Kotlin.
 */
object Chapters {
    /** ⏮ within this long after a chapter start goes to the previous chapter, as for files. */
    const val RESTART_MS = 3_000L

    /** Sorted by start, duplicates and chapters past the end dropped; a list of one is no chapters. */
    fun normalize(chapters: List<Chapter>, durationMs: Long = 0): List<Chapter> {
        val sorted = chapters
            .filter { it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
        if (sorted.size < 2) return emptyList()
        // A first chapter that starts a little late still covers the beginning of the file.
        return if (sorted[0].startMs in 1..RESTART_MS) listOf(sorted[0].copy(startMs = 0)) + sorted.drop(1) else sorted
    }

    /** The chapter playing at [ms], or -1 before the first one. */
    fun indexAt(chapters: List<Chapter>, ms: Long): Int = chapters.indexOfLast { it.startMs <= ms }

    /** ⏮: the start of this chapter, or of the previous one within [RESTART_MS] of it; null = go to the previous file. */
    fun previousStart(chapters: List<Chapter>, ms: Long): Long? {
        val i = indexAt(chapters, ms)
        if (i < 0) return null
        if (ms - chapters[i].startMs > RESTART_MS) return chapters[i].startMs
        return if (i > 0) chapters[i - 1].startMs else null
    }

    /** ⏭: the start of the next chapter; null = go to the next file. */
    fun nextStart(chapters: List<Chapter>, ms: Long): Long? = chapters.firstOrNull { it.startMs > ms }?.startMs

    /** Where the chapter playing at [ms] ends: the next one's start, or the file's end. */
    fun endAt(chapters: List<Chapter>, ms: Long, durationMs: Long): Long =
        nextStart(chapters, ms) ?: durationMs
}

/** One `FILE` of a `.cue` sheet and its tracks as chapters. */
data class CueFile(val name: String, val chapters: List<Chapter>)

/**
 * `.cue` sheets (todo §4.5): one large `.flac` / `.mp3` / `.ape` with its tracks as chapters.
 * Handles quoted and bare names, several `FILE`s, `INDEX 01 mm:ss:ff` (75 frames a second), and
 * `TITLE` / `PERFORMER` per track. Pure Kotlin.
 */
object CueSheet {
    fun parse(text: String): List<CueFile> {
        val files = ArrayList<CueFile>()
        var file: String? = null
        var chapters = ArrayList<Chapter>()
        var trackNo = 0
        var title: String? = null
        var performer: String? = null
        var start: Long? = null

        fun endTrack() {
            val s = start
            if (trackNo > 0 && s != null) {
                val name = when {
                    !title.isNullOrBlank() && !performer.isNullOrBlank() && performer != title -> "$title — $performer"
                    !title.isNullOrBlank() -> title!!
                    else -> "Track %02d".format(trackNo)
                }
                chapters.add(Chapter(s, name))
            }
            trackNo = 0
            title = null
            performer = null
            start = null
        }

        fun endFile() {
            endTrack()
            file?.let { files.add(CueFile(it, chapters)) }
            chapters = ArrayList()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            val cmd = line.substringBefore(' ').uppercase()
            val arg = line.substringAfter(' ', "").trim()
            when (cmd) {
                "FILE" -> {
                    endFile()
                    file = if (arg.startsWith('"')) arg.drop(1).substringBefore('"') else arg.substringBeforeLast(' ')
                }
                "TRACK" -> {
                    endTrack()
                    trackNo = arg.substringBefore(' ').toIntOrNull() ?: 0
                }
                "TITLE" -> if (trackNo > 0) title = unquote(arg)
                "PERFORMER" -> if (trackNo > 0) performer = unquote(arg)
                "INDEX" -> {
                    val parts = arg.split(Regex("\\s+"))
                    if (trackNo > 0 && parts.size >= 2 && parts[0].toIntOrNull() == 1) start = msf(parts[1])
                }
            }
        }
        endFile()
        return files.filter { it.chapters.isNotEmpty() }
    }

    /**
     * Chapters of the audio file [fileName] in [sheet] (the `.cue` named [sheetName]): the `FILE`
     * with that name, or its base name (a sheet written for `.wav` that is now a `.flac`), or the
     * only `FILE` of a sheet named like the audio file.
     */
    fun chaptersFor(sheet: List<CueFile>, sheetName: String, fileName: String): List<Chapter> {
        val base = fileName.substringBeforeLast('.').lowercase()
        fun baseOf(n: String) = n.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').lowercase()
        sheet.firstOrNull { it.name.replace('\\', '/').substringAfterLast('/').equals(fileName, ignoreCase = true) }?.let { return it.chapters }
        sheet.firstOrNull { baseOf(it.name) == base }?.let { return it.chapters }
        if (sheet.size == 1 && sheetName.substringBeforeLast('.').lowercase() == base) return sheet[0].chapters
        return emptyList()
    }

    private fun unquote(s: String): String = if (s.startsWith('"')) s.drop(1).substringBefore('"') else s

    private fun msf(s: String): Long? {
        val p = s.split(':')
        if (p.size != 3) return null
        val m = p[0].toLongOrNull() ?: return null
        val sec = p[1].toLongOrNull() ?: return null
        val f = p[2].toLongOrNull() ?: return null
        return m * 60_000 + sec * 1000 + f * 1000 / 75
    }
}
