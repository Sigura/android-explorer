package com.mediaviewer.data.fs

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Delete with undo (README §6, item 3). A deleted file is renamed into a hidden
 * `.mediaviewer-trash/` folder next to it, which is on the same volume, so the move is instant
 * even for huge files. It is removed for good when the undo snackbar closes.
 * Pending entries are kept in a small file and purged on the next start if the app was killed.
 */
class Trash(context: Context) {
    data class Ticket(val original: String, val trashed: String)

    private val pendingFile = File(context.filesDir, "pending_trash.txt")
    private val pending = LinkedHashMap<String, Ticket>()

    init {
        purgeLeftovers()
    }

    /** Returns null (and logs) if the file couldn't be moved. */
    @Synchronized
    fun moveToTrash(path: String): Ticket? {
        val src = File(path)
        val dir = File(src.parentFile, TRASH_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return directDelete(src)
        var dst = File(dir, src.name)
        var n = 1
        while (dst.exists()) dst = File(dir, "${n++}_${src.name}")
        if (!src.renameTo(dst)) return directDelete(src)
        val t = Ticket(path, dst.path)
        pending[t.trashed] = t
        save()
        return t
    }

    @Synchronized
    fun restore(t: Ticket): Boolean {
        pending.remove(t.trashed)
        save()
        if (t.trashed.isEmpty()) return false // deleted directly, nothing to restore
        val ok = File(t.trashed).renameTo(File(t.original))
        cleanupDir(File(t.trashed).parentFile)
        return ok
    }

    @Synchronized
    fun purge(t: Ticket) {
        pending.remove(t.trashed)
        save()
        if (t.trashed.isEmpty()) return
        val f = File(t.trashed)
        if (!f.deleteRecursively()) Log.w(TAG, "Couldn't purge ${t.trashed}")
        cleanupDir(f.parentFile)
    }

    private fun directDelete(src: File): Ticket? =
        if (src.deleteRecursively()) Ticket(src.path, "") else null

    private fun cleanupDir(dir: File?) {
        if (dir != null && dir.name == TRASH_DIR && dir.list()?.isEmpty() == true) dir.delete()
    }

    private fun save() {
        try {
            pendingFile.writeText(pending.keys.joinToString("\n"))
        } catch (e: Exception) {
            Log.w(TAG, "Can't save trash list", e)
        }
    }

    private fun purgeLeftovers() {
        val left = try {
            if (pendingFile.exists()) pendingFile.readLines() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        for (p in left) if (p.isNotBlank()) {
            val f = File(p)
            f.deleteRecursively()
            cleanupDir(f.parentFile)
        }
        pendingFile.delete()
    }

    companion object {
        const val TRASH_DIR = ".mediaviewer-trash"
    }
}
