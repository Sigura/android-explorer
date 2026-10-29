package com.mediaviewer.data.fs

import android.os.Build
import android.os.FileObserver
import android.util.Log
import com.mediaviewer.domain.SlideOrder
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * One open folder: a sorted, streamed list of entries plus live updates (README §2, rules 1 and 8).
 * Batches from [FolderScanner] are inserted into sorted position, so tiles appear in their final
 * order while the scan runs. Lazy-grid items are keyed by path, so an insertion only composes the
 * new tiles.
 */
class FolderListing(
    val path: String,
    private val scope: CoroutineScope,
    comparator: Comparator<FileEntry>,
    private val showHidden: Boolean,
    /** Folders on MTP devices (todo §9) are listed by the device connection and not watched. */
    private val scanner: (String, Boolean) -> Flow<FolderScanner.Event> = FolderScanner::scan,
    private val watch: Boolean = true,
    private val onFailed: (path: String, reason: String) -> Unit,
) {
    enum class Status { LOADING, DONE, STOPPED, FAILED }

    private val lock = Any()
    private var comparator: Comparator<FileEntry> = comparator
    private val sorted = ArrayList<FileEntry>()
    private val known = HashSet<String>()

    private val _entries = MutableStateFlow<List<FileEntry>>(emptyList())
    val entries: StateFlow<List<FileEntry>> = _entries.asStateFlow()

    private val _status = MutableStateFlow(Status.LOADING)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** True once the first batch arrived (or the listing finished empty). */
    private val _started = MutableStateFlow(false)
    val started: StateFlow<Boolean> = _started.asStateFlow()

    /**
     * True once a still or animated image was listed. Until then the folder is sorted as one
     * without images (by name), so a folder of audio or documents never reorders when it completes.
     */
    private val _hasImages = MutableStateFlow(false)
    val hasImages: StateFlow<Boolean> = _hasImages.asStateFlow()

    private var job: Job? = null
    private var observer: FileObserver? = null

    fun start() {
        if (job?.isActive == true) return
        _status.value = Status.LOADING
        // Sorted insertion and the list snapshot per batch stay off the main thread.
        job = scope.launch(Dispatchers.Default) {
            scanner(path, showHidden).collect { ev ->
                when (ev) {
                    is FolderScanner.Event.Batch -> insertAll(ev.entries)
                    is FolderScanner.Event.Failed -> {
                        _status.value = Status.FAILED
                        onFailed(path, ev.reason)
                    }
                }
                _started.value = true
            }
            _started.value = true
            if (_status.value == Status.LOADING) _status.value = Status.DONE
        }
        if (watch) startWatching()
    }

    /** Stop button: keep what is loaded (README §2.1, "Folder listing"). Returns immediately. */
    fun stop() {
        if (job?.isActive == true) {
            job?.cancel()
            _status.value = Status.STOPPED
            _started.value = true
        }
    }

    fun close() {
        job?.cancel()
        observer?.stopWatching()
        observer = null
    }

    fun resort(newComparator: Comparator<FileEntry>) {
        synchronized(lock) {
            comparator = newComparator
            sorted.sortWith(newComparator)
            publish()
        }
    }

    fun remove(path: String) {
        synchronized(lock) {
            if (!known.remove(path)) return
            sorted.removeAll { it.path == path }
            publish()
        }
    }

    fun add(entry: FileEntry) = insertAll(listOf(entry))

    fun snapshot(): List<FileEntry> = _entries.value

    private fun insertAll(batch: List<FileEntry>) {
        synchronized(lock) {
            var changed = false
            for (e in batch) {
                if (!known.add(e.path)) continue
                SlideOrder.insertSorted(sorted, e, comparator)
                if (e.type == MediaType.IMAGE || e.type == MediaType.ANIMATED) _hasImages.value = true
                changed = true
            }
            if (changed) publish()
        }
    }

    private fun publish() {
        _entries.value = ArrayList(sorted)
    }

    @Suppress("DEPRECATION")
    private fun startWatching() {
        if (observer != null) return
        val mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or
            FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val dir = File(path)
        val obs = if (Build.VERSION.SDK_INT >= 29) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, name: String?) = handle(event, name)
            }
        } else {
            object : FileObserver(path, mask) {
                override fun onEvent(event: Int, name: String?) = handle(event, name)
            }
        }
        observer = obs
        try {
            obs.startWatching()
        } catch (e: Exception) {
            Log.w(TAG, "Can't watch $path", e)
        }
    }

    private fun handle(event: Int, name: String?) {
        val ev = event and FileObserver.ALL_EVENTS
        if (ev and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) {
            onFailed(path, "removed")
            return
        }
        if (name == null) return
        val child = File(path, name)
        scope.launch(Dispatchers.IO) {
            when {
                ev and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0 -> remove(child.path)
                ev and (FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE) != 0 -> {
                    val e = FolderScanner.entryFor(child, showHidden) ?: return@launch
                    if (ev and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) != 0) {
                        // A finished write may change the type (".crdownload" renamed) or size; a
                        // file renamed over an existing one (a safe save) replaces its entry.
                        remove(child.path)
                    }
                    add(e)
                }
            }
        }
    }
}
