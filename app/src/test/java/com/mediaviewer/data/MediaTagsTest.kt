package com.mediaviewer.data

import com.mediaviewer.data.tags.ArraySource
import com.mediaviewer.data.tags.MediaTags
import com.mediaviewer.domain.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Chapters and lyrics read from MP3, MP4 / M4B and Matroska files built in memory (todo §4.4, §4.5). */
class MediaTagsTest {
    // ------------------------------------------------------------------ byte helpers

    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be64(v: Long) = be32(v ushr 32) + be32(v and 0xFFFFFFFFL)
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun syncsafe(v: Int) = byteArrayOf((v ushr 21 and 0x7F).toByte(), (v ushr 14 and 0x7F).toByte(), (v ushr 7 and 0x7F).toByte(), (v and 0x7F).toByte())
    private fun cat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    // ------------------------------------------------------------------ ID3

    private fun frame(id: String, body: ByteArray, v4: Boolean) =
        cat(ascii(id), if (v4) syncsafe(body.size) else be32(body.size.toLong()), be16(0), body)

    private fun tit2(title: String, v4: Boolean) = frame("TIT2", cat(byteArrayOf(3), title.toByteArray(Charsets.UTF_8)), v4)

    private fun chap(id: String, startMs: Long, endMs: Long, title: String, v4: Boolean) =
        frame("CHAP", cat(ascii(id), byteArrayOf(0), be32(startMs), be32(endMs), be32(0xFFFFFFFFL), be32(0xFFFFFFFFL), tit2(title, v4)), v4)

