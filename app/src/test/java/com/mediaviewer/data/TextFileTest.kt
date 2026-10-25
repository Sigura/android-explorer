package com.mediaviewer.data

import com.mediaviewer.data.fs.Drafts
import com.mediaviewer.data.fs.TextFile
import com.mediaviewer.data.thumbs.TextDecode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

class TextFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cp1251 = Charset.forName("windows-1251")

    private fun load(f: File) = (TextFile.load(f, Charsets.ISO_8859_1, TextDecode.cyrillicDetector) as TextFile.Load.Ok).doc

    @Test fun keepsCharsetBomAndLineEndings() {
        val f = tmp.newFile("a.txt")
        val original = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "one\r\ntwo\r\n".toByteArray()
        f.writeBytes(original)
        val doc = load(f)
        assertEquals("one\ntwo\n", doc.text)
        assertTrue(doc.bom && doc.crlf)
        assertTrue(TextFile.save(doc, doc.text) is TextFile.Save.Ok)
        assertArrayEquals(original, f.readBytes())
        assertTrue(TextFile.save(load(f), "one\ntwo\nthree") is TextFile.Save.Ok)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "one\r\ntwo\r\nthree".toByteArray(), f.readBytes())
    }

    @Test fun legacyCharsetRoundTrip() {
        val f = tmp.newFile("ru.txt")
        val text = "Привет, мир! Это простой текст для проверки кодировки, он достаточно длинный.\n"
        f.writeBytes(text.toByteArray(cp1251))
        val doc = load(f)
        assertEquals(cp1251, doc.charset)
        assertEquals(text, doc.text)
        assertTrue(TextFile.save(doc, text + "Ещё строка") is TextFile.Save.Ok)
        assertArrayEquals((text + "Ещё строка").toByteArray(cp1251), f.readBytes())
    }

    @Test fun unencodableAndSaveAsUtf8() {
        val f = tmp.newFile("ru.txt")
        f.writeBytes("Простой русский текст, достаточно длинный для проверки.".toByteArray(cp1251))
        val doc = load(f)
        assertEquals(TextFile.Save.Unencodable, TextFile.save(doc, doc.text + " 😀"))
        assertTrue(TextFile.save(doc, doc.text + " 😀", charset = Charsets.UTF_8) is TextFile.Save.Ok)
        assertEquals(doc.text + " 😀", f.readText())
    }

    @Test fun changedOnDiskIsNoticed() {
        val f = tmp.newFile("a.txt")
        f.writeText("x")
        val doc = load(f)
        f.writeText("changed by someone")
        assertEquals(TextFile.Save.Changed, TextFile.save(doc, "mine"))
        assertTrue(TextFile.save(doc, "mine", force = true) is TextFile.Save.Ok)
        assertEquals("mine", f.readText())
        assertTrue(tmp.root.list()!!.none { it.endsWith(".tmp") })
    }

    @Test fun binaryAndLargeFiles() {
        val bin = tmp.newFile("a.bin")
        bin.writeBytes(byteArrayOf(1, 2, 0, 4))
        assertEquals(TextFile.Load.NotText, TextFile.load(bin, Charsets.UTF_8, null))
        val big = tmp.newFile("big.txt")
        big.writeBytes(ByteArray((TextFile.EDIT_LIMIT + 10).toInt()) { 'a'.code.toByte() })
        assertTrue(load(big).readOnly)
        val empty = tmp.newFile("e.txt")
        assertEquals("", load(empty).text)
    }

    @Test fun names() {
        assertEquals("notes", TextFile.cleanName("  notes.  "))
        assertEquals("", TextFile.cleanName(".."))
        assertEquals(".hidden", TextFile.cleanName(".hidden"))
        assertNotNull(TextFile.nameProblem(""))
        assertNotNull(TextFile.nameProblem("a/b"))
        assertNotNull(TextFile.nameProblem("я".repeat(128)))
        assertNull(TextFile.nameProblem("notes"))
        val f = File(tmp.root, "n.md")
        assertNull(TextFile.create(f))
        assertNotNull(TextFile.create(f))
        assertNull(TextFile.mkdir(File(tmp.root, "dir")))
        assertNotNull(TextFile.mkdir(File(tmp.root, "dir")))
    }

    @Test fun drafts() {
        val d = Drafts(File(tmp.root, "drafts"))
        assertNull(d.get("/a"))
        d.put("/a", "text")
        assertEquals("text", d.get("/a")!!.text)
        d.remove("/a")
        assertNull(d.get("/a"))
    }
}
