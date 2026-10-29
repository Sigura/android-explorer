package com.mediaviewer.data.mtp

import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** [MtpConnection] against [FakeMtpDriver] (todo §9.8 item 9). */
class MtpConnectionTest {
    private val dev = FakeMtpDriver().apply { storage(S) }
    private val fast = MtpConnection.Timeouts(call = 300, list = 300, thumb = 300, chunk = 300)
    private val conn = MtpConnection(D, dev, fast)
    private val root = MtpPaths.storageRoot(D, S)
    private val tmp: File = Files.createTempDirectory("mtp").toFile()

    @After
    fun tearDown() {
        conn.close()
        tmp.deleteRecursively()
    }

    private fun scan(dir: String = root, hidden: Boolean = false) = runBlocking { conn.scan(dir, hidden).toList() }

    @Test
    fun `listing streams batches of at most 50, details 16 at a time`() {
        repeat(120) { dev.file(S, MtpFormats.ROOT, "IMG_%04d.JPG".format(it)) }
        val events = scan()
        val batches = events.filterIsInstance<FolderScanner.Event.Batch>()
        assertEquals(events.size, batches.size)
        assertTrue(batches.all { it.entries.size <= 50 })
        assertEquals(120, batches.sumOf { it.entries.size })
        // One handle call, then 120 / 16 rounded up detail calls of up to 16 objects each.
        assertEquals(1, dev.calls.count { it == "objectHandles" })
        assertEquals(120, dev.calls.count { it == "objectInfo" })
        val e = batches.first().entries.first()
        assertEquals("$root/IMG_0000.JPG", e.path)
        assertEquals(MediaType.IMAGE, e.type)
    }

    @Test
    fun `types come from the extension, else from the object format, hidden files are left out`() {
        val dcim = dev.folder(S, MtpFormats.ROOT, "DCIM")
        dev.file(S, dcim, "clip", format = MtpFormats.MP4)
        dev.file(S, dcim, "song", format = MtpFormats.MP3)
        dev.file(S, dcim, "a.gif")
        dev.file(S, dcim, ".hidden.jpg")
        val top = scan().filterIsInstance<FolderScanner.Event.Batch>().flatMap { it.entries }
        assertEquals(listOf("DCIM"), top.map { it.name })
        assertEquals(MediaType.FOLDER, top[0].type)
        val inside = scan("$root/DCIM").filterIsInstance<FolderScanner.Event.Batch>().flatMap { it.entries }.associateBy { it.name }
        assertEquals(setOf("clip", "song", "a.gif"), inside.keys)
        assertEquals(MediaType.VIDEO, inside["clip"]!!.type)
        assertEquals(OtherKind.AUDIO, inside["song"]!!.other)
        assertEquals(MediaType.ANIMATED, inside["a.gif"]!!.type)
        assertEquals(4, scan("$root/DCIM", hidden = true).filterIsInstance<FolderScanner.Event.Batch>().sumOf { it.entries.size })
    }

    @Test
    fun `a folder never listed in this session is found by its path`() {
        val a = dev.folder(S, MtpFormats.ROOT, "A")
        val b = dev.folder(S, a, "B")
        dev.file(S, b, "x.jpg")
        val entries = scan("$root/A/B").filterIsInstance<FolderScanner.Event.Batch>().flatMap { it.entries }
        assertEquals(listOf("$root/A/B/x.jpg"), entries.map { it.path })
        assertEquals(listOf(FolderScanner.Event.Failed("not found")), scan("$root/A/missing"))
    }

    @Test
    fun `a listing that fails part way keeps what it read, one that fails at once reports it`() {
        val handles = (0 until 40).map { dev.file(S, MtpFormats.ROOT, "f$it.jpg") }
        dev.fail = { op, h -> op == "objectInfo" && h == handles[20] }
        val events = scan()
        assertTrue(events.none { it is FolderScanner.Event.Failed })
        assertEquals(16, events.filterIsInstance<FolderScanner.Event.Batch>().sumOf { it.entries.size })
        dev.fail = { op, _ -> op == "objectHandles" }
        assertTrue(scan().single() is FolderScanner.Event.Failed)
    }

