package com.mediaviewer.data

import com.mediaviewer.data.tags.ArraySource
import com.mediaviewer.data.thumbs.DocCovers
import com.mediaviewer.data.thumbs.ZipIndex
import com.mediaviewer.domain.CsvPreview
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Document and book covers, the ZIP reader and CSV previews (todo §1.0). */
class PreviewTest {
    /** A ZIP of [files]; names ending in `!` are stored instead of deflated. */
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((rawName, data) in files) {
                val stored = rawName.endsWith("!")
                val e = ZipEntry(rawName.removeSuffix("!"))
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun open(vararg files: Pair<String, ByteArray>) = ZipIndex.open(ArraySource(zip(*files)))!!
    private fun text(s: String) = s.toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(200) { it.toByte() }

    // ------------------------------------------------------------------ ZIP

    @Test fun zipEntriesStoredAndDeflated() {
        val big = text("x".repeat(50_000))
        val z = open("mimetype!" to text("application/epub+zip"), "a/b.txt" to big, "dir/" to ByteArray(0), "img.png" to png)
        assertEquals(listOf("mimetype", "a/b.txt", "img.png"), z.entries.map { it.name })
        assertEquals("application/epub+zip", z.readText("mimetype"))
        assertArrayEquals(big, z.read(z.find("a/b.txt")!!))
        assertArrayEquals(png, z.read(z.find("IMG.PNG")!!))
        assertNull("over the cap", z.read(z.find("a/b.txt")!!, max = 1_000))
    }

    @Test fun notAZip() {
        assertNull(ZipIndex.open(ArraySource(text("PK but not really"))))
        assertNull(ZipIndex.open(ArraySource(ByteArray(0))))
        val good = zip("a.txt" to text("hello"))
        // The end record points past the file.
        val broken = good.copyOf().also { b -> b[b.size - 6] = 0x7F }
        assertNull(ZipIndex.open(ArraySource(broken)))
    }

    // ------------------------------------------------------------------ EPUB, CBZ, ODF, OOXML

    private val container = """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    @Test fun epub3CoverImage() {
        val opf = """<package><manifest>
            <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item href="images/My%20Cover.jpg" id="ci" media-type="image/jpeg" properties="cover-image"/>
            <item id="other" href="images/fig.png" media-type="image/png"/>
            </manifest></package>"""
        val z = open("META-INF/container.xml" to text(container), "OEBPS/content.opf" to text(opf), "OEBPS/images/My Cover.jpg" to png, "OEBPS/images/fig.png" to png)
        assertEquals("OEBPS/images/My Cover.jpg", DocCovers.coverEntry("book.epub", z))
    }

    @Test fun epub2MetaCoverAndFallbacks() {
        val opf2 = """<package><metadata><meta content="cov" name="cover"/></metadata><manifest>
            <item id="fig" href="../Images/fig.png" media-type="image/png"/>
            <item id="cov" href="../Images/front.jpeg" media-type="image/jpeg"/>
            </manifest></package>"""
        val z = open("META-INF/container.xml" to text(container), "OEBPS/content.opf" to text(opf2), "Images/front.jpeg" to png, "Images/fig.png" to png)
        assertEquals("../ is resolved", "Images/front.jpeg", DocCovers.coverEntry("x.epub", z))

        val opf3 = """<package><manifest><item id="i1" href="a.png" media-type="image/png"/><item id="i2" href="the-cover.png" media-type="image/png"/></manifest></package>"""
        val z3 = open("META-INF/container.xml" to text(container), "OEBPS/content.opf" to text(opf3), "OEBPS/a.png" to png, "OEBPS/the-cover.png" to png)
        assertEquals("an image named cover", "OEBPS/the-cover.png", DocCovers.coverEntry("x.epub", z3))

        val noOpf = open("META-INF/container.xml" to text(container))
        assertNull(DocCovers.coverEntry("x.epub", noOpf))
    }

    @Test fun cbzOdfOoxml() {
        val cbz = open("Comic/page10.jpg" to png, "Comic/page2.jpg" to png, "__MACOSX/Comic/._page1.jpg" to png, "Comic/info.txt" to text("x"))
        assertEquals("Comic/page2.jpg", DocCovers.coverEntry("c.cbz", cbz))
        val odt = open("mimetype!" to text("application/vnd.oasis.opendocument.text"), "Thumbnails/thumbnail.png" to png)
        assertEquals("Thumbnails/thumbnail.png", DocCovers.coverEntry("letter.odt", odt))
        val docx = open("word/document.xml" to text("<w:document/>"), "docProps/thumbnail.jpeg" to png)
        assertEquals("docProps/thumbnail.jpeg", DocCovers.coverEntry("report.DOCX", docx))
        val emf = open("docProps/thumbnail.emf" to png)
        assertNull("not a picture Android decodes", DocCovers.coverEntry("old.xlsx", emf))
    }

    @Test fun docxText() {
        val xml = """<w:document><w:body>
            <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Title &amp; more</w:t></w:r></w:p>
            <w:p w:rsidR="1"><w:r><w:t xml:space="preserve">First </w:t></w:r><w:r><w:t>line</w:t><w:tab/><w:t>tab</w:t></w:r></w:p>
            <w:p/>
            <w:p><w:r><w:t>Last</w:t><w:br/><w:t>break</w:t></w:r></w:p>
            </w:body></w:document>"""
        assertEquals("Title & more\nFirst line\ttab\n\nLast\nbreak", DocCovers.docxText(xml))
        assertEquals("Title", DocCovers.docxText(xml, max = 5))
    }

    // ------------------------------------------------------------------ FB2

    @Test fun fb2Cover() {
        val b64 = Base64.getMimeEncoder().encodeToString(png)
        val fb2 = """<?xml version="1.0" encoding="windows-1251"?>
            <FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info>
            <coverpage><image l:href="#cover.jpg"/></coverpage></title-info></description>
            <body><p>Текст</p></body>
            <binary id="other.png" content-type="image/png">AAAA</binary>
            <binary content-type="image/jpeg" id="cover.jpg">
            $b64
            </binary></FictionBook>"""
        assertArrayEquals(png, DocCovers.fb2Cover(fb2.toByteArray(charset("windows-1251"))))
        assertNull(DocCovers.fb2Cover(text("<FictionBook><body/></FictionBook>")))
        assertNull("broken base64", DocCovers.fb2Cover(text("""<coverpage><image xlink:href="#c"/></coverpage><binary id="c">@@@</binary>""")))
    }

    // ------------------------------------------------------------------ CSV

    @Test fun csvAligned() {
        val csv = "name,qty,price\n\"Apple, red\",3,1.20\nPear,12,0.5\n"
        assertEquals(
            "name        qty  price\n" +
                "Apple, red  3    1.20\n" +
                "Pear        12   0.5",
            CsvPreview.align(csv),
        )
    }

    @Test fun csvSeparatorsQuotesAndCuts() {
        assertEquals(';', CsvPreview.sniff(listOf("a;b,c;d", "1;2,5;3", "x;y;z")))
        assertEquals('\t', CsvPreview.sniff(listOf("a\tb", "c\td")))
        assertEquals(listOf("say \"hi\"", "x"), CsvPreview.split("\"say \"\"hi\"\"\",x", ','))
        val long = "id,text\n1,${"w".repeat(40)}"
        assertTrue(CsvPreview.align(long, maxCell = 10).lines()[1].endsWith("wwwwwwwww…"))
        assertEquals("one column stays as it was", "a\nb\nc", CsvPreview.align("a\nb\nc"))
    }
}
