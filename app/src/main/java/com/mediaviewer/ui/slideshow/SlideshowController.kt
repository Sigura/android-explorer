package com.mediaviewer.ui.slideshow

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.fs.FolderListing
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.data.meta.MediaMeta
import com.mediaviewer.data.settings.SlideshowSession
import com.mediaviewer.domain.SlideOrder
import com.mediaviewer.domain.SlideTimer
import com.mediaviewer.domain.SlideTiming
import com.mediaviewer.domain.Sorting
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.ui.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Slideshow state (README §5, §6, §7.3): the order, the current item and its timing, pause,
 * the menu, skipping of unreadable items and session saving.
 */
@Stable
class SlideshowController(
    private val vm: AppViewModel,
    private val listing: FolderListing,
    startPath: String,
    private val resume: SlideshowSession?,
    private val scope: CoroutineScope,
) {
    var current by mutableStateOf<FileEntry?>(null)
        private set
    /** Bumped every time an item is shown, even the same file again (a one-item folder wraps). */
    var generation by mutableIntStateOf(0)
        private set
    /** +1 after "next", -1 after "previous": picks the slide direction. */
    var direction by mutableIntStateOf(1)
        private set
    var paused by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
        private set
    /** Menu bounds in slideshow coordinates, so taps on the menu don't reach the slide. */
    var menuBounds: androidx.compose.ui.geometry.Rect? = null
    /** Bumped on any menu input; the menu closes after ~5 s without input. */
    var menuTouch by mutableLongStateOf(0L)
        private set
    var timing by mutableStateOf<SlideTiming?>(null)
        private set
    var meta by mutableStateOf<MediaMeta?>(null)
        private set
    /** Position / count in the current order or random cycle, e.g. 1,284 / 29,102. */
    var progress by mutableStateOf(0 to 0)
        private set
    /** The folder has one media item: it stays on screen without the R limit, and a video loops. */
    val single: Boolean get() = progress.second == 1
    /** Video start position when resuming a long video (README §7.3). */
    var resumePositionMs = if (resume != null && resume.current == startPath) resume.positionMs else 0L
        private set
    /** Playback position of the current video, saved with the session. */
    var videoPositionMs = 0L
    /** Playback position of a video or GIF on screen, set by the screen; read when the user leaves it. */
    var positionOf: (FileEntry) -> Long = { 0L }

    private var order: SlideOrder? = null
    /** Next (+1) / previous (-1) pressed before the folder listing finished. */
    private var pendingNav = 0
    private var ready = false
    private var automatic = false
    /** The current item was reached by the timer, not by the user. */
    val reachedAutomatically: Boolean get() = automatic
    private var skipsInRow = 0
    private var notReadyJob: Job? = null
    private val known = HashSet<String>()
    private var listHash = 0
    private var lastSave = 0L
    /** Time the current item has been on screen, excluding pauses, up to [runningSince]. */
    private var elapsedMs = 0L
    /** Uptime when the item timer last started running, or -1 while it is stopped. */
    private var runningSince = -1L
    /** Identifies the running [tick]; a cancelled one that finishes late must not touch the clock. */
    private var tickToken = 0
    /** Where the last [MAX_VISITS] items were left, so "previous" goes on from there. */
    private val visits = object : LinkedHashMap<String, Visit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Visit>?) = size > MAX_VISITS
    }

    private class Visit(val elapsedMs: Long, val showMs: Long, val positionMs: Long)

    init {
        scope.launch {
            val e = withContext(Dispatchers.IO) { FolderScanner.entryFor(File(startPath), true) }
            if (e == null) {
                vm.errors.notify("File not found", startPath)
            }
            show(e ?: placeholder(startPath), 1, auto = false)
            buildOrder(startPath)
        }
    }

    private fun placeholder(path: String): FileEntry {
        val name = path.substringAfterLast('/')
        return FileEntry(path, name, FileTypes.byExtension(name)?.type ?: MediaType.IMAGE, 0, 0)
    }

    /** The full media list is needed for the order; the first item shows before the listing finishes. */
    private suspend fun buildOrder(startPath: String) {
        listing.status.first { it != FolderListing.Status.LOADING }
        val media = listing.snapshot().filter { it.isMedia }
        val s = vm.settings.value
        val o = if (resume != null) {
            SlideOrder(media, Sorting.comparator(vm.folderSort.value), resume.random, resume.seed, resume.pinnedFirst, startPath)
        } else {
            SlideOrder(media, Sorting.comparator(vm.folderSort.value), s.random, System.nanoTime(), if (s.random) startPath else null, startPath)
        }
        order = o
        known.addAll(media.map { it.path })
        listHash = known.sorted().hashCode()
        o.current?.let { c -> if (c.path != current?.path) show(c, 1, auto = false) }
        updateProgress()
        // A single item has nothing to move to; it just stays on screen.
        if (!single) when (pendingNav) {
            1 -> next()
            -1 -> previous()
        }
        pendingNav = 0
        if (o.isEmpty) {
            close("No media files in this folder")
            return
        }
        // Live updates (README §6): new files join the order, deleted ones are skipped.
        scope.launch {
            listing.entries.collect { list ->
                val ord = order ?: return@collect
                val now = list.filter { it.isMedia }
                val paths = now.mapTo(HashSet()) { it.path }
                val removed = known.filter { it !in paths }
                val added = now.filter { it.path !in known }
                if (removed.isEmpty() && added.isEmpty()) return@collect
                removed.forEach { known.remove(it); ord.remove(it) }
                added.forEach { known.add(it.path); ord.add(it) }
                listHash = known.sorted().hashCode()
                if (current?.path in removed) {
                    val c = ord.current
                    if (c == null) close("No media files left") else show(c, 1, auto = true)
                }
                updateProgress()
            }
        }
    }

    /** The order needs the full listing; until then a navigation waits with the indicator. */
    private fun waitForOrder(dir: Int) {
        pendingNav = dir
        vm.setBusy("Reading folder…", onStop = { pendingNav = 0 }) {
            listing.status.first { it != FolderListing.Status.LOADING }
        }
    }

    private fun updateProgress() {
        val o = order ?: return
        progress = (o.position + 1) to o.size
    }

    fun next(auto: Boolean = false) {
        val o = order ?: return waitForOrder(1)
        val n = o.next() ?: return
        updateProgress()
        show(n, 1, auto)
    }

    fun previous() {
        val o = order ?: return waitForOrder(-1)
        val p = o.previous() ?: return
        updateProgress()
        show(p, -1, auto = false)
    }

    /** Time the current item has been on screen so far, excluding pauses and the open menu. */
    fun shownMs(): Long = elapsedMs + if (runningSince >= 0) SystemClock.uptimeMillis() - runningSince else 0L

    private fun stopClock() {
        elapsedMs = shownMs()
        runningSince = -1
    }

    private fun show(e: FileEntry, dir: Int, auto: Boolean) {
        if (!auto) skipsInRow = 0
        // Remember where the item on screen was left; going back to it goes on from there.
        current?.let { old ->
            val t = timing
            val shown = shownMs()
            if (t != null && shown > 0) {
                visits[old.path] = Visit(shown, t.showMs, positionOf(old))
            }
        }
        // An item that was shown to its end, or nearly, starts over instead of flashing by.
        val visit = if (dir < 0) visits.remove(e.path)?.takeIf { it.showMs - it.elapsedMs >= MIN_LEFT_MS } else null
        if (visit != null) Log.d(TAG, "Slideshow: back to ${e.name} at ${visit.elapsedMs} ms, position ${visit.positionMs} ms")
        val firstShow = generation == 0
        direction = dir
        current = e
        generation++
        ready = false
        automatic = auto
        timing = null
        meta = null
        tickToken++
        elapsedMs = visit?.elapsedMs ?: 0L
        runningSince = -1
        resumePositionMs = when {
            visit != null -> visit.positionMs
            firstShow && resume?.current == e.path -> resume.positionMs
            else -> 0L
        }
        videoPositionMs = resumePositionMs
        vm.thumbnails.clearFailure(e.path)
        saveSession(force = true)
        notReadyJob?.cancel()
        notReadyJob = scope.launch {
            if (auto) {
                // README §2.1: an automatically reached item not ready within min(R, 3 s) is skipped.
                delay(minOf(intervalMs(), 3000L))
                if (!ready) skip(e, "not ready in time")
            } else {
                delay(300)
                if (!ready) {
                    vm.setBusy(loadingLabel(e), onStop = { previous() }) {
                        while (!ready && current === e) delay(50)
                    }
                }
            }
        }
        if (e.type == MediaType.ANIMATED) {
            scope.launch {
                meta = try {
                    vm.thumbnails.gifMeta(e)
                } catch (ex: Exception) {
                    null
                }
                if (current === e && ready) timing = SlideTimer.timing(meta?.durationMs, intervalMs())
            }
        }
    }

    private fun loadingLabel(e: FileEntry): String {
        val kind = when (e.type) {
            MediaType.ANIMATED -> "GIF"
            MediaType.VIDEO -> "video"
            else -> "image"
        }
        return if (e.size > 10 * 1024 * 1024) "Loading ${e.size / (1024 * 1024)} MB $kind…" else "Loading $kind…"
    }

    fun intervalMs(): Long = vm.settings.value.intervalSec * 1000L

    /** README §6: a new R applies at once, also to the item on screen. */
    fun onIntervalChanged() {
        val e = current ?: return
        if (!ready || timing == null) return
        val d = when (e.type) {
            MediaType.VIDEO, MediaType.ANIMATED -> meta?.durationMs?.takeIf { it > 0 }
            else -> null
        }
        timing = SlideTimer.timing(d, intervalMs())
    }

    /** The display reports the item is on screen. For video, [durationMs] comes from the player. */
    fun onReady(e: FileEntry, durationMs: Long? = null, width: Int = 0, height: Int = 0) {
        if (current !== e) return
        if (ready && e.type == MediaType.VIDEO) return // READY again after a seek or loop
        ready = true
        skipsInRow = 0
        if (vm.busy.value != null) vm.stopBusyQuietly()
        when (e.type) {
            MediaType.VIDEO -> {
                meta = MediaMeta(width, height, 0, durationMs ?: 0)
                timing = SlideTimer.timing(durationMs, intervalMs())
            }
            MediaType.ANIMATED -> {
                if (meta == null) meta = MediaMeta(width, height, 0, 0)
                // The duration comes from the block scan; until then the GIF just plays.
                if (meta?.durationMs != null && meta!!.durationMs > 0) timing = SlideTimer.timing(meta!!.durationMs, intervalMs())
                else scope.launch {
                    delay(3000)
                    if (current === e && timing == null) timing = SlideTimer.timing(null, intervalMs())
                }
            }
            else -> {
                meta = MediaMeta(width, height, 1, 0)
                timing = SlideTimer.timing(null, intervalMs())
            }
        }
    }

    /** README §2.1 / §5.3: unreadable items are skipped automatically, with a grouped message. */
    fun onFailed(e: FileEntry, reason: String) {
        if (current !== e) return
        skip(e, reason)
    }

    private fun skip(e: FileEntry, reason: String) {
        if (current !== e) return
        vm.errors.readError(e.path, reason)
        val o = order
        skipsInRow++
        if (o == null || skipsInRow >= o.size.coerceAtLeast(1)) {
            close("Nothing in this folder can be shown")
            return
        }
        val n = o.next() ?: return
        updateProgress()
        show(n, 1, auto = true)
    }

    fun onPlaybackEnded(e: FileEntry) {
        if (current === e && timing?.loop == false && !single && !paused && !menuOpen) next(auto = true)
    }

    fun setRandom(on: Boolean) {
        vm.settingsStore.update { it.copy(random = on) }
        order?.setRandom(on)
        updateProgress()
        saveSession(force = true)
    }

    fun deleteCurrent() {
        val e = current ?: return
        val o = order ?: return
        touchMenu()
        o.remove(e.path)
        known.remove(e.path)
        vm.delete(listOf(e)) { restored -> restored.forEach { r -> if (known.add(r.path)) o.add(r) }; updateProgress() }
        val c = o.current
        updateProgress()
        if (c == null) close("No media files left") else show(c, 1, auto = false)
    }

    fun openMenu() {
        menuOpen = true
        touchMenu()
    }

    fun closeMenu() {
        menuOpen = false
        menuBounds = null
    }

    fun touchMenu() {
        menuTouch = SystemClock.uptimeMillis()
    }

    fun close(message: String? = null) {
        message?.let { vm.errors.notify(it, log = false) }
        vm.closeSlideshow(current?.path)
    }

    fun saveSession(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSave < 1000) return
        lastSave = now
        val c = current ?: return
        val o = order
        vm.settingsStore.saveSession(
            SlideshowSession(
                active = true,
                folder = listing.path,
                current = c.path,
                random = o?.random ?: vm.settings.value.random,
                seed = o?.seed ?: resume?.seed ?: 0L,
                pinnedFirst = o?.pinnedFirst ?: resume?.pinnedFirst,
                positionMs = videoPositionMs,
                listCount = known.size,
                listHash = listHash,
            ),
        )
    }

    fun peekNext(): FileEntry? = order?.peekNext()

    /**
     * Runs the item timer; restarted (by the screen) whenever the item, its timing, pause or the
     * menu changes. Time already shown is kept across pauses and timing changes, and belongs to
     * this item only: [show] resets the clock before a cancelled tick of the previous item ends.
     */
    suspend fun tick() {
        stopClock()
        val token = ++tickToken
        val t = timing ?: return
        val e = current ?: return
        if (paused || menuOpen || single) return
        runningSince = SystemClock.uptimeMillis()
        try {
            val playsOnce = !t.loop && e.type == MediaType.VIDEO
            // A video that plays once advances when it ends; the timer is only a fallback.
            delay((t.showMs + if (playsOnce) 2000 else 0) - elapsedMs)
            if (current === e) next(auto = true)
        } finally {
            if (tickToken == token) stopClock()
        }
    }

    private companion object {
        const val MAX_VISITS = 15
        /** Going back to an item with less time than this left starts it over. */
        const val MIN_LEFT_MS = 2_000L
    }
}
