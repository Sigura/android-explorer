package com.mediaviewer.data.fs

import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import java.io.File
import java.io.RandomAccessFile

/**
 * File type detection (README §3): extension first, then a 200-byte magic sniff when the extension
 * is missing or junk (`name.webm'`, `name.php?id=300737`, `name.mp4,gif`). The text check for
 * previews (README §3.10) needs more bytes and runs later, in `TextPreview`, when a tile is drawn.
 */
object FileTypes {
    data class Kind(val type: MediaType, val other: OtherKind = OtherKind.UNKNOWN)

    private val IMAGE = Kind(MediaType.IMAGE)
    private val ANIMATED = Kind(MediaType.ANIMATED)
    private val VIDEO = Kind(MediaType.VIDEO)
    private val PARTIAL = Kind(MediaType.PARTIAL)
    private fun other(k: OtherKind) = Kind(MediaType.OTHER, k)

    /** Plain text and data formats with a proportional or monospace preview (README §3.10). */
    private val TEXT_EXT = listOf(
        "txt", "text", "md", "markdown", "rst", "log", "csv", "tsv", "json", "jsonl", "ipynb", "xml", "yaml", "yml",
        "toml", "ini", "conf", "cfg", "properties", "env", "srt", "vtt", "ass", "ssa", "lrc", "nfo", "cue", "m3u", "m3u8",
        "diff", "patch", "html", "htm",
    )

    /** Source code: monospace preview. */
    private val CODE_EXT = listOf(
        "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm", "cs", "java", "kt", "kts", "scala", "groovy", "gradle", "go",
        "rs", "swift", "dart", "py", "rb", "php", "pl", "pm", "lua", "r", "js", "mjs", "cjs", "tsx", "jsx", "vue",
        "svelte", "css", "scss", "less", "sql", "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "asm", "s", "make",
        "mk", "cmake", "dockerfile",
    )

    private val byExt: Map<String, Kind> = buildMap {
        listOf("jpg", "jpeg", "jpe", "png", "bmp", "heic", "heif", "avif", "webp", "apng").forEach { put(it, IMAGE) }
        put("gif", ANIMATED)
        listOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "3g2", "ts", "mts", "m2ts").forEach { put(it, VIDEO) }
        listOf("crdownload", "part", "partial", "download").forEach { put(it, PARTIAL) }
        TEXT_EXT.forEach { put(it, other(OtherKind.TEXT)) }
        CODE_EXT.forEach { put(it, other(OtherKind.CODE)) }
        listOf("doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "odg").forEach { put(it, other(OtherKind.DOCUMENT)) }
        listOf("epub", "fb2", "cbz", "cbr", "mobi", "azw3", "djvu").forEach { put(it, other(OtherKind.BOOK)) }
        listOf("svg", "svgz").forEach { put(it, other(OtherKind.VECTOR)) }
        put("pdf", other(OtherKind.PDF))
        listOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka", "aiff", "aif", "ape", "wv", "amr", "mpc")
            .forEach { put(it, other(OtherKind.AUDIO)) }
        listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst").forEach { put(it, other(OtherKind.ARCHIVE)) }
        listOf("apk", "apks", "xapk").forEach { put(it, other(OtherKind.APK)) }
    }

    /** The last extension, lowercased, or null if there is none or it isn't a plain extension. */
    fun extension(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val ext = name.substring(dot + 1)
        if (ext.length > 10 || !ext.all { it.isLetterOrDigit() }) return null
        return ext.lowercase()
    }

    /** Well-known file names without a (useful) extension. */
    private val byName: Map<String, Kind> = buildMap {
        listOf("makefile", "gnumakefile", "dockerfile", "cmakelists.txt", "jenkinsfile", ".gitignore", ".gitattributes", ".editorconfig", ".bashrc", ".profile")
            .forEach { put(it, other(OtherKind.CODE)) }
        listOf("readme", "license", "licence", "copying", "changelog", "authors", "todo", "notice").forEach { put(it, other(OtherKind.TEXT)) }
    }

    /** Fast path: extension or well-known name only. Null means "sniff the content". */
    fun byExtension(name: String): Kind? =
        byName[name.lowercase()] ?: if (name.endsWith(".fb2.zip", ignoreCase = true)) other(OtherKind.BOOK) else extension(name)?.let { byExt[it] }

    fun detect(file: File): Kind {
        val kind = byExtension(file.name) ?: return sniff(file)
        // `.ts` is MPEG-TS video or TypeScript: only the content tells.
        if (extension(file.name) == "ts") return sniff(file).takeIf { it.type == MediaType.VIDEO } ?: other(OtherKind.CODE)
        return kind
    }

