package com.mediaviewer.data.thumbs

import com.mediaviewer.data.tags.ByteSource
import com.mediaviewer.domain.NaturalOrder
import java.util.Base64
import java.util.zip.Inflater

/** One file inside a ZIP. */
class ZipEntry(val name: String, val method: Int, val compressedSize: Long, val size: Long, val localOffset: Long)

/**
 * The entries of a ZIP (EPUB, CBZ, ODF and OOXML documents, FB2.ZIP), read from its central
 * directory, and single entries read out, stored or deflated (todo §1.0). Every size is checked
 * against the file and a cap before anything is allocated; ZIP64 and encrypted entries are left
 * out. Pure Kotlin (JVM).
 */
class ZipIndex private constructor(private val src: ByteSource, val entries: List<ZipEntry>) {
    private val byName = entries.associateBy { it.name }

    fun find(name: String): ZipEntry? = byName[name] ?: entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The entry's bytes, or null when it is larger than [max], broken or compressed some other way. */
    fun read(e: ZipEntry, max: Int = MAX_ENTRY): ByteArray? {
        if (e.size > max || e.compressedSize > MAX_COMPRESSED) return null
        val h = bytes(e.localOffset, 30) ?: return null
        if (u32(h, 0) != LOCAL_SIG) return null
        val data = e.localOffset + 30 + u16(h, 26) + u16(h, 28)
        val packed = bytes(data, e.compressedSize) ?: return null
        return when (e.method) {
            0 -> packed.takeIf { it.size.toLong() == e.size }
            8 -> inflate(packed, e.size.toInt())
            else -> null
        }
    }

    fun readText(name: String, max: Int = MAX_ENTRY): String? = find(name)?.let { read(it, max) }?.let { String(it, Charsets.UTF_8) }

    private fun inflate(packed: ByteArray, size: Int): ByteArray? {
        val inf = Inflater(true)
        try {
            inf.setInput(packed)
            val out = ByteArray(size)
            var n = 0
            while (n < size) {
                val k = inf.inflate(out, n, size - n)
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            return if (n == size) out else null
        } catch (e: java.util.zip.DataFormatException) {
            return null
        } finally {
            inf.end()
        }
    }

    private fun bytes(pos: Long, len: Long): ByteArray? = Companion.bytes(src, pos, len)

    companion object {
        /** A cover or a thumbnail is a few MB at most; a document's main XML can be larger. */
        const val MAX_ENTRY = 24 * 1024 * 1024
        private const val MAX_COMPRESSED = 64L * 1024 * 1024
        private const val MAX_CD = 16 * 1024 * 1024
        private const val MAX_ENTRIES = 100_000
        private const val EOCD_SIG = 0x06054b50L
        private const val CD_SIG = 0x02014b50L
        private const val LOCAL_SIG = 0x04034b50L

        /** The index of [src], or null when it isn't a ZIP this reader understands. */
        fun open(src: ByteSource): ZipIndex? = try {
            read(src)
        } catch (e: Exception) {
            null
        }

        private fun read(src: ByteSource): ZipIndex? {
            // The end record is in the last 22 bytes, plus a comment of up to 64 KB.
            val tailLen = minOf(src.size, 22L + 65_535)
            val tail = bytes(src, src.size - tailLen, tailLen) ?: return null
            var at = -1
            for (i in tail.size - 22 downTo 0) {
                if (u32(tail, i) == EOCD_SIG) {
                    at = i
                    break
                }
            }
            if (at < 0) return null
            val count = u16(tail, at + 10)
            val cdSize = u32(tail, at + 12)
            val cdOffset = u32(tail, at + 16)
            if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return null // ZIP64
            if (cdSize > MAX_CD || count > MAX_ENTRIES) return null
            val cd = bytes(src, cdOffset, cdSize) ?: return null
            val out = ArrayList<ZipEntry>(count)
            var p = 0
            repeat(count) {
                if (p + 46 > cd.size || u32(cd, p) != CD_SIG) return@repeat
                val flags = u16(cd, p + 8)
                val method = u16(cd, p + 10)
                val comp = u32(cd, p + 20)
                val size = u32(cd, p + 24)
                val nameLen = u16(cd, p + 28)
                val extraLen = u16(cd, p + 30)
                val commentLen = u16(cd, p + 32)
                val local = u32(cd, p + 42)
                if (p + 46 + nameLen > cd.size) return@repeat
                // Bit 11: UTF-8 names; old archives use the DOS code page, close enough for ASCII names.
                val name = String(cd, p + 46, nameLen, if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
                if (flags and 1 == 0 && !name.endsWith("/")) out.add(ZipEntry(name, method, comp, size, local))
                p += 46 + nameLen + extraLen + commentLen
            }
            return ZipIndex(src, out)
        }

        internal fun bytes(src: ByteSource, pos: Long, len: Long): ByteArray? {
            if (len < 0 || len > Int.MAX_VALUE || pos < 0 || pos + len > src.size) return null
            val b = ByteArray(len.toInt())
            return if (src.read(pos, b) == b.size) b else null
        }

        internal fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        internal fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)
    }
}

/**
 * Where the picture of a document or book is (todo §1.0, README §3.10): EPUB, CBZ, ODF and
 * OOXML covers and thumbnails, FB2 cover images, and the text of a DOCX for a text preview.
 * Pure Kotlin.
 */
object DocCovers {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val ATTR = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isImage(name: String) = ext(name) in IMAGE_EXT

