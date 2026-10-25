package com.mediaviewer.data

import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTypesTest {
    @Test fun otherFilesGetPreciseMime() {
        assertEquals("application/pdf", FileTypes.mimeType("Book.PDF", MediaType.OTHER))
        assertEquals("audio/mpeg", FileTypes.mimeType("01 - Intro.mp3", MediaType.OTHER))
        assertEquals("text/csv", FileTypes.mimeType("data.csv", MediaType.OTHER))
        assertEquals("application/vnd.android.package-archive", FileTypes.mimeType("app.apk", MediaType.OTHER))
        assertEquals("*/*", FileTypes.mimeType("unknown.xyz", MediaType.OTHER))
        assertEquals("*/*", FileTypes.mimeType("README", MediaType.OTHER))
    }

    @Test fun mediaMimeUnchanged() {
        assertEquals("video/x-matroska", FileTypes.mimeType("a.mkv", MediaType.VIDEO))
        assertEquals("image/gif", FileTypes.mimeType("a.gif", MediaType.ANIMATED))
    }

    @Test fun audiobooksAreAudio() {
        assertEquals(OtherKind.AUDIO, FileTypes.byExtension("Book 01.m4b")?.other)
        val noExt = java.io.File.createTempFile("book", "").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(0, 0, 0, 0x20) + "ftypM4B ".toByteArray() + ByteArray(40))
        }
        assertEquals(OtherKind.AUDIO, FileTypes.detect(noExt).other)
    }
}
