package com.mediaviewer.data.mtp

import com.mediaviewer.data.mtp.MtpConnectionTest.Companion.D
import com.mediaviewer.data.mtp.MtpConnectionTest.Companion.S
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** *Copy to…* / *Move to…* with a device on either side (todo §9.8 item 2), against [FakeMtpDriver]. */
class MtpTransferTest {
    private val dev = FakeMtpDriver().apply { storage(S) }
    private val conn = MtpConnection(D, dev)
    private val root = MtpPaths.storageRoot(D, S)
    private val tmp: File = Files.createTempDirectory("transfer").toFile()
    private val temp = File(tmp, "temp")
    private val transfer = MtpTransfer({ p -> if (MtpPaths.parse(p)?.deviceId == D) conn else null }, temp)
    private val progress = ArrayList<String>()

    @After
    fun tearDown() {
        conn.close()
        tmp.deleteRecursively()
    }

    private fun local(f: File) = FileEntry(f.path, f.name, if (f.isDirectory) MediaType.FOLDER else MediaType.IMAGE, f.length(), 0)

    private fun run(entries: List<FileEntry>, dest: String, move: Boolean = false, moved: MutableList<String> = ArrayList()) =
        runBlocking { transfer.run(entries, dest, move, { i, n, name -> progress.add("${i + 1}/$n $name") }) { moved.add(it) } }

    @Test
    fun `files and folders go to the device with (1) names`() {
        dev.file(S, MtpFormats.ROOT, "a.jpg")
        val src = File(tmp, "src").apply { mkdirs() }
        val a = File(src, "a.jpg").apply { writeBytes(byteArrayOf(1)) }
        val trip = File(src, "Trip").apply { mkdirs() }
        File(trip, "b.jpg").writeBytes(byteArrayOf(2))
        File(trip, "inner").mkdirs()
        val r = run(listOf(local(a), local(trip)), root)
        assertEquals(2, r.done)
        assertEquals(emptyList<Pair<String, String>>(), r.failed)
        assertEquals(listOf("1/2 a.jpg", "2/2 Trip"), progress)
        assertArrayEquals(byteArrayOf(1), dev.byName(MtpFormats.ROOT, "a (1).jpg")!!.data)
        val t = dev.byName(MtpFormats.ROOT, "Trip")!!.info.handle
        assertArrayEquals(byteArrayOf(2), dev.byName(t, "b.jpg")!!.data)
        assertEquals(MtpFormats.ASSOCIATION, dev.byName(t, "inner")!!.info.format)
        assertTrue("copies keep the originals", a.isFile)
    }

    @Test
    fun `a move from the device deletes the originals after copying`() {
        val f = dev.folder(S, MtpFormats.ROOT, "F")
        dev.file(S, f, "x.jpg", byteArrayOf(5))
        dev.file(S, MtpFormats.ROOT, "y.jpg", byteArrayOf(6))
        val entries = runBlocking { conn.list(root) }
        val dest = File(tmp, "dest").apply { mkdirs() }
        File(dest, "y.jpg").writeBytes(byteArrayOf(0))
        val moved = ArrayList<String>()
        val r = run(entries, dest.path, move = true, moved = moved)
        assertEquals(2, r.done)
        assertArrayEquals(byteArrayOf(5), File(dest, "F/x.jpg").readBytes())
        assertArrayEquals(byteArrayOf(6), File(dest, "y (1).jpg").readBytes())
        assertEquals(0, dev.nodes.size)
        assertEquals(entries.map { it.path }.toSet(), moved.toSet())
    }

    @Test
    fun `device to device goes through a temporary copy that is removed`() {
        val a = dev.folder(S, MtpFormats.ROOT, "A")
        dev.folder(S, MtpFormats.ROOT, "B")
        dev.file(S, a, "x.jpg", byteArrayOf(7))
        val x = runBlocking { conn.list("$root/A") }.single()
        val r = run(listOf(x), "$root/B")
        assertEquals(1, r.done)
        val b = dev.byName(MtpFormats.ROOT, "B")!!.info.handle
        assertArrayEquals(byteArrayOf(7), dev.byName(b, "x.jpg")!!.data)
        assertTrue(temp.list().isNullOrEmpty())
    }

    @Test
    fun `a folder can't go into itself or where it already is`() {
        val a = dev.folder(S, MtpFormats.ROOT, "A")
        dev.folder(S, a, "In")
        val entry = runBlocking { conn.list(root) }.single()
        val r = run(listOf(entry), "$root/A/In")
        assertEquals(listOf(entry.path to "can't put a folder inside itself"), r.failed)
        val r2 = run(listOf(entry), root)
        assertEquals(listOf(entry.path to "already in this folder"), r2.failed)
    }

    @Test
    fun `a failure is reported per file and the rest go on`() {
        dev.file(S, MtpFormats.ROOT, "a.jpg")
        dev.file(S, MtpFormats.ROOT, "b.jpg")
        val entries = runBlocking { conn.list(root) }.sortedBy { it.name }
        dev.fail = { op, h -> op == "readPartial" && h == dev.byName(MtpFormats.ROOT, "a.jpg")!!.info.handle }
        val dest = File(tmp, "d").apply { mkdirs() }
        val r = run(entries, dest.path, move = true)
        assertEquals(1, r.done)
        assertEquals(entries[0].path, r.failed.single().first)
        assertFalse(File(dest, "a.jpg").exists())
        assertTrue(File(dest, "b.jpg").isFile)
        assertNull(dev.byName(MtpFormats.ROOT, "b.jpg"))
        assertTrue("a failed copy keeps its original", dev.byName(MtpFormats.ROOT, "a.jpg") != null)
    }

    @Test
    fun `an unplugged device fails each file`() {
        val src = File(tmp, "a.jpg").apply { writeBytes(byteArrayOf(1)) }
        val r = run(listOf(local(src)), MtpPaths.storageRoot(9, S))
        assertEquals("Device disconnected", r.failed.single().second)
    }
}
