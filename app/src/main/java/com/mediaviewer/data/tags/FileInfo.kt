package com.mediaviewer.data.tags

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.mediaviewer.data.abandonable
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The player screen's ⋮ *File info* (todo §4.6): name, folder, size and date, then what the
 * retriever and the extractor say about the stream — format, codec, bitrate, sample rate,
 * channels, duration — and the tags. Each line is a label and a value; missing ones are left out.
 */
object FileInfo {
    private const val READ_MS = 8_000L

    suspend fun read(path: String): List<Pair<String, String>> {
        val f = File(path)
        val out = ArrayList<Pair<String, String>>()
        out += "Name" to f.name
        out += "Folder" to (f.parent ?: "")
        abandonable(READ_MS) { f.length() to f.lastModified() }?.let { (size, time) ->
            out += "Size" to sizeText(size)
            if (time > 0) out += "Modified" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
        }
        abandonable(READ_MS) { retriever(path) }?.let { out += it }
        abandonable(READ_MS) { extractor(path) }?.let { out += it }
        return out
    }

    private fun retriever(path: String): List<Pair<String, String>> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            fun key(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            val out = ArrayList<Pair<String, String>>()
            key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { out += "Duration" to duration(it) }
            key(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { out += "Format" to it }
            key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { out += "Bitrate" to "${it / 1000} kbit/s" }
            if (Build.VERSION.SDK_INT >= 31) {
                key(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.let { out += "Sample rate" to "$it Hz" }
                key(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.let { out += "Bits per sample" to it }
            }
            listOf(
                MediaMetadataRetriever.METADATA_KEY_TITLE to "Title",
                MediaMetadataRetriever.METADATA_KEY_ARTIST to "Artist",
                MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to "Album artist",
                MediaMetadataRetriever.METADATA_KEY_ALBUM to "Album",
                MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER to "Track",
                MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER to "Disc",
                MediaMetadataRetriever.METADATA_KEY_YEAR to "Year",
                MediaMetadataRetriever.METADATA_KEY_GENRE to "Genre",
                MediaMetadataRetriever.METADATA_KEY_COMPOSER to "Composer",
                MediaMetadataRetriever.METADATA_KEY_WRITER to "Writer",
            ).forEach { (k, label) -> key(k)?.let { out += label to it } }
            return out
        } finally {
            r.release()
        }
    }

    /** The audio track's codec and channels (the retriever has no key for those). */
    private fun extractor(path: String): List<Pair<String, String>> {
        val x = MediaExtractor()
        try {
            x.setDataSource(path)
            for (i in 0 until x.trackCount) {
                val f = x.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val out = ArrayList<Pair<String, String>>()
                out += "Codec" to mime.removePrefix("audio/")
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    out += "Channels" to when (val n = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) {
                        1 -> "mono"
                        2 -> "stereo"
                        else -> "$n"
                    }
                }
                if (Build.VERSION.SDK_INT < 31 && f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    out += "Sample rate" to "${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz"
                }
                return out
            }
            return emptyList()
        } finally {
            x.release()
        }
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }
}
