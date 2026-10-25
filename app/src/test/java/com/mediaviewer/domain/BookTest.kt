package com.mediaviewer.domain

import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTest {
    private fun audio(name: String) = FileEntry("/b/$name", name, MediaType.OTHER, 1, 0, OtherKind.AUDIO)
    private fun video(name: String) = FileEntry("/b/$name", name, MediaType.VIDEO, 1, 0)
    private fun image(name: String) = FileEntry("/b/$name", name, MediaType.IMAGE, 1, 0)
    private fun text(name: String) = FileEntry("/b/$name", name, MediaType.OTHER, 1, 0, OtherKind.TEXT)

    private val long = 10 * 60_000L
    private val short = 60_000L

    @Test fun audioAndMixedFolders() {
        assertEquals(BookKind.AUDIO, Book.classify(listOf(audio("1.mp3"), audio("2.mp3"), image("cover.jpg"), text("a.m3u")), 0) { null })
        assertEquals(BookKind.MIXED, Book.classify(listOf(audio("1.mp3"), video("2.mp4")), 0) { short })
        // A photo folder with one song plays a slideshow.
        assertNull(Book.classify(listOf(audio("song.mp3"), image("1.jpg"), image("2.jpg")), 0) { null })
    }

    @Test fun lectureFoldersNeedOnlyLongVideos() {
        val lectures = listOf(video("01.mkv"), video("02.mkv"), image("01.webp"), image("02.webp"), text("01.info.json"))
        assertEquals(BookKind.LECTURE, Book.classify(lectures, 0) { long })
        assertNull(Book.classify(lectures, 0) { if (it.name == "02.mkv") short else long })
        assertNull("unknown durations: not yet", Book.classify(lectures, 0) { null })
        assertNull("one video is no lecture series", Book.classify(listOf(video("a.mp4")), 0) { long })
        assertNull("above the user's limit", Book.classify(lectures, 20 * 60_000L) { long })
    }

    @Test fun naturalNameOrderWithoutPlaylist() {
        val files = listOf(audio("10 - x.mp3"), audio("2 - x.mp3"), audio("1 - x.mp3"))
        assertEquals(listOf("1 - x.mp3", "2 - x.mp3", "10 - x.mp3"), Book.order(files, null).map { it.name })
    }

    @Test fun playlistOrderThenUnlistedFiles() {
        val files = listOf(audio("a.mp3"), audio("b.mp3"), audio("c.mp3"), audio("d.mp3"))
        val m3u = "#EXTM3U\n#EXTINF:123,Chapter C\nc.mp3\r\nsub/../a.mp3\nmissing.mp3\nhttp://radio/stream\nC:\\Books\\b.mp3\n"
        val list = Playlist.parse(m3u, "/b")
        assertEquals(listOf("/b/c.mp3", "/b/a.mp3", "/b/missing.mp3", "/b/b.mp3"), list)
        assertEquals(listOf("c.mp3", "a.mp3", "b.mp3", "d.mp3"), Book.order(files, list).map { it.name })
    }

    @Test fun choosesPlaylistNamedLikeTheFolder() {
        assertEquals("Book.m3u8", Book.choosePlaylist(listOf("a.m3u", "Book.m3u8", "x.mp3"), "book"))
        assertEquals("a.m3u", Book.choosePlaylist(listOf("b.m3u", "a.m3u"), "other"))
        assertNull(Book.choosePlaylist(listOf("x.mp3"), "b"))
    }

    @Test fun rewindAndFinished() {
        assertEquals(0L, Book.rewindMs(30_000))
        assertEquals(5_000L, Book.rewindMs(10 * 60_000))
        assertEquals(20_000L, Book.rewindMs(5 * 3_600_000L))
        assertTrue(Book.isFinished(3_590_000, 3_600_000))
        assertFalse(Book.isFinished(3_000_000, 3_600_000))
        assertTrue(Book.isFinished(99_000, 100_000)) // 2 % of a short file
    }
}
