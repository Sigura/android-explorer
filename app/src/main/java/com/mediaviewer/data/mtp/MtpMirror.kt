package com.mediaviewer.data.mtp

import android.util.Log
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.domain.model.FileEntry
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Local copies of device files (todo §9.6): `<root>/<device>/<storage>/<folders>/<name>`. The
 * slideshow, players and decoders read local files, so device media are downloaded here first.
 * A slideshow downloads the rest of its folder ahead, up to [budget] bytes in the mirror.
 * Pure Kotlin: [connection] finds the device of a path.
 */
class MtpMirror(
    val root: File,
    private val connection: (String) -> MtpConnection?,
    val budget: Long = BUDGET,
) {
    fun of(path: String): File? = MtpPaths.mirrorRelative(path)?.let { File(root, it) }

    fun isMirror(path: String): Boolean = path == root.path || path.startsWith(root.path + "/")

    /** The device path of a mirrored file or folder. */
    fun remoteOf(localPath: String): String? =
        if (!isMirror(localPath)) null else MtpPaths.fromMirrorRelative(localPath.removePrefix(root.path))

    /**
     * The local copy of [entry], downloaded now unless an earlier copy of the same size is there.
     * Returns null when the device is gone.
     */
    suspend fun fetch(entry: FileEntry, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File? {
        val c = connection(entry.path) ?: return null
        val dest = of(entry.path) ?: return null
        if (dest.isFile && dest.length() == entry.size) return dest
        c.download(entry.path, dest, onProgress)
        return dest
    }

    /** Bytes in the mirror. */
    fun bytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Frees the mirror except [keep] (the folder being shown). */
    fun trim(keep: File) {
        root.walkBottomUp().forEach { f ->
            if (f.isFile && !f.path.startsWith(keep.path + "/")) f.delete()
            if (f.isDirectory && f != root && f.list()?.isEmpty() == true) f.delete()
        }
    }

    /** Before a slideshow from [dir]: a mirror over budget keeps only that folder. */
    fun makeRoomFor(dir: File) {
        if (bytes() > budget) trim(dir)
    }

    /** A device file is gone (deleted, moved): its copy goes too. */
    fun forget(path: String) {
        of(path)?.deleteRecursively()
    }

    fun clear(deviceId: Int? = null) {
        (if (deviceId == null) root else File(root, deviceId.toString())).deleteRecursively()
    }

    /**
     * Downloads [media] ahead of a slideshow from [start]: the one after it round to the one
     * before it, while they fit in the budget. [onError] says whether to go on after a failure.
     * Returns how many files are in the mirror.
     */
    suspend fun prefetch(media: List<FileEntry>, start: FileEntry, onError: (FileEntry, Exception) -> Boolean): Int {
        var done = 0
        for (e in plan(order(media, start), bytes(), budget)) {
            try {
                fetch(e) ?: break
                done++
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.w(TAG, "Prefetch failed: ${e.path}", ex)
                if (!onError(e, ex)) break
            }
        }
        return done
    }

    companion object {
        /** A slideshow stops downloading ahead once the mirror holds this much (todo §9.6). */
        const val BUDGET = 2L * 1024 * 1024 * 1024

        /** From the one after [start] round to the one before it. */
        fun order(media: List<FileEntry>, start: FileEntry): List<FileEntry> {
            val i = media.indexOfFirst { it.path == start.path }
            return if (i < 0) media else media.drop(i + 1) + media.take(i)
        }

        /** The files of [queue] that fit in [budget] with [used] bytes taken, in order: the first that doesn't fit ends it. */
        fun plan(queue: List<FileEntry>, used: Long, budget: Long): List<FileEntry> {
            var total = used
            val out = ArrayList<FileEntry>()
            for (e in queue) {
                if (total + e.size > budget) break
                total += e.size
                out.add(e)
            }
            return out
        }
    }
}

/** Thrown when a device path's device is no longer connected. */
class DeviceGone : IOException("Device disconnected")
