package com.mediaviewer.data.mtp

import com.mediaviewer.data.mtp.MtpConnectionTest.Companion.D
import com.mediaviewer.data.mtp.MtpConnectionTest.Companion.S
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The mirror's budget and order (todo §9.6) against [FakeMtpDriver]. */
class MtpMirrorTest {
    private val dev = FakeMtpDriver().apply { storage(S) }
    private val conn = MtpConnection(D, dev)
    private val root = MtpPaths.storageRoot(D, S)
    private val tmp: File = Files.createTempDirectory("mirror").toFile()

    @After
    fun tearDown() {
        conn.close()
        tmp.deleteRecursively()
    }

    private fun entry(name: String, size: Long) = FileEntry("$root/$name", name, MediaType.IMAGE, size, 0)

    @Test
    fun `the budget is 2 GB and the first file that doesn't fit ends the plan`() {
        assertEquals(2L * 1024 * 1024 * 1024, MtpMirror.BUDGET)
        val mb = 1024L * 1024
        val queue = listOf(entry("a", 700 * mb), entry("b", 700 * mb), entry("c", 700 * mb), entry("d", 10 * mb))
        assertEquals(listOf("a", "b"), MtpMirror.plan(queue, 0, MtpMirror.BUDGET).map { it.name })
        assertEquals(listOf("a"), MtpMirror.plan(queue, 1024 * mb, MtpMirror.BUDGET).map { it.name })
        assertEquals(emptyList<FileEntry>(), MtpMirror.plan(queue, MtpMirror.BUDGET, MtpMirror.BUDGET))
        assertEquals(4, MtpMirror.plan(queue, 0, 3000 * mb).size)
    }

    @Test
    fun `downloading ahead goes from the next file round to the one before`() {
        val list = (1..5).map { entry("$it", 1) }
        assertEquals(listOf("4", "5", "1", "2"), MtpMirror.order(list, list[2]).map { it.name })
        assertEquals(list, MtpMirror.order(list, entry("x", 1)))
    }

    @Test
    fun `prefetch stops at the budget and counts what the mirror already holds`() = runBlocking {
        val files = (1..6).map { i -> dev.file(S, MtpFormats.ROOT, "p$i.jpg", ByteArray(4000) { i.toByte() }); i }
        val entries = conn.list(root).sortedBy { it.name }
        assertEquals(files.size, entries.size)
        val mirror = MtpMirror(File(tmp, "m"), { conn }, budget = 10_000)
        assertEquals(2, mirror.prefetch(entries, entries[0]) { _, _ -> true })
        assertTrue(mirror.of(entries[1].path)!!.isFile)
        assertTrue(mirror.of(entries[2].path)!!.isFile)
        assertFalse(mirror.of(entries[3].path)!!.exists())
        assertEquals(8000, mirror.bytes())
        // 8,000 bytes in: nothing more fits.
        assertEquals(0, mirror.prefetch(entries, entries[3]) { _, _ -> true })
    }

    @Test
    fun `a slideshow over budget frees every other folder of the mirror`() {
        val mirror = MtpMirror(File(tmp, "m"), { conn }, budget = 100)
        val keep = mirror.of("$root/DCIM")!!
        File(keep, "a.jpg").apply { parentFile.mkdirs() }.writeBytes(ByteArray(80))
        val other = File(mirror.of("$root/Other")!!, "b.jpg").apply { parentFile.mkdirs() }
        other.writeBytes(ByteArray(80))
        mirror.makeRoomFor(keep)
        assertTrue(File(keep, "a.jpg").isFile)
        assertFalse(other.exists())
        assertFalse(other.parentFile.exists())
        mirror.makeRoomFor(keep) // under budget now: nothing changes
        assertTrue(File(keep, "a.jpg").isFile)
    }

    @Test
    fun `mirror paths map back to device paths`() {
        val mirror = MtpMirror(File(tmp, "m"), { conn })
        val local = mirror.of("$root/DCIM/a.jpg")!!
        assertEquals(File(tmp, "m/$D/$S/DCIM/a.jpg"), local)
        assertTrue(mirror.isMirror(local.path))
        assertEquals("$root/DCIM/a.jpg", mirror.remoteOf(local.path))
        assertNull(mirror.remoteOf("/sdcard/a.jpg"))
    }

    @Test
    fun `fetch keeps a copy of the same size and returns null without the device`() = runBlocking {
        dev.file(S, MtpFormats.ROOT, "a.jpg", ByteArray(10))
        val e = conn.list(root).single()
        val mirror = MtpMirror(File(tmp, "m"), { conn })
        mirror.fetch(e)
        mirror.fetch(e)
        assertEquals(1, dev.calls.count { it == "readPartial" })
        assertNull(MtpMirror(File(tmp, "m"), { null }).fetch(e))
    }
}