    /** Magic-byte check: one small read. Never throws. */
    fun sniff(file: File): Kind {
        // 200 bytes: enough for a second MPEG-TS sync byte; still a single small read.
        val h = ByteArray(200)
        val n = try {
            RandomAccessFile(file, "r").use { it.read(h) }
        } catch (e: Exception) {
            return other(OtherKind.UNKNOWN)
        }
        if (n < 4) return other(OtherKind.UNKNOWN)
        fun at(off: Int, s: String) = n >= off + s.length && s.indices.all { h[off + it] == s[it].code.toByte() }
        fun u(i: Int) = h[i].toInt() and 0xff
        return when {
            at(0, "GIF8") -> ANIMATED
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> IMAGE
            u(0) == 0x89 && at(1, "PNG") -> IMAGE
            at(0, "RIFF") && at(8, "WEBP") -> IMAGE
            at(0, "RIFF") && at(8, "AVI ") -> VIDEO
            at(0, "BM") -> IMAGE
            at(4, "ftyp") -> {
                val brand = String(h, 8, 4, Charsets.US_ASCII)
                when (brand) {
                    in setOf("heic", "heix", "hevc", "mif1", "msf1", "avif", "avis") -> IMAGE
                    // MP4 audio and audiobooks (iTunes brands); without them an `.m4b` would list as video.
                    in setOf("M4A ", "M4B ", "M4P ") -> other(OtherKind.AUDIO)
                    else -> VIDEO
                }
            }
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> VIDEO
            n >= 189 && u(0) == 0x47 && u(188) == 0x47 -> VIDEO // MPEG-TS, 188-byte packets
            n >= 197 && u(4) == 0x47 && u(196) == 0x47 -> VIDEO // M2TS, 192-byte packets
            at(0, "%PDF") -> other(OtherKind.PDF)
            at(0, "<") || at(0, "{") -> other(OtherKind.TEXT)
            at(0, "PK") -> other(OtherKind.ARCHIVE)
            at(0, "ID3") || at(0, "fLaC") || at(0, "OggS") -> other(OtherKind.AUDIO)
            else -> other(OtherKind.UNKNOWN)
        }
    }

    fun mimeType(name: String, type: MediaType): String {
        val ext = extension(name)
        return when (type) {
            MediaType.ANIMATED -> "image/gif"
            MediaType.IMAGE -> when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heif"
                "avif" -> "image/avif"
                "bmp" -> "image/bmp"
                else -> "image/jpeg"
            }
            MediaType.VIDEO -> when (ext) {
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "3gp" -> "video/3gpp"
                else -> "video/mp4"
            }
            else -> ext?.let { OTHER_MIME[it] } ?: "*/*"
        }
    }

    /** Monospace preview: code, logs and tables, where columns line up. */
    fun monospace(name: String, kind: OtherKind): Boolean =
        kind == OtherKind.CODE || extension(name) in setOf("log", "csv", "tsv", "json", "jsonl", "ipynb", "xml", "yaml", "yml", "diff", "patch", "srt", "vtt", "ass", "ssa", "lrc", "cue", "m3u", "m3u8", "toml", "ini", "conf", "cfg", "properties", "env")

    /** MIME types for other files, so `ACTION_VIEW` offers the right apps (README §3.11). */
    private val OTHER_MIME: Map<String, String> = buildMap {
        listOf("txt", "log", "md", "csv", "sh", "py", "ini", "conf", "cfg", "srt", "vtt", "lrc", "nfo").forEach { put(it, "text/plain") }
        put("csv", "text/csv")
        put("html", "text/html")
        put("htm", "text/html")
        put("xml", "text/xml")
        put("json", "application/json")
        put("ipynb", "application/x-ipynb+json")
        put("pdf", "application/pdf")
        put("doc", "application/msword")
        put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        put("xls", "application/vnd.ms-excel")
        put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        put("ppt", "application/vnd.ms-powerpoint")
        put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        put("odt", "application/vnd.oasis.opendocument.text")
        put("ods", "application/vnd.oasis.opendocument.spreadsheet")
        put("odp", "application/vnd.oasis.opendocument.presentation")
        put("cbz", "application/vnd.comicbook+zip")
        put("cbr", "application/vnd.comicbook-rar")
        put("mobi", "application/x-mobipocket-ebook")
        put("djvu", "image/vnd.djvu")
        put("svg", "image/svg+xml")
        put("svgz", "image/svg+xml")
        put("epub", "application/epub+zip")
        put("fb2", "application/x-fictionbook+xml")
        put("mp3", "audio/mpeg")
        put("m4a", "audio/mp4")
        put("m4b", "audio/mp4")
        put("aac", "audio/aac")
        put("flac", "audio/flac")
        put("ogg", "audio/ogg")
        put("opus", "audio/ogg")
        put("wav", "audio/wav")
        put("wma", "audio/x-ms-wma")
        put("zip", "application/zip")
        put("rar", "application/vnd.rar")
        put("7z", "application/x-7z-compressed")
        put("tar", "application/x-tar")
        put("gz", "application/gzip")
        put("apk", "application/vnd.android.package-archive")
    }
}
