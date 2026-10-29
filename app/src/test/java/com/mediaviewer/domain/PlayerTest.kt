package com.mediaviewer.domain

import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Player screen rules (todo §4): lyrics, subtitles, chapters, cue sheets, shuffle, speed, sleep timer. */
class PlayerTest {
    // ------------------------------------------------------------------ LRC

    @Test fun lrcLinesAreTimedAndSorted() {
        val l = Lyrics.parseLrc(
            """
            [ti:Song]
            [ar:Someone]
            [00:12.00]First line
            [00:17.20]Second line
            [01:02.5]Third
            """.trimIndent(),
        )!!
        assertTrue(l.synced)
        assertEquals(listOf(12_000L, 17_200L, 62_500L), l.lines.map { it.startMs })
        assertEquals("First line", l.lines[0].text)
        assertEquals(-1, l.indexAt(11_999))
        assertEquals(0, l.indexAt(12_000))
        assertEquals(1, l.indexAt(20_000))
        assertEquals(2, l.indexAt(600_000))
    }

    @Test fun lrcRepeatedLinesAndPrecision() {
        val l = Lyrics.parseLrc("[00:01.00][00:30.00]Chorus\n[00:10.123]Verse\n[00:20]Bridge")!!
        assertEquals(listOf("Chorus" to 1_000L, "Verse" to 10_123L, "Bridge" to 20_000L, "Chorus" to 30_000L), l.lines.map { it.text to it.startMs })
    }

    @Test fun lrcOffsetMakesLinesComeSooner() {
        val l = Lyrics.parseLrc("[offset:+500]\n[00:10.00]A\n[00:20.00]B")!!
        assertEquals(listOf(9_500L, 19_500L), l.lines.map { it.startMs })
        val later = Lyrics.parseLrc("[offset:-250]\n[00:10.00]A\n[00:11.00]B")!!
        assertEquals(10_250L, later.lines[0].startMs)
    }

    @Test fun lrcWordStampsAreRemovedAndEmptyLinesArePauses() {
        val l = Lyrics.parseLrc("[00:01.00]<00:01.00>Hello <00:01.50>world\n[00:05.00]\n[00:09.00]Again")!!
        assertEquals("Hello world", l.lines[0].text)
        assertEquals(2, l.lines.size)
        assertEquals(0, l.indexAt(3_000))
        assertEquals("nothing during the pause", -1, l.indexAt(6_000))
        assertEquals(1, l.indexAt(9_000))
    }

    @Test fun lrcDetectionAndFallbackToPlainText() {
        assertTrue(Lyrics.looksLikeLrc("[00:01.00]a\n[00:02.00]b"))
        assertFalse(Lyrics.looksLikeLrc("Just some words\n[Chorus]\nmore"))
        val plain = Lyrics.fromText("Line one\r\nLine two\n")!!
        assertFalse(plain.synced)
        assertEquals(listOf("Line one", "Line two"), plain.lines.map { it.text })
        assertEquals(-1, plain.indexAt(1_000))
        assertNull(Lyrics.fromText("  \n "))
        assertNull(Lyrics.parseLrc("[ar:Nobody]\n"))
    }

    // ------------------------------------------------------------------ SRT / VTT

    @Test fun srtCues() {
        val l = Lyrics.parseSubtitles(
            "1\r\n00:00:01,000 --> 00:00:04,000\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:01:00,500 --> 00:01:02,000\r\n{\\an8}Top &amp; more\r\n",
        )!!
        assertEquals(2, l.lines.size)
        assertEquals("Hello\nthere", l.lines[0].text)
        assertEquals(1_000L to 4_000L, l.lines[0].startMs to l.lines[0].endMs)
        assertEquals("Top & more", l.lines[1].text)
        assertEquals(60_500L, l.lines[1].startMs)
        assertEquals(0, l.indexAt(2_000))
        assertEquals("gap between cues", -1, l.indexAt(5_000))
    }

