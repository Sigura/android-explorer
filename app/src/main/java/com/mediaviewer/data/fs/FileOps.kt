package com.mediaviewer.data.fs

import android.util.Log
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Move / copy for selection mode (README §4.6). A move inside one volume is a rename (instant);
 * across volumes it is copy + delete. Name clashes get a " (1)" suffix, nothing is overwritten.
 * Cancellation (Stop) is checked between files and between 1 MB chunks; a partly copied file is
 * removed.
 */
object FileOps {
    data class Result(val done: Int, val failed: List<Pair<String, String>>)

    suspend fun transfer(
        sources: List<String>,
        destDir: String,
        move: Boolean,
        onProgress: (index: Int, total: Int, name: String) -> Unit,
        /** A moved source and where it is now (todo §11.3: *Undo* of a move moves it back). */
        onMoved: (source: String, target: File) -> Unit = { _, _ -> },
    ): Result {
        val dest = File(destDir)
        val failed = ArrayList<Pair<String, String>>()
        var done = 0
        for ((i, path) in sources.withIndex()) {
            coroutineContext.ensureActive()
            val src = File(path)
            onProgress(i, sources.size, src.name)
            try {
                if (dest.canonicalPath == src.canonicalPath || dest.canonicalPath.startsWith(src.canonicalPath + "/")) {
                    failed.add(path to "can't put a folder inside itself")
                    continue
                }
                if (src.parentFile?.canonicalPath == dest.canonicalPath) {
                    failed.add(path to "already in this folder")
                    continue
                }
                val target = freeName(dest, src.name)
                if (move && src.renameTo(target)) {
                    onMoved(path, target)
                    done++
                    continue
                }
                copyRecursively(src, target)
                if (move) {
                    if (src.deleteRecursively()) onMoved(path, target) else failed.add(path to "copied, but the original couldn't be deleted")
                }
                done++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Transfer failed: $path", e)
                failed.add(path to (e.message ?: e.javaClass.simpleName))
            }
        }
        return Result(done, failed)
    }

    fun freeName(dir: File, name: String): File = File(dir, freeName(name) { File(dir, it).exists() })

    /** [name], or `name (1).ext`, `name (2).ext`… : the first one that isn't [taken]. */
    fun freeName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 1
        while (true) {
            val candidate = "$base (${n++})$ext"
            if (!taken(candidate)) return candidate
        }
    }

    private suspend fun copyRecursively(src: File, target: File) {
        if (src.isDirectory) {
            if (!target.mkdirs()) throw IllegalStateException("can't create ${target.name}")
            for (child in src.listFiles() ?: emptyArray()) copyRecursively(child, File(target, child.name))
            return
        }
        src.inputStream().use { copyStream(it, target) }
        target.setLastModified(src.lastModified())
    }

    /** Writes [input] to [target] in 1 MB chunks, checking for Stop between them; a partial file is removed. */
    suspend fun copyStream(input: InputStream, target: File) {
        try {
            target.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }
}
