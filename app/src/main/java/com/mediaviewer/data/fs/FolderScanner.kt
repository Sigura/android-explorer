package com.mediaviewer.data.fs

import android.os.SystemClock
import android.util.Log
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

const val TAG = "MediaViewer"

/**
 * Streaming folder listing (README §2, rule 1). Entries are read one by one from a
 * [java.nio.file.DirectoryStream] and emitted in small batches (every 16 ms or 50 entries),
 * so the first screen of tiles shows up long before a 30k-file folder has been read.
 */
object FolderScanner {
    sealed interface Event {
        data class Batch(val entries: List<FileEntry>) : Event
        data class Failed(val reason: String) : Event
    }

    private const val BATCH_SIZE = 50
    private const val BATCH_MS = 16L

    fun scan(dir: String, showHidden: Boolean): Flow<Event> = flow {
        val root = Paths.get(dir)
        val stream = try {
            Files.newDirectoryStream(root)
        } catch (e: Exception) {
            Log.w(TAG, "Can't list $dir", e)
            emit(Event.Failed(e.message ?: e.javaClass.simpleName))
            return@flow
        }
        stream.use {
            val batch = ArrayList<FileEntry>(BATCH_SIZE)
            var last = SystemClock.uptimeMillis()
            val iter = try {
                stream.iterator()
            } catch (e: Exception) {
                emit(Event.Failed(e.message ?: "read error"))
                return@flow
            }
            while (true) {
                coroutineContext.ensureActive()
                val p: Path = try {
                    if (!iter.hasNext()) break
                    iter.next()
                } catch (e: Exception) {
                    Log.w(TAG, "Listing $dir stopped early", e)
                    break
                }
                val entry = entryFor(p, showHidden) ?: continue
                batch.add(entry)
                val now = SystemClock.uptimeMillis()
                if (batch.size >= BATCH_SIZE || now - last >= BATCH_MS) {
                    emit(Event.Batch(ArrayList(batch)))
                    batch.clear()
                    last = now
                }
            }
            if (batch.isNotEmpty()) emit(Event.Batch(batch))
        }
    }.flowOn(Dispatchers.IO)

    fun entryFor(p: Path, showHidden: Boolean): FileEntry? {
        val name = p.fileName?.toString() ?: return null
        if (!showHidden && isHidden(name)) return null
        val attrs = try {
            Files.readAttributes(p, BasicFileAttributes::class.java)
        } catch (e: Exception) {
            // Dangling symlink or unreadable entry: list it as an unknown file so it can be deleted.
            try {
                Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e2: Exception) {
                return null
            }
        }
        val path = p.toString()
        if (attrs.isDirectory) {
            if (!showHidden && File(path, ".nomedia").exists()) return null
            return FileEntry(path, name, MediaType.FOLDER, 0, attrs.lastModifiedTime().toMillis())
        }
        val kind = FileTypes.detect(File(path))
        return FileEntry(path, name, kind.type, attrs.size(), attrs.lastModifiedTime().toMillis(), kind.other)
    }

    fun entryFor(file: File, showHidden: Boolean): FileEntry? = try {
        entryFor(file.toPath(), showHidden)
    } catch (e: Exception) {
        null
    }

    /** Dot files plus known metadata folders (README §3, "Hidden metadata"). */
    fun isHidden(name: String): Boolean = name.startsWith('.') || name == "Thumbs.db" || name == "desktop.ini"
}