    @Test fun vttCuesWithHeaderNotesAndSettings() {
        val l = Lyrics.parseSubtitles(
            """
            WEBVTT
            Kind: captions

            NOTE a comment
            with two lines

            intro
            00:05.000 --> 00:07.250 align:start position:0%
            <v Speaker>Welcome<c.colorE5E5E5> everyone</c>

            01:00:00.000 --> 01:00:01.000
            Late
            """.trimIndent(),
        )!!
        assertEquals(listOf("Welcome everyone", "Late"), l.lines.map { it.text })
        assertEquals(5_000L, l.lines[0].startMs)
        assertEquals(7_250L, l.lines[0].endMs)
        assertEquals(3_600_000L, l.lines[1].startMs)
    }

    @Test fun youtubeRollingCaptionsDropTheRepeatedLine() {
        val l = Lyrics.parseSubtitles(
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            one two three

            00:00:03.000 --> 00:00:03.010
            one two three

            00:00:03.010 --> 00:00:05.000
            one two three
            four five six
            """.trimIndent(),
        )!!
        assertEquals(listOf("one two three", "four five six"), l.lines.map { it.text })
        assertEquals(3_010L, l.lines[0].endMs)
    }

    // ------------------------------------------------------------------ companions and covers

    @Test fun companionTextFiles() {
        val names = listOf("07 Song.mp3", "07 Song.LRC", "08 Other.mp3", "Lecture 1.mp4", "Lecture 1.de.vtt", "Lecture 1.en.vtt", "Lecture 2.mp4", "Lecture 2.fr.srt")
        assertEquals("07 Song.LRC", Companions.textFor("07 Song.mp3", names))
        assertNull(Companions.textFor("08 Other.mp3", names))
        assertEquals("Lecture 1.en.vtt", Companions.textFor("Lecture 1.mp4", names))
        assertEquals("Lecture 1.de.vtt", Companions.textFor("Lecture 1.mp4", names, language = "de"))
        assertEquals("only variant", "Lecture 2.fr.srt", Companions.textFor("Lecture 2.mp4", names))
        assertEquals("exact name wins", "a.srt", Companions.textFor("a.mkv", listOf("a.en.vtt", "a.srt")))
    }

    @Test fun coverImages() {
        assertEquals("Folder.JPG", Companions.coverFor(listOf("scan1.jpg", "Folder.JPG", "back.jpg")))
        assertEquals("cover.png", Companions.coverFor(listOf("front.jpg", "cover.png")))
        assertEquals("AlbumArt_{X}_Large.jpg", Companions.coverFor(listOf("AlbumArt_{X}_Small.jpg", "AlbumArt_{X}_Large.jpg", "x.jpg")))
        assertEquals("only image", "scan.webp", Companions.coverFor(listOf("scan.webp")))
        assertNull(Companions.coverFor(listOf("a.jpg", "b.jpg")))
        assertNull(Companions.coverFor(listOf("notes.gif")))
        assertEquals("the video's own thumbnail", "Talk 2.webp", Companions.coverFor(listOf("Talk 1.webp", "Talk 2.webp", "cover.jpg"), "Talk 2.mkv"))
        assertEquals("cover.jpg", Companions.coverFor(listOf("Talk 1.webp", "cover.jpg"), "Talk 3.mkv"))
    }

    // ------------------------------------------------------------------ chapters

    private val ch = listOf(Chapter(0, "One"), Chapter(60_000, "Two"), Chapter(120_000, "Three"))

    @Test fun chapterAtAndEnds() {
        assertEquals(0, Chapters.indexAt(ch, 0))
        assertEquals(1, Chapters.indexAt(ch, 119_999))
        assertEquals(2, Chapters.indexAt(ch, 500_000))
        assertEquals(120_000L, Chapters.endAt(ch, 70_000, 300_000))
        assertEquals(300_000L, Chapters.endAt(ch, 130_000, 300_000))
    }

    @Test fun previousAndNextChapterFollowTheThreeSecondRule() {
        assertEquals("back to this chapter's start", 60_000L, Chapters.previousStart(ch, 70_000))
        assertEquals("early in a chapter: the one before", 0L, Chapters.previousStart(ch, 61_000))
        assertNull("early in the first: the previous file", Chapters.previousStart(ch, 1_000))
        assertEquals(60_000L, Chapters.nextStart(ch, 0))
        assertNull("last chapter: the next file", Chapters.nextStart(ch, 130_000))
    }

    @Test fun normalizeSortsDropsDuplicatesAndSingleChapters() {
        val raw = listOf(Chapter(60_000, "B"), Chapter(1_500, "A"), Chapter(60_000, "dup"), Chapter(999_000, "past end"))
        assertEquals(listOf(Chapter(0, "A"), Chapter(60_000, "B")), Chapters.normalize(raw, durationMs = 500_000))
        assertTrue(Chapters.normalize(listOf(Chapter(0, "only"))).isEmpty())
    }

    // ------------------------------------------------------------------ cue sheets

    @Test fun cueSheetTracksAreChapters() {
        val cue = """
            REM GENRE Audiobook
            PERFORMER "Reader"
            TITLE "The Book"
            FILE "The Book.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Chapter 1"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Chapter 2"
                PERFORMER "Guest"
                INDEX 00 10:01:00
                INDEX 01 10:02:37
              TRACK 03 AUDIO
                INDEX 01 75:00:00
        """.trimIndent()
        val sheet = CueSheet.parse(cue)
        assertEquals(1, sheet.size)
        val chapters = sheet[0].chapters
        assertEquals(listOf("Chapter 1", "Chapter 2 — Guest", "Track 03"), chapters.map { it.title })
        assertEquals(listOf(0L, 602_493L, 4_500_000L), chapters.map { it.startMs })
        assertEquals("a .wav sheet matches the .flac", chapters, CueSheet.chaptersFor(sheet, "x.cue", "The Book.flac"))
        assertTrue(CueSheet.chaptersFor(sheet, "x.cue", "Other.flac").isEmpty())
    }

    @Test fun cueSheetWithSeveralFilesAndBareNames() {
        val cue = "FILE disc1.flac WAVE\nTRACK 1 AUDIO\nINDEX 01 00:00:00\nTRACK 2 AUDIO\nINDEX 01 01:00:00\nFILE \"disc 2.flac\" WAVE\nTRACK 3 AUDIO\nINDEX 01 00:00:00"
        val sheet = CueSheet.parse(cue)
        assertEquals(listOf("disc1.flac", "disc 2.flac"), sheet.map { it.name })
        assertEquals(2, CueSheet.chaptersFor(sheet, "album.cue", "DISC1.FLAC").size)
        val single = CueSheet.parse("FILE \"rip.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        assertEquals("only FILE of a sheet named like the audio", 1, CueSheet.chaptersFor(single, "Book.cue", "Book.mp3").size)
    }

    // ------------------------------------------------------------------ shuffle, speed, sleep

    private fun audio(name: String) = FileEntry("/b/$name", name, MediaType.OTHER, 1, 0, OtherKind.AUDIO)

    @Test fun shuffleIsStableWithTheCurrentFileFirst() {
        val queue = (1..30).map { audio("$it.mp3") }
        val a = ShuffleOrder.of(queue, seed = 42, first = "/b/7.mp3")
        assertEquals("/b/7.mp3", a[0].path)
        assertEquals(queue.toSet(), a.toSet())
        assertEquals("same seed, same order", a, ShuffleOrder.of(queue.reversed(), seed = 42, first = "/b/7.mp3"))
        assertNotEquals(a.map { it.name }, ShuffleOrder.of(queue, seed = 43, first = "/b/7.mp3").map { it.name })
        assertNotEquals("shuffled", queue.drop(1), a.drop(1))
        // A file added later falls into place; the others keep their relative order.
        val more = ShuffleOrder.of(queue + audio("31.mp3"), seed = 42, first = "/b/7.mp3")
        assertEquals(a, more.filter { it.name != "31.mp3" })
    }

    @Test fun speedSteps() {
        assertEquals(1.05f, PlayerSpeed.step(1f, +1), 0.0001f)
        assertEquals(0.95f, PlayerSpeed.step(1f, -1), 0.0001f)
        assertEquals(0.5f, PlayerSpeed.step(0.5f, -1), 0.0001f)
        assertEquals(3f, PlayerSpeed.step(3f, +1), 0.0001f)
        assertEquals(1.25f, PlayerSpeed.snap(1.26f), 0.0001f)
        assertEquals("1×", PlayerSpeed.label(1f))
        assertEquals("1.25×", PlayerSpeed.label(1.25f))
        assertEquals("0.8×", PlayerSpeed.label(0.8f))
        var s = 0.5f
        repeat(50) { s = PlayerSpeed.step(s, +1) }
        assertEquals("no drift over many steps", 3f, s, 0.0001f)
    }

    @Test fun sleepFadeAndShake() {
        assertEquals(1f, SleepTimer.volume(60_000), 0f)
        assertEquals(1f, SleepTimer.volume(SleepTimer.FADE_MS), 0f)
        assertEquals(0.25f, SleepTimer.volume(SleepTimer.FADE_MS / 2), 0.001f)
        assertEquals(0f, SleepTimer.volume(0), 0f)
        assertFalse("lying still", SleepTimer.isShake(0f, 0f, 9.81f))
        assertFalse("walking", SleepTimer.isShake(2f, 3f, 12f))
        assertTrue(SleepTimer.isShake(15f, 5f, 9f))
        assertEquals(10_000L, Skip.ms(music = true))
        assertEquals(30_000L, Skip.ms(music = false))
        assertEquals(15_000L, Skip.ms(music = false, bookSec = 15, musicSec = 5))
        assertEquals(5_000L, Skip.ms(music = true, bookSec = 15, musicSec = 5))
    }

    // ------------------------------------------------------------------ moved books

    private fun k(name: String, size: Long = 100) = BookMatch.FileKey(name, size)

    @Test fun movedBookIsFoundByItsPlayedFiles() {
        val here = (1..20).map { k("%02d.mp3".format(it)) }
        val recorded = listOf(k("01.mp3"), k("02.mp3"), k("03.mp3"))
        assertTrue(BookMatch.matches(here, recorded, current = k("03.mp3"), sameFolderName = false))
        assertFalse("the current file is gone", BookMatch.matches(here, recorded, current = k("99.mp3"), sameFolderName = false))
        assertFalse("sizes differ", BookMatch.matches(here, recorded.map { k(it.name, 5) }, current = null, sameFolderName = false))
        assertTrue("size not recorded", BookMatch.matches(here, recorded.map { k(it.name, 0) }, current = null, sameFolderName = false))
        assertFalse("one file is a weak sign after a rename", BookMatch.matches(here, listOf(k("01.mp3")), current = k("01.mp3"), sameFolderName = false))
        assertTrue("… but enough after a move", BookMatch.matches(here, listOf(k("01.mp3")), current = k("01.mp3"), sameFolderName = true))
    }

    @Test fun onlyOneCandidateMayMatch() {
        val here = listOf(k("a.mp3"), k("b.mp3"), k("c.mp3"))
        val old = BookMatch.Candidate("/old/Book", listOf(k("a.mp3"), k("b.mp3")), k("b.mp3"))
        val other = BookMatch.Candidate("/x/Other", listOf(k("q.mp3"), k("r.mp3")), k("q.mp3"))
        assertEquals("/old/Book", BookMatch.best(here, "Book (2)", listOf(old, other)))
        val twin = BookMatch.Candidate("/older/Book", listOf(k("a.mp3"), k("c.mp3")), k("c.mp3"))
        assertNull("two old folders match: don't guess", BookMatch.best(here, "Book", listOf(old, twin)))
    }
}
