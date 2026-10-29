package com.mediaviewer.data.mtp

import android.hardware.usb.UsbDeviceConnection
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.SystemClock
import android.util.Log
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * One open MTP / PTP device (todo §9.3). [MtpDevice] is not thread-safe and a USB device answers
 * one request at a time, so every call runs on this connection's own thread, one after another.
 * Listing, thumbnails and downloads are split into short calls, so a thumbnail never waits for a
 * whole video to download. Each call has a watchdog: a device that stops answering is marked
 * [hung] and every later call fails at once with "Device not responding" (README §2.1).
 */
class MtpConnection(
    val deviceId: Int,
    private val device: MtpDevice,
    private val usb: UsbDeviceConnection,
) {
    class Storage(val id: Int, val label: String, val freeBytes: Long, val totalBytes: Long)

    /** What the listing learnt about a file: enough for thumbnails and downloads without asking again. */
    private class Obj(val handle: Int, val storageId: Int, val folder: Boolean, val size: Long, val hasThumb: Boolean)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mtp-$deviceId").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val objects = ConcurrentHashMap<String, Obj>()

    @Volatile var hung = false
        private set

    @Volatile private var closed = false

    /** GetPartialObject: downloads in chunks, interleaved with thumbnails and cancellable between chunks. */
    private val partial: Boolean by lazy {
        try {
            device.deviceInfo?.isOperationSupported(MtpConstants.OPERATION_GET_PARTIAL_OBJECT) == true
        } catch (e: Exception) {
            false
        }
    }

    /** Runs [block] on the device thread with a watchdog. Throws [IOException] on timeout or failure. */
    private suspend fun <T> call(timeoutMs: Long = CALL_MS, block: (MtpDevice) -> T): T {
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
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    suspend fun storages(): List<Storage> = call { d ->
        (d.storageIds ?: IntArray(0)).toList().mapNotNull { id ->
            val info = d.getStorageInfo(id) ?: return@mapNotNull null
            val label = info.description?.takeIf { it.isNotBlank() } ?: info.volumeIdentifier?.takeIf { it.isNotBlank() } ?: "Storage"
            Storage(id, label, info.freeSpace, info.maxCapacity)
        }
    }

    /**
     * Streaming listing of an MTP folder, in the same batches as [FolderScanner.scan]. The handles
     * come in one call; the file details are read [INFO_BATCH] at a time.
     */
    fun scan(dir: String, showHidden: Boolean): Flow<FolderScanner.Event> = flow {
        val parts = MtpPaths.parse(dir)
        val parent = try {
            parts?.let { if (it.isStorageRoot) ROOT else resolve(dir)?.handle }
        } catch (e: IOException) {
            emit(FolderScanner.Event.Failed(e.message ?: "read error"))
            return@flow
        }
        if (parts == null || parent == null) {
            emit(FolderScanner.Event.Failed("not found"))
            return@flow
        }
        val handles = try {
            call(LIST_MS) { it.getObjectHandles(parts.storageId, 0, parent) } ?: IntArray(0)
        } catch (e: IOException) {
            emit(FolderScanner.Event.Failed(e.message ?: "read error"))
            return@flow
        }
        var last = SystemClock.uptimeMillis()
        var listed = 0
        val batch = ArrayList<FileEntry>()
        for (chunk in handles.asList().chunked(INFO_BATCH)) {
            coroutineContext.ensureActive()
            val infos = try {
                call { d -> chunk.mapNotNull { h -> d.getObjectInfo(h) } }
            } catch (e: IOException) {
                Log.w(TAG, "Listing $dir stopped early", e)
                if (listed == 0) emit(FolderScanner.Event.Failed(e.message ?: "read error"))
                break
            }
            for (info in infos) {
                val entry = remember(dir, info, showHidden) ?: continue
                batch.add(entry)
                listed++
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

    private fun remember(dir: String, info: MtpObjectInfo, showHidden: Boolean): FileEntry? {
        val name = info.name ?: return null
        if (!showHidden && FolderScanner.isHidden(name)) return null
        val path = MtpPaths.child(dir, name)
        val folder = info.format == MtpConstants.FORMAT_ASSOCIATION
        val size = info.compressedSizeLong
        objects[path] = Obj(info.objectHandle, info.storageId, folder, size, info.thumbCompressedSizeLong > 0)
        if (folder) return FileEntry(path, name, MediaType.FOLDER, 0, info.dateModified)
        val kind = FileTypes.byExtension(name) ?: kindOf(info.format)
        return FileEntry(path, name, kind.type, size, info.dateModified, kind.other)
    }

    /**
     * The object behind [path]. Paths from this session's listings are known; any other one (a
     * breadcrumb after the app was recreated) is found by listing each folder on the way down.
     */
    private suspend fun resolve(path: String): Obj? {
        objects[path]?.let { return it }
        val parts = MtpPaths.parse(path) ?: return null
        var dir = MtpPaths.storageRoot(parts.deviceId, parts.storageId)
        var handle = ROOT
        for (seg in parts.segments) {
            val target = MtpPaths.child(dir, seg)
            if (objects[target] == null) {
                val handles = call(LIST_MS) { it.getObjectHandles(parts.storageId, 0, handle) } ?: return null
                for (chunk in handles.asList().chunked(INFO_BATCH)) {
                    val infos = call { d -> chunk.mapNotNull { h -> d.getObjectInfo(h) } }
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
        return call(THUMB_MS) { it.getThumbnail(o.handle) }
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
            if (partial && o.size in 0 until 0xFFFF_FFFFL) {
                RandomAccessFile(tmp, "rw").use { out ->
                    out.setLength(0)
                    val buf = ByteArray(CHUNK)
                    var off = 0L
                    while (off < o.size) {
                        coroutineContext.ensureActive()
                        val want = minOf(CHUNK.toLong(), o.size - off)
                        val n = call(CHUNK_MS) { it.getPartialObject(o.handle, off, want, buf) }
                        if (n <= 0) throw IOException("read error")
                        out.write(buf, 0, n.toInt())
                        off += n
                        onProgress(off, o.size)
                    }
                }
            } else {
                // Whole-file transfer: one call, so its watchdog grows with the size (≥ 1 MB/s).
                val ms = CHUNK_MS + o.size / 1000
                if (!call(ms) { it.importFile(o.handle, tmp.path) }) throw IOException("read error")
                onProgress(o.size, o.size)
            }
            if (!tmp.renameTo(dest)) throw IOException("can't write ${dest.name}")
        } finally {
            tmp.delete()
        }
    }

    /** Size of [path] as the device reported it, or -1 if unknown. */
    fun sizeOf(path: String): Long = objects[path]?.size ?: -1L

    fun close() {
        closed = true
        scope.cancel()
        // Closing from another thread also ends a call stuck on the device thread.
        try {
            device.close()
        } catch (e: Exception) {
            Log.w(TAG, "MTP close failed", e)
        }
        try {
            usb.close()
        } catch (e: Exception) {
        }
        executor.shutdownNow()
    }

    companion object {
        /** The parent handle of objects at the top of a storage. */
        private const val ROOT = -1 // 0xFFFFFFFF
        private const val CALL_MS = 8_000L
        private const val LIST_MS = 30_000L
        private const val THUMB_MS = 10_000L
        private const val CHUNK_MS = 20_000L
        private const val CHUNK = 1024 * 1024
        private const val INFO_BATCH = 16
        private const val BATCH_SIZE = 50
        private const val BATCH_MS = 16L

        /** Files without a known extension: the MTP object format decides. */
        private fun kindOf(format: Int): FileTypes.Kind = when (format) {
            MtpConstants.FORMAT_EXIF_JPEG, MtpConstants.FORMAT_JFIF, MtpConstants.FORMAT_PNG, MtpConstants.FORMAT_BMP,
            MtpConstants.FORMAT_TIFF, MtpConstants.FORMAT_TIFF_EP, MtpConstants.FORMAT_DNG,
            -> FileTypes.Kind(MediaType.IMAGE)
            MtpConstants.FORMAT_GIF -> FileTypes.Kind(MediaType.ANIMATED)
            MtpConstants.FORMAT_MPEG, MtpConstants.FORMAT_MP4_CONTAINER, MtpConstants.FORMAT_AVI,
            MtpConstants.FORMAT_3GP_CONTAINER, MtpConstants.FORMAT_WMV, MtpConstants.FORMAT_ASF,
            -> FileTypes.Kind(MediaType.VIDEO)
            MtpConstants.FORMAT_MP3, MtpConstants.FORMAT_WAV, MtpConstants.FORMAT_AAC, MtpConstants.FORMAT_FLAC,
            MtpConstants.FORMAT_OGG, MtpConstants.FORMAT_WMA,
            -> FileTypes.Kind(MediaType.OTHER, OtherKind.AUDIO)
            MtpConstants.FORMAT_TEXT -> FileTypes.Kind(MediaType.OTHER, OtherKind.TEXT)
            else -> FileTypes.Kind(MediaType.OTHER, OtherKind.UNKNOWN)
        }
    }
}
