package com.mediaviewer.data.history

import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.thumbs.TextDecode
import com.mediaviewer.domain.Book
import com.mediaviewer.domain.Playlist
import com.mediaviewer.domain.model.FileEntry
import java.io.File
import java.nio.charset.Charset

/** Where to start a book: the queue, the file and the position in it. */
data class ResumePoint(val folder: String, val queue: List<FileEntry>, val index: Int, val positionMs: Long, val speed: Float)

/**
 * Builds a book's queue from disk (todo §3.5) and finds where to continue (todo §3.4).
 * Blocking: call from `Dispatchers.IO`.
 */
object BookFolder {
    /** Playlists larger than this are not read (a playlist is a few KB). */
    private const val MAX_PLAYLIST_BYTES = 512 * 1024

    /** Audio files and videos of [folder] in play order: its `.m3u` playlist if any, then natural name order. */
    fun queue(folder: String, legacyCharset: Charset): List<FileEntry> {
        val files = File(folder).listFiles()?.filter { it.isFile && !FolderScanner.isHidden(it.name) } ?: return emptyList()
        val playable = files.mapNotNull { FolderScanner.entryFor(it, showHidden = false) }.filter(Book::isPlayable)
        val listName = Book.choosePlaylist(files.map { it.name }, File(folder).name)
        val playlist = listName?.let { readPlaylist(File(folder, it), legacyCharset) }?.let { Playlist.parse(it, folder) }
        return Book.order(playable, playlist)
    }

    private fun readPlaylist(file: File, legacyCharset: Charset): String? = try {
        if (file.length() > MAX_PLAYLIST_BYTES) {
            null
        } else {
            val bytes = file.readBytes()
            // `.m3u8` is UTF-8 by definition; `.m3u` may be in a legacy charset.
            if (file.name.endsWith(".m3u8", ignoreCase = true)) {
                String(bytes, Charsets.UTF_8)
            } else {
                TextDecode.decode(bytes, bytes.size, legacyCharset, TextDecode.cyrillicDetector)
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Continue where the book stopped, with smart rewind. A finished book, or one whose current
     * file is gone, starts from the beginning.
     */
    fun resume(folder: String, history: HistoryStore, legacyCharset: Charset): ResumePoint? {
        val queue = queue(folder, legacyCharset)
        if (queue.isEmpty()) return null
        val h = history.folder(folder)
        if (h == null || h.finished) {
            if (h?.finished == true) history.reset(folder)
            return ResumePoint(folder, queue, 0, 0, h?.speed ?: 1f)
        }
        val index = queue.indexOfFirst { it.path == h.currentPath }
        if (index < 0) return ResumePoint(folder, queue, 0, 0, h.speed)
        val back = Book.rewindMs(System.currentTimeMillis() - h.playedAt)
        return ResumePoint(folder, queue, index, (h.positionMs - back).coerceAtLeast(0), h.speed)
    }
}
