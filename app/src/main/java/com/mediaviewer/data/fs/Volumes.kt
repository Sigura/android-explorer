package com.mediaviewer.data.fs

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

data class Volume(
    val path: String,
    val label: String,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

/** Storage roots shown on the root screen (README §4 "Root"): internal, SD cards, USB drives. */
object Volumes {
    fun list(context: Context): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val result = LinkedHashMap<String, Volume>()
        if (Build.VERSION.SDK_INT >= 30) {
            for (v in sm.storageVolumes) {
                val dir = v.directory ?: continue
                if (!dir.canRead()) continue
                result[dir.path] = volume(dir, v.getDescription(context), v.isRemovable)
            }
        } else {
            val primary = Environment.getExternalStorageDirectory()
            result[primary.path] = volume(primary, "Internal storage", false)
            // App-specific dirs exist on every mounted volume: strip "/Android/data/<pkg>/files".
            for (d in context.getExternalFilesDirs(null)) {
                val root = d?.path?.substringBefore("/Android/data/")?.let(::File) ?: continue
                if (root.path in result || !root.canRead()) continue
                val desc = sm.getStorageVolume(root)?.getDescription(context) ?: root.name
                result[root.path] = volume(root, desc, true)
            }
        }
        return result.values.toList()
    }

    private fun volume(dir: File, label: String, removable: Boolean): Volume {
        val (free, total) = try {
            val st = StatFs(dir.path)
            st.availableBytes to st.totalBytes
        } catch (e: Exception) {
            0L to 0L
        }
        return Volume(dir.path, label, removable, free, total)
    }

    /** The volume root containing [path], or null if it is on none of them. */
    fun rootOf(path: String, volumes: List<Volume>): Volume? =
        volumes.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
}