    @Test
    fun `a call that hangs marks the device not responding, and later calls fail at once`() {
        dev.file(S, MtpFormats.ROOT, "a.jpg")
        dev.hang += "objectInfo"
        val events = scan()
        assertEquals(listOf(FolderScanner.Event.Failed("Device not responding")), events)
        assertTrue(conn.hung)
        val t0 = System.nanoTime()
        try {
            runBlocking { conn.storages() }
            fail("storages() should fail")
        } catch (e: IOException) {
            assertEquals("Device not responding", e.message)
        }
        assertTrue("failed at once", System.nanoTime() - t0 < 100_000_000L)
        assertFalse("no call reached the device", "storageIds" in dev.calls)
    }

    @Test
    fun `a slow call within its watchdog is not a hang`() {
        dev.file(S, MtpFormats.ROOT, "a.jpg")
        dev.delayMs["objectInfo"] = 150
        assertEquals(1, scan().filterIsInstance<FolderScanner.Event.Batch>().sumOf { it.entries.size })
        assertFalse(conn.hung)
    }

    @Test
    fun `a caller that leaves does not mark the device hung`() = runBlocking {
        dev.file(S, MtpFormats.ROOT, "a.jpg")
        dev.delayMs["objectHandles"] = 200
        val job = launch { conn.scan(root, false).toList() }
        delay(50)
        job.cancelAndJoin()
        delay(300)
        assertFalse(conn.hung)
        assertEquals(1, conn.list(root).size)
    }

    @Test
    fun `thumbnails are the device's own, or none`() = runBlocking {
        val thumb = byteArrayOf(1, 2, 3)
        dev.file(S, MtpFormats.ROOT, "a.jpg", thumb = thumb)
        dev.file(S, MtpFormats.ROOT, "b.jpg")
        conn.list(root)
        assertArrayEquals(thumb, conn.thumbnail("$root/a.jpg"))
        assertNull(conn.thumbnail("$root/b.jpg"))
        assertEquals(false, conn.hasThumbnail("$root/b.jpg"))
        assertEquals(1, dev.calls.count { it == "thumbnail" })
    }

    @Test
    fun `downloads go in 1 MB chunks through a part file`() = runBlocking {
        val data = ByteArray(2_500_000) { (it % 251).toByte() }
        dev.file(S, MtpFormats.ROOT, "v.mp4", data)
        conn.list(root)
        val dest = File(tmp, "out/v.mp4")
        val progress = ArrayList<Long>()
        conn.download("$root/v.mp4", dest) { done, _ -> progress.add(done) }
        assertArrayEquals(data, dest.readBytes())
        assertEquals(3, dev.calls.count { it == "readPartial" })
        assertEquals(listOf(1_048_576L, 2_097_152L, 2_500_000L), progress)
        assertEquals(listOf("v.mp4"), dest.parentFile.list()!!.toList())
    }

    @Test
    fun `a stopped download leaves nothing behind`() = runBlocking {
        dev.file(S, MtpFormats.ROOT, "v.mp4", ByteArray(5_000_000))
        dev.delayMs["readPartial"] = 60
        conn.list(root)
        val dest = File(tmp, "v.mp4")
        val job = launch { conn.download("$root/v.mp4", dest) }
        delay(100)
        job.cancelAndJoin()
        assertEquals(emptyList<String>(), tmp.list()!!.toList())
    }

    @Test
    fun `without partial reads the whole file comes in one call`() = runBlocking {
        val d = FakeMtpDriver(canReadPartially = false).apply { storage(S) }
        d.file(S, MtpFormats.ROOT, "a.jpg", byteArrayOf(9, 8, 7))
        val c = MtpConnection(D, d, fast)
        c.list(root)
        c.download("$root/a.jpg", File(tmp, "a.jpg"))
        assertArrayEquals(byteArrayOf(9, 8, 7), File(tmp, "a.jpg").readBytes())
        assertEquals(1, d.calls.count { it == "importFile" })
        c.close()
    }

    @Test
    fun `delete removes a file, and empties a folder the device won't delete whole`() = runBlocking {
        val f = dev.folder(S, MtpFormats.ROOT, "F")
        val sub = dev.folder(S, f, "sub")
        dev.file(S, sub, "x.jpg")
        dev.file(S, f, "y.jpg")
        dev.file(S, MtpFormats.ROOT, "z.jpg")
        conn.list(root)
        conn.delete("$root/z.jpg")
        assertNull(dev.byName(MtpFormats.ROOT, "z.jpg"))
        dev.deletesFullFolders = false
        conn.delete("$root/F")
        assertEquals(0, dev.nodes.size)
        assertEquals(0, conn.list(root).size)
    }

