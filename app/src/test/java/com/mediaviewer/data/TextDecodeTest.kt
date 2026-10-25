package com.mediaviewer.data

import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.thumbs.TextDecode
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

class TextDecodeTest {
    private val latin1 = Charsets.ISO_8859_1
    private val russian = "Привет, мир! Это простой текст для проверки кодировки, он достаточно длинный.\n"

    private fun decode(bytes: ByteArray, fallback: Charset = latin1) =
        TextDecode.decode(bytes, bytes.size, fallback, TextDecode.cyrillicDetector)

    @Test fun bomAndUtf8() {
        assertEquals("héllo", decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".toByteArray()))
        assertEquals("hi", decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'h'.code.toByte(), 0, 'i'.code.toByte(), 0)))
        assertEquals(russian, decode(russian.toByteArray()))
    }

    @Test fun utf8CutInsideACharacterIsStillUtf8() {
        val bytes = "абв".toByteArray()
        assertEquals("аб", decode(bytes.copyOf(bytes.size - 1)))
    }

    @Test fun binaryIsRejected() {
        assertNull(decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0x14)))
        assertNull(decode(ByteArray(100) { if (it % 3 == 0) 0x01 else 0x41 }))
    }

    @Test fun cyrillicCodePages() {
        for (name in listOf("windows-1251", "KOI8-R", "IBM866")) {
            assertEquals(name, russian, decode(russian.toByteArray(Charset.forName(name))))
        }
    }

    @Test fun westernTextUsesTheSetting() {
        val text = "Café crème, déjà vu — naïve façade.\n".repeat(3)
        assertEquals(text, decode(text.toByteArray(Charset.forName("windows-1252")), Charset.forName("windows-1252")))
    }

    @Test fun declaredCharsetWins() {
        val bytes = "# -*- coding: cp1251 -*-\n".toByteArray() + "x = 'ёлка'\n".toByteArray(Charset.forName("windows-1251"))
        assertEquals("x = 'ёлка'", decode(bytes)!!.lines()[1])
        assertEquals(Charset.forName("windows-1251"), "<?xml version=\"1.0\" encoding=\"windows-1251\"?>".toByteArray().let { TextDecode.declared(it, it.size) })
        assertNull("<meta charset=\"utf-8\">".toByteArray().let { TextDecode.declared(it, it.size) })
    }

    @Test fun linesAreCleaned() {
        val lines = TextDecode.lines("\n\n\ta\tb\u0007\r\nsecond   \nthird\npart", maxLines = 10, truncated = true)
        assertEquals(listOf("    a   b", "second", "third"), lines)
        assertEquals(listOf("one long line"), TextDecode.lines("one long line", 5, truncated = true))
        assertEquals(2, TextDecode.lines("a\nb\nc\nd", 2, truncated = false).size)
    }

    @Test fun defaultCharsetFollowsLanguage() {
        assertEquals("windows-1251", TextDecode.defaultLegacyCharset(Locale("uk")))
        assertEquals("windows-1252", TextDecode.defaultLegacyCharset(Locale.GERMANY))
    }

    @Test fun fileKinds() {
        assertEquals(OtherKind.CODE, FileTypes.byExtension("main.kt")?.other)
        assertEquals(OtherKind.CODE, FileTypes.byExtension("Makefile")?.other)
        assertEquals(OtherKind.TEXT, FileTypes.byExtension("README")?.other)
        assertEquals(OtherKind.TEXT, FileTypes.byExtension("movie.srt")?.other)
        assertEquals(OtherKind.PDF, FileTypes.byExtension("a.pdf")?.other)
        assertEquals(OtherKind.DOCUMENT, FileTypes.byExtension("a.docx")?.other)
        assertEquals(true, FileTypes.monospace("app.log", OtherKind.TEXT))
        assertEquals(false, FileTypes.monospace("notes.txt", OtherKind.TEXT))
    }

    @Test fun tsIsVideoOrTypeScriptByContent() {
        val video = File.createTempFile("clip", ".ts").apply {
            deleteOnExit()
            writeBytes(ByteArray(400) { if (it % 188 == 0) 0x47 else 0 })
        }
        val code = File.createTempFile("app", ".ts").apply {
            deleteOnExit()
            writeText("export const x: number = 1;\n".repeat(20))
        }
        assertEquals(MediaType.VIDEO, FileTypes.detect(video).type)
        assertEquals(OtherKind.CODE, FileTypes.detect(code).other)
    }
}
