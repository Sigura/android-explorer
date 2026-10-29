package com.mediaviewer.data.tags

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import com.mediaviewer.data.abandonable
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.thumbs.TextDecode
import com.mediaviewer.domain.Chapter
import com.mediaviewer.domain.Chapters
import com.mediaviewer.domain.Companions
import com.mediaviewer.domain.CueSheet
import com.mediaviewer.domain.Lyrics
import com.mediaviewer.domain.model.MediaType
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

/** What the player screen shows beside the controls for one file (todo §4.1, §4.4, §4.5). */
data class TrackExtras(
    val path: String,
    val chapters: List<Chapter> = emptyList(),
    val lyrics: Lyrics? = null,
    /** Title tag, when there is one and it differs from the file name. */
    val tagTitle: String? = null,
    val cover: Bitmap? = null,
)

/**
 * Reads a file's [TrackExtras] on IO: chapters (embedded, else a `.cue` sheet in the folder),
 * lyrics or subtitles (a companion `.lrc` / `.srt` / `.vtt`, else embedded lyrics, else a video's
 * own subtitle track), the title tag and the cover (embedded picture, else the image named like
 * the file, else for videos a frame at 10 %, else the folder's cover image). Each slow step runs
 * under a watchdog (a stuck USB drive) and gives nothing on failure.
 */
object TrackExtrasLoader {
    private const val READ_MS = 8_000L
    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    private const val COVER_PX = 1024

    suspend fun load(path: String, durationMs: Long, legacyCharset: Charset): TrackExtras {
        val file = File(path)
        val names = abandonable(READ_MS) { file.parentFile?.list()?.filterNot(FolderScanner::isHidden) } ?: emptyList()
        val chapters = abandonable(READ_MS) { chaptersOf(file, names, durationMs, legacyCharset) } ?: emptyList()
        val lyrics = abandonable(READ_MS) { lyricsOf(file, names, legacyCharset) }
        val tags = abandonable(READ_MS) { retrieverTags(file, COVER_PX, frame = false) }
        val cover = coverOf(file, names, COVER_PX, tags)
        val title = tags?.title?.trim()?.takeIf { it.isNotEmpty() && it != file.nameWithoutExtension && it != file.name }
        return TrackExtras(path, chapters, lyrics, title, cover)
    }

    private fun isVideo(file: File) = FileTypes.byExtension(file.name)?.type == MediaType.VIDEO

    /**
     * The cover of [file] at about [px]: its embedded picture, the image named like it (`yt-dlp`
     * thumbnails), a frame at 10 % for videos (todo §3.6), then the folder's cover image.
     */
    private suspend fun coverOf(file: File, names: List<String>, px: Int, read: Tags? = null): Bitmap? {
        (read ?: abandonable(READ_MS) { retrieverTags(file, px, frame = false) })?.cover?.let { return it }
        val base = file.nameWithoutExtension.lowercase()
        val own = Companions.coverFor(names.filter { it.substringBeforeLast('.').lowercase() == base }, file.name)
        if (own != null) abandonable(READ_MS) { decodeFile(File(file.parentFile, own), px) }?.let { return it }
        if (isVideo(file)) abandonable(READ_MS) { retrieverTags(file, px, frame = true) }?.cover?.let { return it }
        return abandonable(READ_MS) { folderCover(file, names, px) }
    }

    /**
     * A small cover for a book card (*Continue listening*, todo §3.4): [path]'s own picture, else
     * the folder's cover image, decoded at about [px]. Null when there is none.
     */
    suspend fun cover(path: String, px: Int): Bitmap? {
        val file = File(path)
        val names = abandonable(READ_MS) { file.parentFile?.list()?.filterNot(FolderScanner::isHidden) } ?: emptyList()
        return coverOf(file, names, px)
    }

    private fun chaptersOf(file: File, names: List<String>, durationMs: Long, legacyCharset: Charset): List<Chapter> {
        val embedded = Chapters.normalize(MediaTags.chapters(file), durationMs)
        if (embedded.isNotEmpty()) return embedded
        for (cue in names.filter { it.endsWith(".cue", ignoreCase = true) }) {
            val text = readText(File(file.parentFile, cue), legacyCharset) ?: continue
            val chapters = Chapters.normalize(CueSheet.chaptersFor(CueSheet.parse(text), cue, file.name), durationMs)
            if (chapters.isNotEmpty()) return chapters
        }
        return emptyList()
    }

    private fun lyricsOf(file: File, names: List<String>, legacyCharset: Charset): Lyrics? {
        Companions.textFor(file.name, names, Locale.getDefault().language)?.let { name ->
            val text = readText(File(file.parentFile, name), legacyCharset)
            if (text != null) {
                val l = if (name.endsWith(".lrc", ignoreCase = true)) Lyrics.parseLrc(text) ?: Lyrics.plain(text) else Lyrics.parseSubtitles(text)
                if (l != null) return l
            }
        }
        MediaTags.lyrics(file)?.let(Lyrics::fromText)?.let { return it }
        // A lecture's own subtitle track (MKV / MP4, todo §4.6).
        if (!isVideo(file)) return null
        val lines = MediaTags.subtitles(file, Locale.getDefault().language)
        return if (lines.isEmpty()) null else Lyrics(lines, synced = true)
    }

    private fun readText(f: File, legacyCharset: Charset): String? = try {
        if (f.length() > MAX_TEXT_BYTES) {
            null
        } else {
            val bytes = f.readBytes()
            TextDecode.decode(bytes, bytes.size, legacyCharset, TextDecode.cyrillicDetector)
        }
    } catch (e: Exception) {
        null
    }

    private class Tags(val title: String?, val cover: Bitmap?)

    /**
     * Title tag and, when [px] > 0, the embedded picture; with [frame], a video frame at 10 % (the
     * nearest key frame) instead. One retriever per call.
     */
    private fun retrieverTags(file: File, px: Int, frame: Boolean): Tags {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val cover = when {
                px <= 0 -> null
                frame -> {
                    val us = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 100
                    if (Build.VERSION.SDK_INT >= 27) {
                        r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px)
                    } else {
                        r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                }
                else -> r.embeddedPicture?.let { decodeSampled(it, px) }
            }
            return Tags(title, cover)
        } finally {
            r.release()
        }
    }

    private fun decodeFile(f: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, px) })
    }

    /** The folder's cover image (`cover` / `folder` / `front`, `AlbumArt…`, the only image). */
    private fun folderCover(file: File, names: List<String>, px: Int): Bitmap? {
        val name = Companions.coverFor(names) ?: return null
        return decodeFile(File(file.parentFile, name), px)
    }

    private fun decodeSampled(bytes: ByteArray, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, px) })
    }

    private fun sample(w: Int, h: Int, px: Int): Int {
        var s = 1
        while (w / (s * 2) >= px && h / (s * 2) >= px) s *= 2
        return s
    }
}