    @Test(expected = IOException::class)
    fun `a refused delete is an error`() = runBlocking {
        dev.file(S, MtpFormats.ROOT, "a.jpg")
        conn.list(root)
        dev.fail = { op, _ -> op == "delete" }
        conn.delete("$root/a.jpg")
    }

    @Test
    fun `new folders and uploads get (1) names, compared without case`() = runBlocking {
        dev.file(S, MtpFormats.ROOT, "Photo.JPG")
        dev.folder(S, MtpFormats.ROOT, "Trip")
        val src = File(tmp, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        assertEquals("$root/photo (1).jpg", conn.upload(src, root))
        assertArrayEquals(byteArrayOf(1, 2), dev.byName(MtpFormats.ROOT, "photo (1).jpg")!!.data)
        assertEquals(MtpFormats.EXIF_JPEG, dev.byName(MtpFormats.ROOT, "photo (1).jpg")!!.info.format)
        try {
            conn.mkdir(root, "trip")
            fail("an existing folder")
        } catch (e: IOException) {
            assertEquals("already exists", e.message)
        }
        assertEquals("$root/trip (1)", conn.mkdir(root, "trip", freeName = true))
        val made = conn.mkdir(root, "New")
        assertEquals(MtpFormats.ASSOCIATION, dev.byName(MtpFormats.ROOT, "New")!!.info.format)
        assertEquals("$root/New/photo.jpg", conn.upload(src, made))
    }

    @Test
    fun `an upload stopped while it runs is deleted on the device`() = runBlocking {
        dev.delayMs["send"] = 150
        val src = File(tmp, "a.jpg").apply { writeBytes(ByteArray(100)) }
        conn.list(root)
        val job = async(start = CoroutineStart.UNDISPATCHED) { conn.upload(src, root) }
        delay(60)
        job.cancelAndJoin()
        delay(250)
        assertEquals(0, dev.nodes.size)
        assertFalse(conn.hung)
    }

    @Test
    fun `device events become changes of listed folders`() = runBlocking {
        val dcim = dev.folder(S, MtpFormats.ROOT, "DCIM")
        val other = dev.folder(S, MtpFormats.ROOT, "Other")
        val old = dev.file(S, dcim, "old.jpg")
        conn.list(root)
        conn.list("$root/DCIM")
        conn.startEvents()
        val seen = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { conn.changes.first { it is MtpConnection.Change.Added } } }
        val h = dev.file(S, dcim, "new.jpg")
        dev.event(MtpFormats.EVENT_OBJECT_ADDED, h)
        val added = seen.await() as MtpConnection.Change.Added
        assertEquals("$root/DCIM", added.dir)
        assertEquals("$root/DCIM/new.jpg", added.entry.path)

        val gone = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { conn.changes.first { it !is MtpConnection.Change.Added } } }
        // A file in a folder never listed is nobody's business.
        val hidden = dev.file(S, dev.folder(S, other, "Deep"), "x.jpg")
        dev.event(MtpFormats.EVENT_OBJECT_ADDED, hidden)
        dev.remove(old)
        dev.event(MtpFormats.EVENT_OBJECT_REMOVED, old)
        assertEquals(MtpConnection.Change.Removed("$root/DCIM/old.jpg"), gone.await())

        val storages = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { conn.changes.first { it == MtpConnection.Change.Storages } } }
        dev.event(MtpFormats.EVENT_STORE_ADDED, 0x20001)
        assertTrue(storages.await() == MtpConnection.Change.Storages)
    }

    @Test
    fun `folder previews are the first media files with a device thumbnail`() = runBlocking {
        val f = dev.folder(S, MtpFormats.ROOT, "F")
        dev.file(S, f, "doc.pdf", thumb = byteArrayOf(1))
        dev.file(S, f, "nothumb.jpg")
        repeat(10) { dev.file(S, f, "p$it.jpg", thumb = byteArrayOf(1)) }
        val (candidates, count) = conn.folderPreview("$root/F", max = 8)
        assertEquals(12, count)
        assertEquals((0 until 8).map { "p$it.jpg" }, candidates.map(FileEntry::name))
        assertEquals(emptyList<FileEntry>() to 0, conn.folderPreview("$root/missing"))
    }

    @Test
    fun `calls after close fail as disconnected`() = runBlocking {
        conn.close()
        try {
            conn.storages()
            fail("closed")
        } catch (e: IOException) {
            assertEquals("Device disconnected", e.message)
        }
    }

    companion object {
        const val D = 3
        const val S = 65537
    }
}
