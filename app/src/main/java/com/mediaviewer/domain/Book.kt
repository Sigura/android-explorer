package com.mediaviewer.domain

import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind

/** How a folder plays in the audio player (todo §3.1, §3.6). */
enum class BookKind {
    /** Audio files only (plus covers, subtitles, playlists…). */
    AUDIO,

    /** Audio files and videos: one queue, book mode. */
    MIXED,

    /** Only long videos: listened to, the picture is never shown. */
    LECTURE,
}

/** Book rules: which folders play as books, in what order, and where to resume. Pure Kotlin. */
object Book {
    /** Lecture detection when the long-video setting is off. */
    const val DEFAULT_LONG_MS = 3 * 60_000L

    fun isAudio(e: FileEntry) = e.type == MediaType.OTHER && e.other == OtherKind.AUDIO
    fun isPlayable(e: FileEntry) = isAudio(e) || e.type == MediaType.VIDEO

    /**
     * Null when the folder's Play button starts the slideshow instead. Images count as covers
     * while there are no more of them than playable files (`yt-dlp` writes one thumbnail per video).
     * @param durationMs a video's duration, or null while it isn't known yet.
     */
    fun classify(entries: List<FileEntry>, longMs: Long, durationMs: (FileEntry) -> Long?): BookKind? {
        val audio = entries.count(::isAudio)
        val videos = entries.filter { it.type == MediaType.VIDEO }
        val images = entries.count { it.type == MediaType.IMAGE || it.type == MediaType.ANIMATED }
        if (images > audio + videos.size) return null
        if (audio > 0) return if (videos.isEmpty()) BookKind.AUDIO else BookKind.MIXED
        if (videos.size < 2) return null
        val limit = if (longMs > 0) longMs else DEFAULT_LONG_MS
        return if (videos.all { (durationMs(it) ?: 0) > limit }) BookKind.LECTURE else null
    }

    /**
     * Queue order (todo §3.5): playlist entries first (missing ones skipped), then the files it
     * doesn't list, by natural name order. Without a playlist: natural name order only.
     * @param playlist absolute paths from [Playlist.parse], or null.
     */
    fun order(playable: List<FileEntry>, playlist: List<String>?): List<FileEntry> {
        val byName = playable.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        if (playlist.isNullOrEmpty()) return byName
        val byPath = playable.associateBy { it.path }
        val listed = LinkedHashSet<FileEntry>()
        for (p in playlist) byPath[p]?.let { listed.add(it) }
        return listed.toList() + byName.filter { it !in listed }
    }

    /** The playlist to follow: the one named like the folder, otherwise the first by name. */
    fun choosePlaylist(names: List<String>, folderName: String): String? {
        val lists = names.filter { n -> n.substringAfterLast('.', "").lowercase().let { it == "m3u" || it == "m3u8" } }
        return lists.firstOrNull { it.substringBeforeLast('.').equals(folderName, ignoreCase = true) }
            ?: lists.minWithOrNull(NaturalOrder)
    }

    /**
     * Smart rewind on resume (todo §3.4): nothing after a short pause, a few seconds after a
     * longer one, more after hours, so the listener finds the thread again.
     */
    fun rewindMs(pausedForMs: Long): Long = when {
        pausedForMs < 60_000L -> 0L
        pausedForMs < 3_600_000L -> 5_000L
        else -> 20_000L
    }

    /** A file counts as finished within 2 % or 30 s of its end, whichever is less. */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && durationMs - positionMs <= minOf(30_000L, durationMs / 50)
}

/** `.m3u` / `.m3u8` playlists (todo §3.5). Pure Kotlin. */
object Playlist {
    /**
     * Absolute local paths in playlist order. `#` lines are comments (`#EXTINF` titles are not
     * needed: the player shows file names); URLs are skipped; `\` separators are accepted.
     * @param dir the playlist's folder, for relative paths.
     */
    fun parse(text: String, dir: String): List<String> {
        val base = dir.trimEnd('/')
        val out = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#")) continue
            if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(line)) continue
            var p = line.replace('\\', '/')
            if (p.startsWith("file://")) p = p.removePrefix("file://")
            p = when {
                // A Windows path (`C:\Books\…`) can't exist here: match the file by name in this folder.
                Regex("^[a-zA-Z]:/").containsMatchIn(p) -> "$base/${p.substringAfterLast('/')}"
                p.startsWith("/") -> p
                else -> "$base/$p"
            }
            out.add(normalize(p))
        }
        return out
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }
}
