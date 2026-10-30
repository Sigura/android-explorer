package com.mediaviewer.data.mtp

import android.os.SystemClock
import android.util.Log
import com.mediaviewer.data.fs.FileOps
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * One open MTP / PTP device (todo §9.3). A device answers one request at a time and `MtpDevice`
 * is not thread-safe, so every call runs on this connection's own thread, one after another.
 * Listing, thumbnails and downloads are split into short calls, so a thumbnail never waits for a
 * whole video to download. Each call has a watchdog: a device that stops answering is marked
 * [hung] and every later call fails at once with "Device not responding" (README §2.1).
 * Device events (todo §9.8 item 3) are read on a thread of their own and come out as [changes].
 * Pure Kotlin over an [MtpDriver], so tests run it against a fake device.
 */
class MtpConnection(
    /** The device's key in paths (`MtpPaths.deviceKey`). */
    val key: String,
    private val device: MtpDriver,
    private val timeouts: Timeouts = Timeouts(),
) {
    class Storage(val id: Int, val label: String, val freeBytes: Long, val totalBytes: Long)

    /** Watchdogs of the calls, in ms (todo §9.3); tests shorten them. */
    data class Timeouts(
        val call: Long = 8_000L,
        val list: Long = 30_000L,
        val thumb: Long = 10_000L,
        val chunk: Long = 20_000L,
    )

    /** What a device event changed (todo §9.8 item 3). */
    sealed interface Change {
        /** A new or changed object in [dir]; [entry] replaces an entry with the same path. */
        data class Added(val dir: String, val entry: FileEntry) : Change
        data class Removed(val path: String) : Change

        /** Storages added or removed, or their free space changed. */
        data object Storages : Change
    }

    /** What the listing learnt about a file: enough for thumbnails and downloads without asking again. */
    private class Obj(val handle: Int, val storageId: Int, val folder: Boolean, val size: Long, val hasThumb: Boolean)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mtp-$key").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val objects = ConcurrentHashMap<String, Obj>()

    /** Handle → path of every object listed, for device events, which name only the handle. */
    private val paths = ConcurrentHashMap<Int, String>()

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 256)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()
    private var events: Thread? = null
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var hung = false
        private set

    @Volatile private var closed = false

    /** Runs [block] on the device thread with a watchdog. Throws [IOException] on timeout or failure. */
    private suspend fun <T> call(timeoutMs: Long = timeouts.call, block: (MtpDriver) -> T): T {
        if (closed) throw IOException("Device disconnected")
        if (hung) throw IOException("Device not responding")
        val job = scope.async { block(device) }
        try {
            return withTimeout(timeoutMs) { job.await() }
        } catch (e: TimeoutCancellationException) {
            hung = true
            job.cancel()
            throw IOException("Device not responding")
        } catch (e: CancellationException) {
            // The caller left: a call still in the queue never runs.
            job.cancel()
            if (closed) throw IOException("Device disconnected")
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    suspend fun storages(): List<Storage> = call { d ->
        (d.storageIds() ?: IntArray(0)).toList().mapNotNull { id ->
            val info = d.storageInfo(id) ?: return@mapNotNull null
            val label = info.description?.takeIf { it.isNotBlank() } ?: info.volumeId?.takeIf { it.isNotBlank() } ?: "Storage"
            Storage(id, label, info.freeBytes, info.totalBytes)
        }
    }

    /**
     * Streaming listing of an MTP folder, in the same batches as [FolderScanner.scan]. The handles
     * come in one call; the file details are read [INFO_BATCH] at a time.
     */
    fun scan(dir: String, showHidden: Boolean): Flow<FolderScanner.Event> = flow {
        val parts = MtpPaths.parse(dir)
        val handles = try {
            if (parts == null) null else handlesOf(dir, parts)
        } catch (e: IOException) {
            emit(FolderScanner.Event.Failed(e.message ?: "read error"))
            return@flow
        }
        if (handles == null) {
            emit(FolderScanner.Event.Failed("not found"))
            return@flow
        }
        var last = SystemClock.uptimeMillis()
        var listed = 0
        val batch = ArrayList<FileEntry>()
        for (chunk in handles.asList().chunked(INFO_BATCH)) {
            coroutineContext.ensureActive()
            val infos = try {
                call { d -> chunk.mapNotNull { h -> d.objectInfo(h) } }
            } catch (e: IOException) {
                Log.w(TAG, "Listing $dir stopped early", e)
                if (listed == 0) emit(FolderScanner.Event.Failed(e.message ?: "read error"))
                break
            }
            for (info in infos) {
                val entry = remember(dir, info, showHidden) ?: continue
                batch.add(entry)
                listed++
                if (batch.size >= BATCH_SIZE) {
                    emit(FolderScanner.Event.Batch(ArrayList(batch)))
                    batch.clear()
                    last = SystemClock.uptimeMillis()
                }
            }
            val now = SystemClock.uptimeMillis()
            if (batch.size >= BATCH_SIZE || now - last >= BATCH_MS) {
                emit(FolderScanner.Event.Batch(ArrayList(batch)))
                batch.clear()
                last = now
            }
        }
        if (batch.isNotEmpty()) emit(FolderScanner.Event.Batch(batch))
    }

    /** The whole folder at once (copies, deletes, the folder picker); throws when it can't be read. */
    suspend fun list(dir: String, showHidden: Boolean = true): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        scan(dir, showHidden).collect { ev ->
            when (ev) {
                is FolderScanner.Event.Batch -> out.addAll(ev.entries)
                is FolderScanner.Event.Failed -> throw IOException(ev.reason)
            }
        }
        return out
    }

    /** Handles of the objects in [dir], or null when [dir] isn't a folder on this device. */
    private suspend fun handlesOf(dir: String, parts: MtpPaths.Parts): IntArray? {
        val parent = if (parts.isStorageRoot) MtpFormats.ROOT else resolve(dir)?.takeIf { it.folder }?.handle ?: return null
        return call(timeouts.list) { it.objectHandles(parts.storageId, parent) } ?: IntArray(0)
    }

    private fun remember(dir: String, info: MtpDriver.ObjectInfo, showHidden: Boolean): FileEntry? {
        val name = info.name ?: return null
        val path = MtpPaths.child(dir, name)
        val folder = info.format == MtpFormats.ASSOCIATION
        objects[path] = Obj(info.handle, info.storageId, folder, info.size, info.thumbSize > 0)
        paths[info.handle] = path
        if (!showHidden && FolderScanner.isHidden(name)) return null
        if (folder) return FileEntry(path, MtpPaths.safeName(name), MediaType.FOLDER, 0, info.modified)
        val kind = FileTypes.byExtension(name) ?: kindOf(info.format)
        return FileEntry(path, MtpPaths.safeName(name), kind.type, info.size, info.modified, kind.other)
    }

    private fun forget(path: String) {
        objects.remove(path)?.let { paths.remove(it.handle) }
        val prefix = "$path/"
        objects.keys.filter { it.startsWith(prefix) }.forEach { k -> objects.remove(k)?.let { paths.remove(it.handle) } }
    }

    /**
     * The object behind [path]. Paths from this session's listings are known; any other one (a
     * breadcrumb after the app was recreated) is found by listing each folder on the way down.
     */
    private suspend fun resolve(path: String): Obj? {
        objects[path]?.let { return it }
        val parts = MtpPaths.parse(path) ?: return null
        var dir = MtpPaths.storageRoot(parts.device, parts.storageId)
        var handle = MtpFormats.ROOT
        for (seg in parts.segments) {
            val target = MtpPaths.child(dir, seg)
            if (objects[target] == null) {
                val handles = call(timeouts.list) { it.objectHandles(parts.storageId, handle) } ?: return null
                for (chunk in handles.asList().chunked(INFO_BATCH)) {
                    val infos = call { d -> chunk.mapNotNull { h -> d.objectInfo(h) } }
                    infos.forEach { remember(dir, it, showHidden = true) }
                    if (objects[target] != null) break
                }
            }
            handle = objects[target]?.handle ?: return null
            dir = target
        }
        return objects[path]
    }

    /** The device's own thumbnail (a small JPEG made by the camera or phone), or null if it has none. */
    suspend fun thumbnail(path: String): ByteArray? {
        val o = resolve(path) ?: return null
        if (o.folder || !o.hasThumb) return null
        return call(timeouts.thumb) { it.thumbnail(o.handle) }
    }

    /**
     * Folder collage candidates (todo §9.8 item 5): the first media files in [dir] that have a
     * device thumbnail, at most [max], looking at no more than [PREVIEW_LOOK] objects. Returns
     * them with the folder's item count.
     */
    suspend fun folderPreview(dir: String, max: Int = PREVIEW_MAX): Pair<List<FileEntry>, Int> {
        val parts = MtpPaths.parse(dir) ?: return emptyList<FileEntry>() to 0
        val handles = handlesOf(dir, parts) ?: return emptyList<FileEntry>() to 0
        val found = ArrayList<FileEntry>()
        for (chunk in handles.asList().take(PREVIEW_LOOK).chunked(INFO_BATCH)) {
            val infos = call { d -> chunk.mapNotNull { h -> d.objectInfo(h) } }
            for (info in infos) {
                val e = remember(dir, info, showHidden = false) ?: continue
                if (e.isMedia && info.thumbSize > 0) found.add(e)
            }
            if (found.size >= max) break
        }
        return found.take(max) to handles.size
    }

    /**
     * Copies [path] to [dest] through a `.part` file renamed at the end, so the folder watcher of a
     * local listing sees the file only once it is complete. Cancellable between 1 MB chunks; a
     * cancelled or failed download leaves nothing behind.
     */
    suspend fun download(path: String, dest: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val o = resolve(path) ?: throw IOException("not found")
        if (o.folder) throw IOException("is a folder")
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, ".${dest.name}.part")
        try {
            if (device.canReadPartially && o.size in 0 until 0xFFFF_FFFFL) {
                RandomAccessFile(tmp, "rw").use { out ->
                    out.setLength(0)
                    val buf = ByteArray(CHUNK)
                    var off = 0L
                    while (off < o.size) {
                        coroutineContext.ensureActive()
                        val want = minOf(CHUNK.toLong(), o.size - off)
                        val n = call(timeouts.chunk) { it.readPartial(o.handle, off, want, buf) }
                        if (n <= 0) throw IOException("read error")
                        out.write(buf, 0, n.toInt())
                        off += n
                        onProgress(off, o.size)
                    }
                }
            } else {
                // Whole-file transfer: one call, so its watchdog grows with the size (≥ 1 MB/s).
                if (!call(timeouts.chunk + o.size / 1000) { it.importFile(o.handle, tmp.path) }) throw IOException("read error")
                onProgress(o.size, o.size)
            }
            if (!tmp.renameTo(dest)) throw IOException("can't write ${dest.name}")
        } finally {
            tmp.delete()
        }
    }

    /**
     * [path] as a stream for players (todo §11.4): partial reads through a read-ahead window, run
     * in [scope]. Null when the device can't read it partially (no GetPartialObject, or a file past
     * 4 GB without GetPartialObject64): such files go through the mirror instead.
     */
    suspend fun stream(path: String, scope: CoroutineScope): MtpStream? {
        val o = resolve(path) ?: throw IOException("not found")
        if (o.folder) throw IOException("is a folder")
        if (!canStream(o.size)) return null
        val handle = o.handle
        return MtpStream(o.size, scope) { offset, size -> readAt(handle, offset, size) }
    }

    /** Whether [path] (as listed) can be streamed; unknown files: whether the device reads partially at all. */
    fun streams(path: String): Boolean = objects[path]?.let { !it.folder && canStream(it.size) } ?: device.canReadPartially

    private fun canStream(size: Long): Boolean = device.canReadPartially && size >= 0 && (size < MAX_32 || device.canReadPartially64)

    /** One partial read of up to [size] bytes at [offset], under the chunk watchdog. */
    private suspend fun readAt(handle: Int, offset: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        val n = call(timeouts.chunk) { it.readPartial(handle, offset, size.toLong(), buf) }
        return if (n == size.toLong()) buf else buf.copyOf(n.coerceIn(0, size.toLong()).toInt())
    }

    /**
     * Deletes [path] on the device (todo §9.8 item 1). A folder the device won't delete in one go
     * is emptied first (**verify**: phones delete folders with their content, some cameras don't).
     */
    suspend fun delete(path: String) {
        val o = resolve(path) ?: throw IOException("not found")
        if (call(timeouts.list) { it.delete(o.handle) }) return forget(path)
        if (!o.folder) throw IOException("the device didn't delete it")
        for (child in list(path)) delete(child.path)
        if (!call(timeouts.list) { it.delete(o.handle) }) throw IOException("the device didn't delete it")
        forget(path)
    }

    /** Names in [dir], for `name (1)` choices; compared without case, as most device storages do. */
    private suspend fun takenNames(dir: String): Set<String> = list(dir).mapTo(HashSet()) { it.name.lowercase() }

    /** The object handle of folder [dir] to create objects in. */
    private suspend fun folderHandle(dir: String): Pair<Int, Int> {
        val parts = MtpPaths.parse(dir) ?: throw IOException("not a device folder")
        if (parts.isStorageRoot) return parts.storageId to MtpFormats.ROOT
        val o = resolve(dir)?.takeIf { it.folder } ?: throw IOException("folder not found")
        return parts.storageId to o.handle
    }

    /**
     * A new folder in [dir] (todo §9.8 item 2). [freeName]: a clash gets a `(1)` name (copies);
     * otherwise it fails with "already exists" (*New folder*). Returns the new folder's path.
     */
    suspend fun mkdir(dir: String, name: String, freeName: Boolean = false): String {
        val (storage, parent) = folderHandle(dir)
        val taken = takenNames(dir)
        val safe = MtpPaths.safeName(name)
        if (!freeName && safe.lowercase() in taken) throw IOException("already exists")
        val chosen = FileOps.freeName(safe) { it.lowercase() in taken }
        val info = MtpDriver.ObjectInfo(0, storage, parent, chosen, MtpFormats.ASSOCIATION, 0, System.currentTimeMillis(), 0)
        val sent = call { it.send(info, null) } ?: throw IOException("the device didn't create the folder")
        return addSent(dir, sent, chosen)
    }

    /**
     * Sends local file [src] into [dir] under a free name (todo §9.8 item 2). The transfer is one
     * call (the platform has no partial send), so its watchdog grows with the size (≥ 1 MB/s) and
     * Stop takes effect when it ends: a file whose copy was stopped is deleted on the device.
     */
    suspend fun upload(src: File, dir: String, name: String = src.name): String {
        val (storage, parent) = folderHandle(dir)
        val taken = takenNames(dir)
        val chosen = FileOps.freeName(MtpPaths.safeName(name)) { it.lowercase() in taken }
        val size = src.length()
        val info = MtpDriver.ObjectInfo(0, storage, parent, chosen, MtpFormats.forName(chosen), size, src.lastModified(), 0)
        val stopped = AtomicBoolean(false)
        val sent = try {
            call(timeouts.chunk + size / 1000) { d ->
                val s = d.send(info, src)
                if (s != null && stopped.get()) {
                    d.delete(s.handle)
                    null
                } else {
                    s
                }
            }
        } catch (e: CancellationException) {
            stopped.set(true)
            throw e
        } ?: throw IOException("the device didn't take ${src.name}")
        return addSent(dir, sent, chosen)
    }

    private fun addSent(dir: String, sent: MtpDriver.ObjectInfo, name: String): String {
        // Some devices answer without the name (**verify**); the one asked for is the one created.
        val info = if (sent.name.isNullOrEmpty()) sent.copy(name = name) else sent
        val entry = remember(dir, info, showHidden = true) ?: return MtpPaths.child(dir, name)
        _changes.tryEmit(Change.Added(dir, entry))
        return entry.path
    }

    /** Size of [path] as the device reported it, or -1 if unknown. */
    fun sizeOf(path: String): Long = objects[path]?.size ?: -1L

    /** Whether [path] has a device thumbnail; null when it hasn't been listed. */
    fun hasThumbnail(path: String): Boolean? = objects[path]?.hasThumb

    // ---------------------------------------------------------------- events

    /**
     * Starts reading device events on a thread of their own (todo §9.8 item 3): objects added,
     * removed or changed come out as [changes] with their paths, storages as [Change.Storages].
     * Objects in folders never listed are ignored: nothing shows them. A device that doesn't send
     * events (an error at once, **verify** on PTP cameras) just has no live updates.
     */
    fun startEvents() {
        if (events != null || closed) return
        events = Thread({
            var errors = 0
            while (!closed && !hung) {
                val ev = try {
                    device.readEvent()
                } catch (e: Exception) {
                    if (closed) break
                    Log.w(TAG, "MTP event read failed", e)
                    if (++errors >= MAX_EVENT_ERRORS) break
                    try {
                        Thread.sleep(EVENT_RETRY_MS)
                    } catch (ie: InterruptedException) {
                        break
                    }
                    continue
                }
                if (ev == null) {
                    if (closed) break
                    continue
                }
                errors = 0
                eventScope.launch { handle(ev) }
            }
        }, "mtp-events-$key").apply {
            isDaemon = true
            start()
        }
    }

    private suspend fun handle(ev: MtpDriver.Event) {
        when (ev.code) {
            MtpFormats.EVENT_OBJECT_ADDED, MtpFormats.EVENT_OBJECT_INFO_CHANGED -> {
                val info = try {
                    call { it.objectInfo(ev.param) }
                } catch (e: IOException) {
                    null
                } ?: return
                val dir = if (info.parent == MtpFormats.ROOT || info.parent == 0) MtpPaths.storageRoot(key, info.storageId)
                else paths[info.parent] ?: return
                // A rename leaves the old name behind.
                paths[info.handle]?.let { old -> if (MtpPaths.parent(old) == dir && old != MtpPaths.child(dir, info.name ?: "")) removed(old) }
                val entry = remember(dir, info, showHidden = true) ?: return
                _changes.emit(Change.Added(dir, entry))
            }
            MtpFormats.EVENT_OBJECT_REMOVED -> paths[ev.param]?.let { removed(it) }
            MtpFormats.EVENT_STORE_ADDED, MtpFormats.EVENT_STORE_REMOVED, MtpFormats.EVENT_STORAGE_INFO_CHANGED ->
                _changes.emit(Change.Storages)
        }
    }

    private suspend fun removed(path: String) {
        forget(path)
        _changes.emit(Change.Removed(path))
    }

    fun close() {
        closed = true
        scope.cancel()
        eventScope.cancel()
        // Closing from another thread also ends a call stuck on the device thread and the event read.
        device.close()
        events?.interrupt()
        executor.shutdownNow()
    }

    companion object {
        private const val CHUNK = 1024 * 1024
        private const val MAX_32 = 0xFFFF_FFFFL
        private const val INFO_BATCH = 16
        private const val BATCH_SIZE = 50
        private const val BATCH_MS = 16L
        private const val PREVIEW_MAX = 8
        private const val PREVIEW_LOOK = 64
        private const val MAX_EVENT_ERRORS = 3
        private const val EVENT_RETRY_MS = 500L

        /** Files without a known extension: the MTP object format decides. */
        private fun kindOf(format: Int): FileTypes.Kind = when (format) {
            MtpFormats.EXIF_JPEG, MtpFormats.JFIF, MtpFormats.PNG, MtpFormats.BMP,
            MtpFormats.TIFF, MtpFormats.TIFF_EP, MtpFormats.DNG, MtpFormats.HEIF,
            -> FileTypes.Kind(MediaType.IMAGE)
            MtpFormats.GIF -> FileTypes.Kind(MediaType.ANIMATED)
            MtpFormats.MPEG, MtpFormats.MP4, MtpFormats.AVI, MtpFormats.THREE_GP, MtpFormats.WMV, MtpFormats.ASF,
            -> FileTypes.Kind(MediaType.VIDEO)
            MtpFormats.MP3, MtpFormats.WAV, MtpFormats.AAC, MtpFormats.FLAC, MtpFormats.OGG, MtpFormats.WMA,
            -> FileTypes.Kind(MediaType.OTHER, OtherKind.AUDIO)
            MtpFormats.TEXT -> FileTypes.Kind(MediaType.OTHER, OtherKind.TEXT)
            else -> FileTypes.Kind(MediaType.OTHER, OtherKind.UNKNOWN)
        }
    }
}
