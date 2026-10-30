package com.mediaviewer.data.mtp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections

/** [MtpStream] alone, and through [MtpConnection.stream] against [FakeMtpDriver] (todo §11.4). */
class MtpStreamTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val data = ByteArray(1_000_003) { (it * 31 + it / 7).toByte() }

    /** Offsets asked for, in order. */
    private val asked: MutableList<Long> = Collections.synchronizedList(ArrayList())

    private fun stream(chunk: Int = 64 * 1024, ahead: Long = 256 * 1024L, delayMs: Long = 0, failAt: Long = -1) =
        MtpStream(data.size.toLong(), scope, chunk, ahead) { off, size ->
            asked.add(off)
            if (delayMs > 0) delay(delayMs)
            if (off == failAt) throw IOException("read error")
            data.copyOfRange(off.toInt(), (off + size).toInt())
        }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun readAll(s: MtpStream, step: Int = 10_000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(step)
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `sequential reads return the file, chunk after chunk`() = runBlocking {
        val s = stream()
        assertArrayEquals(data, withTimeout(10_000) { readAll(s) })
        // Every chunk once, in order, from the start.
        val chunks = (data.size + 64 * 1024 - 1) / (64 * 1024)
        assertEquals(chunks, asked.size)
        assertEquals((0 until chunks).map { it * 64 * 1024L }, asked.toList())
        assertEquals(-1, s.read(ByteArray(10), 0, 10))
    }

    @Test
    fun `the window never runs further ahead than allowed`() = runBlocking {
        val s = stream(chunk = 64 * 1024, ahead = 256 * 1024L)
        val one = ByteArray(1)
        assertEquals(1, s.read(one, 0, 1))
        delay(200) // the fetcher fills the window and stops
        assertTrue("asked ${asked.size}", asked.size in 4..5)
        assertTrue(s.buffered <= 256 * 1024L + 64 * 1024L)
        // Reading on lets it go on.
        val buf = ByteArray(200 * 1024)
        var got = 0
        while (got < buf.size) got += s.read(buf, got, buf.size - got)
        delay(200)
        assertTrue(asked.size > 5)
        assertEquals(data[1], buf[0])
    }

    @Test
    fun `a seek inside the window reads no more, one outside starts there`() = runBlocking {
        val s = stream()
        val buf = ByteArray(1000)
        s.read(buf, 0, 1)
        delay(200)
        val before = asked.size
        s.seek(100_000) // inside the 256 KB window
        assertEquals(1000, readFully(s, buf))
        assertArrayEquals(data.copyOfRange(100_000, 101_000), buf)
        assertEquals(before, asked.size)
        // Far away: a new partial read at exactly the new offset.
        s.seek(900_000)
        assertEquals(1000, readFully(s, buf))
        assertArrayEquals(data.copyOfRange(900_000, 901_000), buf)
        assertTrue(900_000L in asked)
        assertEquals(900_000L, asked[before])
        // And back to the start.
        s.seek(0)
        assertEquals(1000, readFully(s, buf))
        assertArrayEquals(data.copyOfRange(0, 1000), buf)
    }

    private suspend fun readFully(s: MtpStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = s.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    @Test
    fun `a slow device only makes reads wait`() = runBlocking {
        val s = stream(chunk = 256 * 1024, delayMs = 40)
        assertArrayEquals(data, withTimeout(10_000) { readAll(s) })
    }

    @Test
    fun `a failed read fails only the bytes that are missing`() = runBlocking {
        val s = stream(chunk = 64 * 1024, failAt = 3 * 64 * 1024L)
        val buf = ByteArray(3 * 64 * 1024)
        assertEquals(buf.size, readFully(s, buf)) // the first three chunks arrived
        try {
            s.read(ByteArray(10), 0, 10)
            fail("read past a failed chunk")
        } catch (e: IOException) {
        }
        // A seek away from the failure reads again.
        s.seek(500_000)
        assertEquals(10, readFully(s, ByteArray(10)))
    }

    @Test
    fun `through the connection, and unplugging mid-read fails the read`() = runBlocking {
        val dev = FakeMtpDriver().apply { storage(S) }
        val big = ByteArray(5 * 1024 * 1024) { it.toByte() }
        dev.file(S, MtpFormats.ROOT, "clip.mp4", big)
        val conn = MtpConnection(D, dev, MtpConnection.Timeouts(call = 2_000, list = 2_000, thumb = 2_000, chunk = 2_000))
        val path = MtpPaths.child(MtpPaths.storageRoot(D, S), "clip.mp4")
        conn.list(MtpPaths.storageRoot(D, S))
        assertTrue(conn.streams(path))
        val s = conn.stream(path, scope)!!
        assertEquals(big.size.toLong(), s.length)
        val buf = ByteArray(1024 * 1024)
        assertEquals(buf.size, readFully(s, buf))
        assertArrayEquals(big.copyOfRange(0, buf.size), buf)
        assertTrue(dev.calls.count { it == "readPartial" } >= 1)
        // The device answers slowly, then is unplugged while the player waits.
        dev.delayMs["readPartial"] = 300
        s.seek(4 * 1024 * 1024L)
        val reader = scope.launch {
            try {
                readFully(s, ByteArray(512 * 1024))
                fail("read from an unplugged device")
            } catch (e: IOException) {
            }
        }
        delay(100)
        conn.close()
        withTimeout(5_000) { reader.join() }
    }

    @Test
    fun `devices without partial reads are not streamed`() = runBlocking {
        val dev = FakeMtpDriver(canReadPartially = false).apply { storage(S) }
        dev.file(S, MtpFormats.ROOT, "clip.mp4", ByteArray(100))
        val conn = MtpConnection(D, dev)
        val root = MtpPaths.storageRoot(D, S)
        conn.list(root)
        val path = MtpPaths.child(root, "clip.mp4")
        assertEquals(false, conn.streams(path))
        assertNull(conn.stream(path, scope))
        conn.close()
    }

    private companion object {
        const val D = "7"
        const val S = 0x10001
    }
}
