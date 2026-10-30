package com.mediaviewer.data.mtp

import android.util.Log
import com.mediaviewer.data.fs.FileOps
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * *Copy to…* / *Move to…* when a device is on either side (todo §9.5, §9.8 item 2): device →
 * this device, this device → device, and device → device through a temporary local copy (the
 * platform has no copy or move on the device). Files and whole folders; name clashes get a
 * `(1)` name, as local copies do; Stop between files, and a stopped file leaves nothing behind.
 * A move deletes each original once it is copied. Pure Kotlin: [connection] finds the device of a path.
 */
class MtpTransfer(
    private val connection: (String) -> MtpConnection?,
    /** Where device → device copies pass through. */
    private val temp: File,
) {
    suspend fun run(
        sources: List<FileEntry>,
        destDir: String,
        move: Boolean,
        onProgress: (index: Int, total: Int, name: String) -> Unit,
        /** A source that was moved (copied and its original deleted), for the open listing. */
        onMoved: (String) -> Unit = {},
    ): FileOps.Result {
        val failed = ArrayList<Pair<String, String>>()
        var done = 0
        for ((i, src) in sources.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(i, sources.size, src.name)
            val reason = when {
                destDir == src.path || destDir.startsWith(src.path + "/") -> "can't put a folder inside itself"
                parentOf(src.path) == destDir -> "already in this folder"
                else -> null
            }
            if (reason != null) {
                failed.add(src.path to reason)
                continue
            }
            try {
                // A copy that goes through this phone says which half it is in (todo §11.4).
                copy(src, destDir) { step -> onProgress(i, sources.size, "${src.name} · $step") }
                if (move) {
                    if (deleteOriginal(src)) onMoved(src.path) else failed.add(src.path to "copied, but the original couldn't be deleted")
                }
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Transfer failed: ${src.path}", e)
                failed.add(src.path to (e.message ?: e.javaClass.simpleName))
            }
        }
        return FileOps.Result(done, failed)
    }

    private fun parentOf(path: String): String? = if (MtpPaths.isMtp(path)) MtpPaths.parent(path) else File(path).parent

    private fun conn(path: String): MtpConnection = connection(path) ?: throw DeviceGone()

    /**
     * Copies [src] into [destDir]. [step] tells how far a file that passes through this phone is:
     * `reading 40 %` while it comes off a device, `writing` while it goes onto one (one call: the
     * platform has no partial send, so there is no percentage and Stop waits for it).
     */
    private suspend fun copy(src: FileEntry, destDir: String, step: (String) -> Unit = {}) {
        coroutineContext.ensureActive()
        val fromDevice = MtpPaths.isMtp(src.path)
        val toDevice = MtpPaths.isMtp(destDir)
        if (!fromDevice && !toDevice) throw IOException("not a device copy")
        if (src.isFolder) {
            val children = if (fromDevice) conn(src.path).list(src.path) else localChildren(File(src.path))
            val target = if (toDevice) {
                conn(destDir).mkdir(destDir, src.name, freeName = true)
            } else {
                val t = FileOps.freeName(File(destDir), src.name)
                if (!t.mkdirs()) throw IOException("can't create ${t.name}")
                t.path
            }
            for (child in children) copy(child, target, step)
            return
        }
        var shown = -1L
        val reading = { done: Long, total: Long ->
            val pct = if (total > 0) done * 100 / total else 0
            if (pct != shown) {
                shown = pct
                step("reading $pct %")
            }
        }
        when {
            !toDevice -> conn(src.path).download(src.path, FileOps.freeName(File(destDir), src.name), reading)
            !fromDevice -> {
                step("writing")
                conn(destDir).upload(File(src.path), destDir)
            }
            else -> {
                // Same device or another one: the platform has no CopyObject / MoveObject, so the
                // file comes to this phone and goes back (todo §11.4).
                temp.mkdirs()
                val tmp = File(temp, "${System.nanoTime()}-${src.name}")
                try {
                    conn(src.path).download(src.path, tmp, reading)
                    step("writing")
                    conn(destDir).upload(tmp, destDir, src.name)
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private suspend fun deleteOriginal(src: FileEntry): Boolean {
        if (!MtpPaths.isMtp(src.path)) return File(src.path).deleteRecursively()
        return try {
            conn(src.path).delete(src.path)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Delete after move failed: ${src.path}", e)
            false
        }
    }

    private fun localChildren(dir: File): List<FileEntry> = (dir.listFiles() ?: throw IOException("can't read ${dir.name}")).map { f ->
        FileEntry(f.path, f.name, if (f.isDirectory) MediaType.FOLDER else MediaType.OTHER, f.length(), f.lastModified())
    }
}
