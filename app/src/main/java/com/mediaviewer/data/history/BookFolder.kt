package com.mediaviewer.data.history

import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.thumbs.TextDecode
import com.mediaviewer.domain.Book
import com.mediaviewer.domain.BookMatch
import com.mediaviewer.domain.Playlist
import com.mediaviewer.domain.ShuffleOrder
import com.mediaviewer.domain.model.FileEntry
import java.io.File
import java.nio.charset.Charset

/** Where to start a book: the queue, the file and the position in it; [music] = shuffled music mode (todo §3.1). */
data class ResumePoint(
    val folder: String,
    val queue: List<FileEntry>,
    val index: Int,
    val positionMs: Long,
    val speed: Float,
    val music: Boolean = false,
    /** `Player.REPEAT_MODE_*`, music mode only. */
    val repeat: Int = 0,
)

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
     * A renamed or moved book (todo §3.4): the history of a folder that is gone, whose recorded
     * files are here, moves to [folder]. Only folders on storage that is present count as gone,
     * so a book on an unplugged SD card keeps its history.
     */
    private fun adoptMoved(folder: String, queue: List<FileEntry>, history: HistoryStore): FolderHistory? {
        val orphans = history.orphans { path -> File(path).exists() || !File(storageRoot(path)).exists() }
        if (orphans.isEmpty()) return null
        val here = queue.map { BookMatch.FileKey(it.name, it.size) }
        val candidates = orphans.map { (h, files) ->
            val current = h.currentPath?.let { p -> files.firstOrNull { it.name == File(p).name } ?: FileRecord(File(p).name, 0) }
            BookMatch.Candidate(h.folder, files.map { BookMatch.FileKey(it.name, it.size) }, current?.let { BookMatch.FileKey(it.name, it.size) })
        }
        val from = BookMatch.best(here, File(folder).name, candidates) ?: return null
        history.moveBook(from, folder)
        return history.folder(folder)
    }

    /** `/storage/emulated/0` or `/storage/ABCD-1234` (the volume a path is on); `/` for anything else. */
    internal fun storageRoot(path: String): String {
        val parts = path.trim('/').split('/')
        return when {
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" -> "/" + parts.take(3).joinToString("/")
            parts.size >= 2 && parts[0] == "storage" -> "/" + parts.take(2).joinToString("/")
            parts.size >= 3 && parts[0] == "mnt" && parts[1] == "media_rw" -> "/" + parts.take(3).joinToString("/")
            else -> "/"
        }
    }

    private fun repeatOf(h: FolderHistory?): Int = if (h?.shuffle == true) h.repeat else 0

    /** True when [folder]'s book was heard to the end: Play asks *Start over?* first (todo §3.4). */
    fun isFinished(folder: String, history: HistoryStore): Boolean = history.folder(folder)?.finished == true

    /** The queue in play order: shuffled in music mode, from the saved seed and pinned file. */
    private fun ordered(queue: List<FileEntry>, h: FolderHistory?): List<FileEntry> =
        if (h?.shuffle == true) ShuffleOrder.of(queue, h.seed, h.first) else queue

    /**
     * Continue where the book stopped, with smart rewind (books only: music resumes where it
     * stopped). A finished book, or one whose current file is gone, starts from the beginning.
     */
    fun resume(folder: String, history: HistoryStore, legacyCharset: Charset): ResumePoint? {
        val plain = queue(folder, legacyCharset)
        if (plain.isEmpty()) return null
        val h = history.folder(folder)?.takeIf { it.currentPath != null || it.finished } ?: adoptMoved(folder, plain, history) ?: history.folder(folder)
        val music = h?.shuffle == true
        val queue = ordered(plain, h)
        if (h == null || h.finished) {
            if (h?.finished == true) history.reset(folder)
            return ResumePoint(folder, queue, 0, 0, h?.speed ?: 1f, music, repeatOf(h))
        }
        val index = queue.indexOfFirst { it.path == h.currentPath }
        if (index < 0) return ResumePoint(folder, queue, 0, 0, h.speed, music, repeatOf(h))
        val back = if (music) 0 else Book.rewindMs(System.currentTimeMillis() - h.playedAt)
        return ResumePoint(folder, queue, index, (h.positionMs - back).coerceAtLeast(0), h.speed, music, repeatOf(h))
    }

    /**
     * A tap on one file (todo §4.1): the folder's queue from that file — at its saved position in
     * book mode, from the start in music mode or when it was heard to the end. Null when [path] isn't playable.
     */
    fun startAt(path: String, history: HistoryStore, legacyCharset: Charset): ResumePoint? {
        val folder = File(path).parent ?: return null
        val h = history.folder(folder)
        val queue = ordered(queue(folder, legacyCharset), h)
        val index = queue.indexOfFirst { it.path == path }
        if (index < 0) return null
        val music = h?.shuffle == true
        val saved = if (music) null else history.files(folder)[path]?.takeIf { !it.finished }
        if (h?.finished == true) history.markBookFinished(folder, false)
        return ResumePoint(folder, queue, index, saved?.positionMs ?: 0, h?.speed ?: 1f, music, repeatOf(h))
    }
}
