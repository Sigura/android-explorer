package com.mediaviewer.data.mtp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException

/**
 * One device file read as a stream (todo §11.4): what a player needs to play a long video or an
 * audio book from a camera or phone without copying it first. Reads come from a read-ahead window
 * filled by partial reads of [chunkSize] bytes, at most [aheadBytes] past the read position, so the
 * device works while the player decodes. A seek inside the window just moves; one outside drops it
 * and starts a new partial read at the new offset. A slow device only makes reads wait; a failed
 * or unplugged one fails the read that needs the missing bytes with an [IOException].
 *
 * Pure Kotlin: [readChunk] is one partial read (`MtpConnection.readAt`), [scope] runs the fetches.
 */
class MtpStream(
    val length: Long,
    private val scope: CoroutineScope,
    private val chunkSize: Int = CHUNK,
    private val aheadBytes: Long = AHEAD,
    /** Up to `size` bytes at `offset`; fewer only at the end of the file, none = a read error. */
    private val readChunk: suspend (offset: Long, size: Int) -> ByteArray,
) : Closeable {
    private class Chunk(val offset: Long, val data: ByteArray) {
        val end: Long get() = offset + data.size
    }

    private val lock = Any()
    private val chunks = ArrayDeque<Chunk>()
    private var position = 0L

    /** Where the window ends: the next partial read starts here. */
    private var fetchFrom = 0L
    private var error: IOException? = null
    private var fetcher: Job? = null
    private var closed = false

    /** Bumped on every new chunk, failure or seek, for readers waiting on the fetcher. */
    private val changed = MutableStateFlow(0L)

    /** Partial reads asked of the device so far (for tests and the log). */
    @Volatile var requests = 0
        private set

    val readPosition: Long get() = synchronized(lock) { position }

    /** Bytes in the window, from the read position on. */
    val buffered: Long get() = synchronized(lock) { (chunks.lastOrNull()?.end ?: position) - position }

    /** Moves the read position to [pos]: kept window if it is inside it, else a new partial read there. */
    fun seek(pos: Long) {
        require(pos in 0..length) { "seek to $pos of $length" }
        synchronized(lock) {
            position = pos
            while (chunks.isNotEmpty() && chunks.first().end <= pos) chunks.removeFirst()
            val first = chunks.firstOrNull()
            if (first == null || first.offset > pos) {
                chunks.clear()
                error = null
                fetcher?.cancel()
                fetcher = null
                fetchFrom = pos
            }
        }
        changed.update { it + 1 }
        fill()
    }

    /**
     * Copies up to [len] bytes at the read position into [dst], waiting for the window if needed.
     * Returns the count, or -1 at the end of the file.
     */
    suspend fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val seen = changed.value
            var n = -2
            synchronized(lock) {
                if (closed) throw IOException("closed")
                if (position >= length) return -1
                val c = chunks.firstOrNull { position >= it.offset && position < it.end }
                if (c != null) {
                    val from = (position - c.offset).toInt()
                    n = minOf(len.toLong(), c.end - position).toInt()
                    System.arraycopy(c.data, from, dst, off, n)
                    position += n
                    while (chunks.isNotEmpty() && chunks.first().end <= position) chunks.removeFirst()
                } else {
                    error?.let { throw IOException(it.message, it) }
                }
            }
            fill()
            if (n >= 0) return n
            changed.first { it != seen }
        }
    }

    /** Starts the fetcher when the window has room and it isn't running. */
    private fun fill() {
        synchronized(lock) {
            if (closed || error != null || fetcher?.isActive == true) return
            if (fetchFrom >= length || fetchFrom - position >= aheadBytes) return
            fetcher = scope.launch { fetch() }
        }
    }

    private suspend fun fetch() {
        while (true) {
            val from: Long
            val size: Int
            synchronized(lock) {
                if (closed || fetchFrom >= length || fetchFrom - position >= aheadBytes) return
                from = fetchFrom
                size = minOf(chunkSize.toLong(), length - from).toInt()
            }
            requests++
            val data = try {
                readChunk(from, size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                fail(from, e)
                return
            }
            synchronized(lock) {
                // A seek moved the window meanwhile: this read belongs to the old one.
                if (fetchFrom != from) return
                if (data.isEmpty()) {
                    error = IOException("read error at $from")
                } else {
                    chunks.addLast(Chunk(from, data))
                    fetchFrom = from + data.size
                }
            }
            changed.update { it + 1 }
            if (data.isEmpty()) return
        }
    }

    private fun fail(from: Long, e: IOException) {
        synchronized(lock) {
            if (fetchFrom != from) return
            error = e
        }
        changed.update { it + 1 }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            chunks.clear()
            fetcher?.cancel()
        }
        changed.update { it + 1 }
    }

    companion object {
        const val CHUNK = 1024 * 1024
        const val AHEAD = 4L * 1024 * 1024
    }
}