    private fun attrs(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { m -> m.groupValues[1].lowercase() to (m.groups[2]?.value ?: m.groups[3]?.value ?: "") }

    private fun tags(xml: String, name: String): Sequence<Map<String, String>> =
        Regex("""<(?:[\w-]+:)?$name\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(xml).map { attrs(it.value) }

    /** The zip entry that is the cover of an EPUB, CBZ, ODF or OOXML file named [fileName]; null when there is none. */
    fun coverEntry(fileName: String, zip: ZipIndex): String? = when (ext(fileName)) {
        "epub" -> epubCover(zip)
        "cbz" -> cbzCover(zip)
        "odt", "ods", "odp", "odg" -> zip.find("Thumbnails/thumbnail.png")?.name
        "docx", "xlsx", "pptx" -> zip.entries.firstOrNull { it.name.lowercase().startsWith("docprops/thumbnail.") && isImage(it.name) }?.name
        else -> null
    }

    /** CBZ: the first image in natural order (folders inside count by their full path). */
    fun cbzCover(zip: ZipIndex): String? =
        zip.entries.map { it.name }.filter { isImage(it) && !it.substringAfterLast('/').startsWith(".") && !it.startsWith("__MACOSX/") }
            .minWithOrNull(NaturalOrder)

    /**
     * EPUB: `META-INF/container.xml` names the OPF; in it the cover is the manifest item with
     * `properties="cover-image"` (EPUB 3), the item a `<meta name="cover">` points at (EPUB 2), an
     * image item whose id or href says *cover*, or else the first image.
     */
    fun epubCover(zip: ZipIndex): String? {
        val container = zip.readText("META-INF/container.xml", 256 * 1024) ?: return null
        val opfPath = tags(container, "rootfile").firstNotNullOfOrNull { it["full-path"] } ?: return null
        val opf = zip.readText(opfPath, 4 * 1024 * 1024) ?: return null
        val dir = opfPath.substringBeforeLast('/', "")
        val items = tags(opf, "item").toList()
        fun image(i: Map<String, String>) = (i["media-type"] ?: "").startsWith("image/") || isImage(i["href"] ?: "")
        val coverId = tags(opf, "meta").firstOrNull { it["name"].equals("cover", ignoreCase = true) }?.get("content")
        val item = items.firstOrNull { (it["properties"] ?: "").split(' ').contains("cover-image") }
            ?: items.firstOrNull { coverId != null && it["id"] == coverId && image(it) }
            ?: items.firstOrNull { image(it) && ((it["id"] ?: "").contains("cover", true) || (it["href"] ?: "").contains("cover", true)) }
            ?: items.firstOrNull(::image)
            ?: return null
        val href = java.net.URLDecoder.decode((item["href"] ?: return null).replace("+", "%2B"), "UTF-8")
        return resolve(dir, href).takeIf { zip.find(it) != null }
    }

    /** [href] relative to [dir], with `..` and `.` resolved. */
    internal fun resolve(dir: String, href: String): String {
        val parts = ArrayList<String>()
        for (seg in (if (dir.isEmpty()) href else "$dir/$href").split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    /**
     * FB2: `<coverpage><image l:href="#id"/>` → `<binary id="id">` (base64). Read as Latin-1:
     * the tags and base64 are ASCII whatever the book's encoding.
     */
    fun fb2Cover(bytes: ByteArray): ByteArray? {
        val xml = String(bytes, Charsets.ISO_8859_1)
        val page = Regex("""<coverpage>(.*?)</coverpage>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(xml)?.groupValues?.get(1) ?: return null
        val id = tags(page, "image").firstNotNullOfOrNull { a -> a.entries.firstOrNull { it.key.endsWith("href") }?.value }?.removePrefix("#") ?: return null
        val binary = Regex("""<binary\b([^>]*)>(.*?)</binary>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml).firstOrNull { attrs(it.groupValues[1])["id"] == id } ?: return null
        return try {
            // The MIME decoder skips characters it doesn't know: junk comes back empty, not as an error.
            Base64.getMimeDecoder().decode(binary.groupValues[2].trim()).takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** DOCX: the text of `word/document.xml`, one line per paragraph, up to [max] characters. */
    fun docxText(xml: String, max: Int = 4_000): String {
        val out = StringBuilder()
        val para = Regex("""<w:p\b[^>]*?(?:/>|>(.*?)</w:p>)""", RegexOption.DOT_MATCHES_ALL)
        val run = Regex("""<w:t\b[^>]*>([^<]*)</w:t>|<w:tab\s*/>|<w:br\s*/>""")
        for (p in para.findAll(xml)) {
            val body = p.groupValues[1]
            for (r in run.findAll(body)) {
                when {
                    r.value.startsWith("<w:tab") -> out.append('\t')
                    r.value.startsWith("<w:br") -> out.append('\n')
                    else -> out.append(unescape(r.groupValues[1]))
                }
            }
            out.append('\n')
            if (out.length >= max) break
        }
        return out.toString().take(max).trimEnd()
    }

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