    private fun id3(major: Int, frames: ByteArray, padding: Int = 64): ByteArray {
        val body = cat(frames, ByteArray(padding))
        return cat(ascii("ID3"), byteArrayOf(major.toByte(), 0, 0), syncsafe(body.size), body, byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0))
    }

    @Test fun id3ChaptersV23AndV24() {
        for (v4 in listOf(false, true)) {
            val file = id3(
                if (v4) 4 else 3,
                cat(
                    frame("TIT2", cat(byteArrayOf(0), ascii("Book")), v4),
                    chap("ch1", 90_000, 200_000, "Part Two", v4),
                    chap("ch0", 0, 90_000, "Опенинг", v4),
                ),
            )
            val chapters = MediaTags.chapters(ArraySource(file))
            assertEquals("v2.${if (v4) 4 else 3}", listOf(Chapter(0, "Опенинг"), Chapter(90_000, "Part Two")), chapters)
        }
    }

    @Test fun id3V24WithPlainFrameSizes() {
        // iTunes wrote plain sizes in v2.4 tags: a 200-byte frame has 0xC8 in its last size byte.
        val longTitle = "x".repeat(190)
        val body = cat(byteArrayOf(3), longTitle.toByteArray())
        val frames = cat(ascii("TIT2"), be32(body.size.toLong()), be16(0), body, frame("USLT", cat(byteArrayOf(3), ascii("eng"), byteArrayOf(0), ascii("words")), v4 = true))
        assertEquals("words", MediaTags.lyrics(ArraySource(id3(4, frames))))
    }

    @Test fun id3LyricsInUtf16() {
        val text = "[00:01.00]Hello\n[00:02.00]Мир"
        val body = cat(
            byteArrayOf(1), ascii("eng"),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()), ascii("d\u0000"), byteArrayOf(0, 0), // description "d" + terminator
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()), text.toByteArray(Charsets.UTF_16LE),
        )
        assertEquals(text, MediaTags.lyrics(ArraySource(id3(3, frame("USLT", body, v4 = false)))))
    }

    @Test fun id3UnsynchronisedTag() {
        // Tag-level unsync (v2.3): every FF is followed by an inserted 00.
        val frames = chap("c", 0x00FF00L, 0x00FFFFL, "A", v4 = false) + chap("d", 0x01FF00L, 0x02FF00L, "B", v4 = false)
        val out = ByteArrayOutputStream()
        for (b in frames) {
            out.write(b.toInt())
            if (b == 0xFF.toByte()) out.write(0)
        }
        val body = cat(out.toByteArray(), ByteArray(16))
        val file = cat(ascii("ID3"), byteArrayOf(3, 0, 0x80.toByte()), syncsafe(body.size), body)
        assertEquals(listOf(Chapter(0x00FF00L, "A"), Chapter(0x01FF00L, "B")), MediaTags.chapters(ArraySource(file)))
    }

    @Test fun brokenId3GivesNothing() {
        val file = id3(3, chap("c", 0, 1000, "A", v4 = false))
        // A frame size larger than the tag.
        val broken = file.copyOf().also { it[10 + 4 + 3] = 0x7F }
        assertTrue(MediaTags.chapters(ArraySource(broken)).isEmpty())
        assertTrue(MediaTags.chapters(ArraySource(ascii("ID3"))).isEmpty())
        assertNull(MediaTags.lyrics(ArraySource(ByteArray(0))))
    }

    // ------------------------------------------------------------------ MP4

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val b = cat(*body)
        return cat(be32(8L + b.size), ascii(type), b)
    }

    private fun full(type: String, version: Int, vararg body: ByteArray) = box(type, byteArrayOf(version.toByte(), 0, 0, 0), *body)

    private val ftyp = box("ftyp", ascii("M4B "), be32(0), ascii("isomM4B "))

    private fun chpl(chapters: List<Chapter>): ByteArray {
        val entries = chapters.map { c -> cat(be64(c.startMs * 10_000), byteArrayOf(c.title.toByteArray().size.toByte()), c.title.toByteArray()) }
        return full("chpl", 1, be32(0), byteArrayOf(chapters.size.toByte()), *entries.toTypedArray())
    }

    @Test fun mp4NeroChapters() {
        val want = listOf(Chapter(0, "Intro"), Chapter(65_500, "Глава 1"), Chapter(3_600_000, "End"))
        val file = cat(ftyp, box("moov", box("udta", chpl(want))), box("mdat", ByteArray(100)))
        assertEquals(want, MediaTags.chapters(ArraySource(file)))
    }

    /** An M4B with a sound track that points at a text track holding [titles], each [durations] long (timescale 1000). */
    private fun quickTimeChapters(titles: List<String>, durations: List<Long>, samplesPerChunk: Int): ByteArray {
        val samples = titles.map { t -> val b = t.toByteArray(); cat(be16(b.size), b) }
        val chunks = samples.chunked(samplesPerChunk).map { cat(*it.toTypedArray()) }
        fun moov(mdatDataStart: Long): ByteArray {
            var off = mdatDataStart
            val offsets = chunks.map { c -> off.also { off += c.size } }
            val stbl = box(
                "stbl",
                full("stts", 0, be32(durations.size.toLong()), *durations.map { cat(be32(1), be32(it)) }.toTypedArray()),
                full("stsc", 0, be32(1), be32(1), be32(samplesPerChunk.toLong()), be32(1)),
                full("stsz", 0, be32(0), be32(samples.size.toLong()), *samples.map { be32(it.size.toLong()) }.toTypedArray()),
                full("co64", 0, be32(offsets.size.toLong()), *offsets.map { be64(it) }.toTypedArray()),
            )
            val sound = box(
                "trak",
                full("tkhd", 0, be32(0), be32(0), be32(1), ByteArray(60)),
                box("tref", box("chap", be32(2))),
                box("mdia", full("mdhd", 0, be32(0), be32(0), be32(44_100), be32(0))),
            )
            val text = box(
                "trak",
                full("tkhd", 1, be64(0), be64(0), be32(2), ByteArray(60)),
                box("mdia", full("mdhd", 0, be32(0), be32(0), be32(1000), be32(0)), box("minf", stbl)),
            )
            return box("moov", sound, text, box("udta", chpl(listOf(Chapter(0, "nero A"), Chapter(1, "nero B")))))
        }
        val moovSize = moov(0).size
        val mdatDataStart = ftyp.size + moovSize + 8L
        return cat(ftyp, moov(mdatDataStart), box("mdat", *chunks.toTypedArray()))
    }

    @Test fun mp4QuickTimeChapterTrack() {
        val titles = listOf("Opening", "Chapter One", "Chapter Two", "Credits")
        val durations = listOf(5_000L, 600_000L, 700_500L, 30_000L)
        for (perChunk in listOf(1, 3)) {
            val chapters = MediaTags.chapters(ArraySource(quickTimeChapters(titles, durations, perChunk)))
            assertEquals("samples per chunk $perChunk", titles, chapters.map { it.title })
            assertEquals(listOf(0L, 5_000L, 605_000L, 1_305_500L), chapters.map { it.startMs })
        }
    }

    @Test fun mp4Lyrics() {
        val data = box("data", be32(1), be32(0), "La la la".toByteArray())
        val ilst = box("ilst", box("©nam", box("data", be32(1), be32(0), ascii("Song"))), cat(be32(8L + data.size), byteArrayOf(0xA9.toByte()), ascii("lyr"), data))
        val file = cat(ftyp, box("moov", box("udta", full("meta", 0, box("hdlr", ByteArray(25)), ilst))))
        assertEquals("La la la", MediaTags.lyrics(ArraySource(file)))
    }

    @Test fun brokenMp4GivesNothing() {
        val file = cat(ftyp, box("moov", box("udta", chpl(listOf(Chapter(0, "A"), Chapter(10, "B"))))))
        val truncated = file.copyOf(file.size - 3)
        assertTrue(MediaTags.chapters(ArraySource(truncated)).isEmpty())
        // A huge box size.
        val huge = file.copyOf().also { it[ftyp.size] = 0x7F }
        assertTrue(MediaTags.chapters(ArraySource(huge)).isEmpty())
    }

    // ------------------------------------------------------------------ Matroska

    private fun ebmlId(id: Long): ByteArray = when {
        id > 0xFFFFFF -> be32(id)
        id > 0xFFFF -> byteArrayOf((id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
        id > 0xFF -> byteArrayOf((id ushr 8).toByte(), id.toByte())
        else -> byteArrayOf(id.toByte())
    }

    /** An element with an 8-byte size. */
    private fun el(id: Long, vararg body: ByteArray): ByteArray {
        val b = cat(*body)
        return cat(ebmlId(id), byteArrayOf(0x01), be64(b.size.toLong()).copyOfRange(1, 8), b)
    }

    private fun uintEl(id: Long, v: Long) = el(id, be64(v))

    private fun atom(startNs: Long, title: String, hidden: Boolean = false) = el(
        0xB6,
        uintEl(0x73C4, 1), uintEl(0x91, startNs),
        *(if (hidden) arrayOf(uintEl(0x98, 1)) else emptyArray()),
        el(0x80, el(0x85, title.toByteArray()), el(0x437C, ascii("eng"))),
    )

    private val ebmlHeader = el(0x1A45DFA3, el(0x4282, ascii("matroska")))

    private fun chaptersEl() = el(
        0x1043A770,
        el(0x45B9, uintEl(0x45BD, 1), atom(0, "hidden edition")),
        el(0x45B9, uintEl(0x45DB, 1), atom(0, "Start"), atom(90_500_000_000, "Middle"), atom(100_000_000_000, "secret", hidden = true), atom(45_000_000_000, "Early")),
    )

    @Test fun matroskaChaptersBeforeTheClusters() {
        val segment = el(0x18538067, el(0x1549A966, el(0x2AD7B1, be32(1_000_000))), chaptersEl(), el(0x1F43B675, ByteArray(50)))
        val chapters = MediaTags.chapters(ArraySource(cat(ebmlHeader, segment)))
        assertEquals(listOf(Chapter(0, "Start"), Chapter(45_000, "Early"), Chapter(90_500, "Middle")), chapters)
    }

    @Test fun matroskaChaptersFoundThroughTheSeekHead() {
        val info = el(0x1549A966, el(0x2AD7B1, be32(1_000_000)))
        val cluster = el(0x1F43B675, ByteArray(500))
        fun seekHead(pos: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, ebmlId(0x1043A770)), uintEl(0x53AC, pos)))
        val headSize = seekHead(0).size
        val chaptersPos = (headSize + info.size + cluster.size).toLong()
        // Unknown segment size (live recordings): all ones.
        val segmentBody = cat(seekHead(chaptersPos), info, cluster, chaptersEl())
        val segment = cat(ebmlId(0x18538067), byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1), segmentBody)
        val chapters = MediaTags.chapters(ArraySource(cat(ebmlHeader, segment)))
        assertEquals(listOf("Start", "Early", "Middle"), chapters.map { it.title })
    }

    @Test fun matroskaWithoutChapters() {
        val segment = el(0x18538067, el(0x1549A966), el(0x1F43B675, ByteArray(20)))
        assertTrue(MediaTags.chapters(ArraySource(cat(ebmlHeader, segment))).isEmpty())
        assertTrue(MediaTags.chapters(ArraySource(ebmlHeader.copyOf(6))).isEmpty())
    }

    // ------------------------------------------------------------------ SYLT, FLAC and Ogg comments

    @Test fun id3SyncedLyricsBecomeLrc() {
        fun line(text: String, ms: Long) = cat(text.toByteArray(Charsets.UTF_8), byteArrayOf(0), be32(ms))
        val body = cat(byteArrayOf(3), ascii("eng"), byteArrayOf(2, 1), ascii("desc"), byteArrayOf(0), line("First", 1_500), line("\nSecond", 62_250))
        val lrc = MediaTags.lyrics(ArraySource(id3(3, cat(frame("USLT", cat(byteArrayOf(3), ascii("eng"), byteArrayOf(0), ascii("plain")), v4 = false), frame("SYLT", body, v4 = false)))))
        assertEquals("[00:01.50]First\n[01:02.25]Second\n", lrc)
    }

    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun comments(vararg entries: String): ByteArray {
        val vendor = ascii("test")
        val parts = entries.map { e -> val b = e.toByteArray(Charsets.UTF_8); cat(le32(b.size.toLong()), b) }
        return cat(le32(vendor.size.toLong()), vendor, le32(entries.size.toLong()), *parts.toTypedArray())
    }

    private val vorbisFields = arrayOf(
        "TITLE=Book", "CHAPTER002=00:10:00.500", "CHAPTER001=00:00:00.000", "CHAPTER001NAME=Пролог", "CHAPTER002NAME=Part 2",
        "UNSYNCEDLYRICS=old", "lyrics=[00:01.00]a\n[00:02.00]b",
    )

    @Test fun flacComments() {
        val streamInfo = cat(byteArrayOf(0, 0, 0, 34), ByteArray(34))
        val c = comments(*vorbisFields)
        val block = cat(byteArrayOf(0x84.toByte()), be32(c.size.toLong()).copyOfRange(1, 4), c)
        val file = cat(ascii("fLaC"), streamInfo, block, ByteArray(100))
        assertEquals(listOf(Chapter(0, "Пролог"), Chapter(600_500, "Part 2")), MediaTags.chapters(ArraySource(file)))
        assertEquals("[00:01.00]a\n[00:02.00]b", MediaTags.lyrics(ArraySource(file)))
    }

    /** One Ogg page holding [segments] (each < 255 bytes ends a packet; 255 continues it). */
    private fun oggPage(serial: Long, seq: Long, data: ByteArray, lacing: List<Int>): ByteArray =
        cat(ascii("OggS"), byteArrayOf(0, 0), ByteArray(8), le32(serial), le32(seq), ByteArray(4), byteArrayOf(lacing.size.toByte()), lacing.map { it.toByte() }.toByteArray(), data)

    private fun lacing(len: Int, ends: Boolean): List<Int> = List(len / 255) { 255 } + if (ends) listOf(len % 255) else emptyList()

    @Test fun oggVorbisCommentsAcrossPages() {
        val ident = cat(byteArrayOf(1), ascii("vorbis"), ByteArray(23))
        // A long comment packet, split over two pages at a segment boundary.
        val comment = cat(byteArrayOf(3), ascii("vorbis"), comments(*vorbisFields, "PADDING=" + "x".repeat(600)), byteArrayOf(1))
        val first = comment.copyOfRange(0, 510)
        val rest = comment.copyOfRange(510, comment.size)
        val file = cat(
            oggPage(7, 0, ident, lacing(ident.size, true)),
            oggPage(99, 0, ByteArray(10), listOf(10)), // another stream's page in between
            oggPage(7, 1, first, List(2) { 255 }),
            oggPage(7, 2, rest, lacing(rest.size, true)),
        )
        assertEquals(listOf("Пролог", "Part 2"), MediaTags.chapters(ArraySource(file)).map { it.title })
    }

    @Test fun opusTags() {
        val head = cat(ascii("OpusHead"), ByteArray(11))
        val tags = cat(ascii("OpusTags"), comments("UNSYNCEDLYRICS=Just words"))
        val file = cat(oggPage(1, 0, head, listOf(head.size)), oggPage(1, 1, tags, lacing(tags.size, true)))
        assertEquals("Just words", MediaTags.lyrics(ArraySource(file)))
        assertTrue(MediaTags.chapters(ArraySource(file)).isEmpty())
    }

    // ------------------------------------------------------------------ subtitle tracks inside videos

    private fun mkvTrack(number: Int, type: Int, codec: String, lang: String?, default: Boolean = true) = el(
        0xAE,
        uintEl(0xD7, number.toLong()), uintEl(0x83, type.toLong()), el(0x86, ascii(codec)),
        *(if (lang != null) arrayOf(el(0x22B59C, ascii(lang))) else emptyArray()),
        uintEl(0x88, if (default) 1 else 0),
    )

    /** A block of track [track] at [tc] ms after the cluster's time. */
    private fun blockBody(track: Int, tc: Int, text: String) = cat(byteArrayOf((0x80 or track).toByte()), be16(tc), byteArrayOf(0), text.toByteArray(Charsets.UTF_8))

    @Test fun matroskaSubtitleTracks() {
        val tracks = el(
            0x1654AE6B,
            mkvTrack(1, 1, "V_MPEG4/ISO/AVC", null),
            mkvTrack(2, 2, "A_AAC", "eng"),
            mkvTrack(3, 17, "S_TEXT/UTF8", "rus", default = false),
            mkvTrack(4, 17, "S_TEXT/ASS", "eng"),
        )
        val c1 = el(
            0x1F43B675, uintEl(0xE7, 0),
            el(0xA3, blockBody(1, 0, "video bytes")),
            el(0xA3, blockBody(3, 1_000, "<i>Привет</i>")),
            el(0xA0, el(0xA1, blockBody(4, 1_000, "0,0,Default,,0,0,0,,{\\an8}Hello\\Nworld")), uintEl(0x9B, 2_500)),
            el(0xA3, blockBody(3, 9_000, "Пока")),
        )
        val c2 = el(0x1F43B675, uintEl(0xE7, 60_000), el(0xA0, el(0xA1, blockBody(4, -500, "1,0,Default,,0,0,0,,Bye")), uintEl(0x9B, 1_000)))
        val segment = el(0x18538067, el(0x1549A966, uintEl(0x2AD7B1, 1_000_000)), tracks, c1, c2)
        val file = ArraySource(cat(ebmlHeader, segment))

        val eng = MediaTags.subtitles(file, "en")
        assertEquals(listOf("Hello\nworld", "Bye"), eng.map { it.text })
        assertEquals(listOf(1_000L to 3_500L, 59_500L to 60_500L), eng.map { it.startMs to it.endMs })

        val rus = MediaTags.subtitles(file, "ru")
        assertEquals(listOf("Привет", "Пока"), rus.map { it.text })
        assertEquals("no duration: until the next line, 5 s at most", 6_000L, rus[0].endMs)
        assertEquals(14_000L, rus[1].endMs)

        assertEquals("the default track when the language isn't there", "Hello\nworld", MediaTags.subtitles(file, "de").first().text)
    }

    @Test fun matroskaWithoutTextSubtitles() {
        val tracks = el(0x1654AE6B, mkvTrack(1, 1, "V_VP9", null), mkvTrack(2, 17, "S_HDMV/PGS", "eng"))
        val segment = el(0x18538067, tracks, el(0x1F43B675, uintEl(0xE7, 0), el(0xA3, blockBody(2, 0, "bitmap"))))
        assertTrue(MediaTags.subtitles(ArraySource(cat(ebmlHeader, segment))).isEmpty())
    }

    @Test fun assCleaning() {
        assertEquals("a, b\nc", MediaTags.Ebml.clean("3,0,Default,,0,0,0,,{\\b1}a, b{\\b0}\\Nc", "S_TEXT/ASS"))
        assertEquals("plain", MediaTags.Ebml.clean("<b>plain</b>", "S_TEXT/UTF8"))
    }

    /** An MP4 with a `tx3g` subtitle track (language [lang]) and a chapter text track that must not be taken for it. */
    private fun mp4WithSubtitles(lines: List<Pair<String, Long>>, lang: String): ByteArray {
        val samples = lines.map { (t, _) -> val b = t.toByteArray(); cat(be16(b.size), b) }
        fun stbl(offsets: List<Long>, durations: List<Long>, sizes: List<Int>) = box(
            "stbl",
            full("stsd", 0, be32(1), box("tx3g", ByteArray(30))),
            full("stts", 0, be32(durations.size.toLong()), *durations.map { cat(be32(1), be32(it)) }.toTypedArray()),
            full("stsc", 0, be32(1), be32(1), be32(1), be32(1)),
            full("stsz", 0, be32(0), be32(sizes.size.toLong()), *sizes.map { be32(it.toLong()) }.toTypedArray()),
            full("stco", 0, be32(offsets.size.toLong()), *offsets.map { be32(it) }.toTypedArray()),
        )
        val l = lang.map { it.code - 0x60 }
        val packed = (l[0] shl 10) or (l[1] shl 5) or l[2]
        fun trak(id: Int, handler: String, langCode: Int, s: ByteArray, tref: ByteArray? = null) = box(
            "trak",
            full("tkhd", 0, be32(0), be32(0), be32(id.toLong()), ByteArray(60)),
            *(if (tref != null) arrayOf(tref) else emptyArray()),
            box("mdia", full("mdhd", 0, be32(0), be32(0), be32(1000), be32(0), be16(langCode), be16(0)), full("hdlr", 0, be32(0), ascii(handler), ByteArray(12)), box("minf", s)),
        )
        val chapterSample = cat(be16(5), ascii("Intro"))
        fun moov(base: Long): ByteArray {
            var off = base
            val offs = samples.map { s -> off.also { off += s.size } }
            return box(
                "moov",
                trak(1, "soun", 0, box("stbl"), tref = box("tref", box("chap", be32(3)))),
                trak(2, "sbtl", packed, stbl(offs, lines.map { it.second }, samples.map { it.size })),
                trak(3, "text", 0, stbl(listOf(off), listOf(1000), listOf(chapterSample.size))),
            )
        }
        val size = moov(0).size
        val base = ftyp.size + size + 8L
        return cat(ftyp, moov(base), box("mdat", *samples.toTypedArray(), chapterSample))
    }

    @Test fun mp4Tx3gSubtitles() {
        val file = ArraySource(mp4WithSubtitles(listOf("First" to 2_000L, "" to 3_000L, "Second" to 1_500L), "eng"))
        val subs = MediaTags.subtitles(file, "en")
        assertEquals(listOf("First", "Second"), subs.map { it.text })
        // The chapter text track ("Intro") is not taken for subtitles.
        assertEquals(listOf(0L to 2_000L, 5_000L to 6_500L), subs.map { it.startMs to it.endMs })
    }

    @Test fun fileSourceWindowReadsLikeTheArray() {
        val bytes = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
        val f = java.io.File.createTempFile("tags", ".bin").apply { deleteOnExit(); writeBytes(bytes) }
        val arr = ArraySource(bytes)
        com.mediaviewer.data.tags.FileSource(f).use { fs ->
            val rnd = java.util.Random(1)
            repeat(2_000) {
                val pos = if (it % 3 == 0) rnd.nextInt(bytes.size + 10).toLong() else (it * 97L) % bytes.size
                val len = if (it % 50 == 0) 40_000 else rnd.nextInt(64) + 1
                val a = ByteArray(len)
                val b = ByteArray(len)
                assertEquals(arr.read(pos, a), fs.read(pos, b))
                assertTrue(a.contentEquals(b))
            }
        }
    }
}
