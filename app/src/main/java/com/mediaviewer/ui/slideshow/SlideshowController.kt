package com.mediaviewer.ui.slideshow

import android.net.Uri
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
import com.mediaviewer.data.history.FileHistory
import com.mediaviewer.data.meta.MediaMeta
import com.mediaviewer.data.mtp.MtpPaths
import com.mediaviewer.data.settings.SlideshowSession
import com.mediaviewer.domain.Book
import com.mediaviewer.domain.SlideNav
import com.mediaviewer.domain.SlideOrder
import com.mediaviewer.domain.SlideTimer
import com.mediaviewer.domain.SlideTiming
import com.mediaviewer.domain.Sorting
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.ui.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

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
    /**
     * Gets an item ready to be shown (todo §11.1), set by the screen: an image decoded into the
     * cache the slide draws it from, a GIF's block scan, a video prepared in the spare player at
     * the given position. Returns null when the item is ready, else why it can't be shown.
     */
    var prepare: suspend (FileEntry, Long) -> String? = { _, _ -> null }
    /** A manual step waits for its target to be ready (todo §11.1): the screen shows a small spinner. */
    var waiting by mutableStateOf(false)
        private set
    /**
     * Bumped when the app is visible again; restarts the timer. Composition is paused while the
     * app is stopped, so a "stopped" flag that went on and off again would never reach the screen.
     */
    var starts by mutableIntStateOf(0)
        private set

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
    private var tickJob: Job? = null
    /** The app is not visible: the clock stops and nothing is skipped as "not ready". */
    private var stopped = false
    /** The item ended while paused, in the menu or in the background; it advances when the timer runs again. */
    private var endedInMenu = false
    /** Items being prepared ahead (next, previous, a manual step's target), by path; the oldest is dropped. */
    private val preloads = LinkedHashMap<String, Deferred<String?>>()
    /** Where the last [MAX_VISITS] items were left, and the manual step waiting for its target. */
    private val nav = SlideNav(MAX_VISITS, MIN_LEFT_MS)
    private var waitJob: Job? = null

    /**
     * Listening positions of this folder's files (todo §3.6): a lecture listened to halfway starts
     * there here too, and the position the slideshow reaches goes back to the book.
     */
    private var listened: Map<String, FileHistory> = emptyMap()

    init {
        scope.launch {
            listened = vm.listenPositions(listing.path)
            // A device folder (todo §11.4) is already listed; its files have no local path.
            val e = if (onDevice) listing.snapshot().firstOrNull { it.path == startPath }
            else withContext(Dispatchers.IO) { FolderScanner.entryFor(File(startPath), true) }
            if (e == null) {
                vm.errors.notify("File not found", startPath)
            }
            show(e ?: placeholder(startPath), 1, auto = false)
            buildOrder(startPath)
        }
    }

    /** The slideshow runs over a folder on a camera or phone (todo §11.4); it is never resumed after a restart. */
    val onDevice: Boolean = MtpPaths.isMtp(listing.path)

    /**
     * The local file a slide decodes: the file itself, or for a device file its copy in the
     * mirror, downloaded now unless it is there already (todo §11.4). Throws when it can't be had.
     */
    suspend fun localFile(e: FileEntry): File {
        if (!MtpPaths.isMtp(e.path)) return File(e.path)
        return withContext(Dispatchers.IO) { vm.mtp.mirror.fetch(e) } ?: throw IOException("Device disconnected")
    }

    /** What the player opens: a local file, a device file streamed, or its mirror copy when the device can't stream. */
    suspend fun videoUri(e: FileEntry): Uri = when {
        !MtpPaths.isMtp(e.path) -> Uri.fromFile(File(e.path))
        vm.mtp.streams(e.path) -> Uri.parse(e.path)
        else -> Uri.fromFile(localFile(e))
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
        if (!auto) return step(1)
        val n = o.next() ?: return
        updateProgress()
        show(n, 1, auto)
    }

    fun previous() {
        if (order == null) return waitForOrder(-1)
        step(-1)
    }

    /**
     * A manual Next (+1) or Previous (-1), todo §11.1: the item on screen stays until the target is
     * ready, then the switch happens. A second press the same way moves the target on, the opposite
     * press cancels; a target that can't be read is skipped.
     */
    private fun step(dir: Int) {
        val o = order ?: return
        if (single) return
        // After the last item of a random cycle the next one isn't known until the new cycle starts.
        if (nav.wait == null && dir > 0 && o.peek(1) == null) {
            val n = o.next() ?: return
            updateProgress()
            show(n, 1, auto = false)
            return
        }
        when (val a = nav.press(dir, ::reachable)) {
            is SlideNav.Action.Go -> waitFor(a.offset)
            SlideNav.Action.Cancel -> endWait()
            SlideNav.Action.Stay -> Unit
        }
    }

    /** An item [offset] steps away exists, and isn't the one on screen again. */
    private fun reachable(offset: Int): Boolean {
        val o = order ?: return false
        return kotlin.math.abs(offset) < o.size && o.peek(offset) != null
    }

    private fun waitFor(offset: Int) {
        val o = order ?: return
        val target = o.peek(offset) ?: return cancelWait()
        waitJob?.cancel()
        val job = preload(target)
        waitJob = scope.launch {
            val mark = launch {
                delay(SPINNER_DELAY_MS)
                waiting = true
            }
            // Not ready within the cap (null): the switch happens anyway, with the wait indicator.
            val failure = withTimeoutOrNull(MAX_PRELOAD_WAIT_MS) {
                try {
                    job.await()
                } catch (e: CancellationException) {
                    // The preload was dropped for a newer one, not this wait: show the target as it is.
                    currentCoroutineContext().ensureActive()
                    null
                }
            }
            mark.cancel()
            if (failure != null) {
                // Skipped like an unreadable item reached automatically, with the grouped message.
                vm.errors.readError(target.path, failure)
                preloads.remove(target.path)
                when (val a = nav.failed(::reachable)) {
                    is SlideNav.Action.Go -> waitFor(a.offset)
                    else -> endWait()
                }
                return@launch
            }
            commit(target, if (offset > 0) 1 else -1)
        }
    }

    private fun commit(target: FileEntry, dir: Int) {
        val o = order ?: return
        waitJob = null
        cancelWait()
        // Deleted while waiting: the item on screen stays.
        val e = o.moveTo(target.path) ?: return endWait()
        updateProgress()
        show(e, dir, auto = false)
    }

    /**
     * A wait ended without a switch (cancelled, nothing left to skip to, the target deleted): an
     * item that ended meanwhile moves on now, since the timer held it back for the step.
     */
    private fun endWait() {
        cancelWait()
        val e = current
        if (e != null && endedInMenu && !paused && !menuOpen && !stopped) scope.launch { advanceFrom(e) }
    }

    private fun cancelWait() {
        waitJob?.cancel()
        waitJob = null
        nav.done()
        waiting = false
    }

    /** Time the current item has been on screen so far, excluding pauses and the open menu. */
    fun shownMs(): Long = elapsedMs + if (runningSince >= 0) SystemClock.uptimeMillis() - runningSince else 0L

    private fun stopClock() {
        elapsedMs = shownMs()
        runningSince = -1
    }

    private fun show(e: FileEntry, dir: Int, auto: Boolean) {
        if (!auto) skipsInRow = 0
        // Anything else that changes the item (a skip, a delete, the timer) ends a manual wait.
        cancelWait()
        // Remember where the item on screen was left; going back or forward to it goes on from there.
        current?.let { old ->
            val t = timing
            if (t != null) nav.leave(old.path, shownMs(), t.showMs, positionOf(old))
            saveListened(old)
        }
        // An item that was shown to its end, or nearly, starts over instead of flashing by.
        val visit = nav.resume(e.path)
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
        endedInMenu = false
        elapsedMs = visit?.elapsedMs ?: 0L
        runningSince = -1
        resumePositionMs = when {
            visit != null -> visit.positionMs
            firstShow && resume?.current == e.path -> resume.positionMs
            else -> startPositionOf(e)
        }
        videoPositionMs = resumePositionMs
        vm.thumbnails.clearFailure(e.path)
        saveSession(force = true)
        armNotReady(e, auto)
        if (e.type == MediaType.ANIMATED) {
            scope.launch {
                meta = try {
                    vm.thumbnails.gifMeta(if (MtpPaths.isMtp(e.path)) e.copy(path = localFile(e).path) else e)
                } catch (ex: kotlinx.coroutines.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    null
                }
                if (current === e && ready) timing = SlideTimer.timing(meta?.durationMs, intervalMs())
            }
        }
    }

    private fun armNotReady(e: FileEntry, auto: Boolean) {
        notReadyJob?.cancel()
        notReadyJob = scope.launch {
            if (auto) {
                if (stopped) return@launch
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
    }

    /**
     * The activity stopped or started. In the background nothing is composed, so a new item would
     * never get ready and would be skipped as unreadable, one after another.
     */
    fun onVisible(visible: Boolean) {
        stopped = !visible
        if (visible) starts++ else tickJob?.cancel()
        val e = current ?: return
        if (!automatic || ready) return
        if (visible) armNotReady(e, auto = true) else notReadyJob?.cancel()
    }

    /** A video starts where it was listened to (todo §3.6); other items from the start. */
    private fun startPositionOf(e: FileEntry): Long =
        if (e.type == MediaType.VIDEO) listened[e.path]?.takeIf { !it.finished }?.positionMs ?: 0L else 0L

    /**
     * Starts getting [e] ready, unless it already is being. A failure is not reported here: a
     * manual step reports and skips it, an automatic one when it is shown.
     */
    private fun preload(e: FileEntry): Deferred<String?> {
        preloads[e.path]?.let { p ->
            // A finished video preload may have lost the spare player to another video since.
            if (!p.isCancelled && !(e.type == MediaType.VIDEO && p.isCompleted)) return p
        }
        val start = nav.peekResume(e.path)?.positionMs ?: startPositionOf(e)
        val job = scope.async {
            try {
                prepare(e, start)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                ex.message ?: ex.javaClass.simpleName
            }
        }
        preloads.remove(e.path)
        preloads[e.path] = job
        while (preloads.size > MAX_PRELOADS) {
            val oldest = preloads.keys.first()
            preloads.remove(oldest)?.cancel()
        }
        return job
    }

    /**
     * Starts preloading the item "next" would move to. A video takes the spare player, so it is
     * prepared only shortly before it is due ([video]), and not while a manual step uses it.
     */
    private fun preloadNext(video: Boolean = true): Deferred<String?>? {
        val n = order?.peekNext() ?: return null
        if (n.type == MediaType.VIDEO && (!video || nav.wait != null)) return null
        if (n.path == current?.path) return null
        return preload(n)
    }

    /** The item Previous goes to, so Back is instant (todo §11.1); not a video, which would take the spare player. */
    private fun preloadPrevious() {
        val p = order?.peek(-1) ?: return
        if (p.type != MediaType.VIDEO && p.path != current?.path) preload(p)
    }

    /** Moves on once the next item is preloaded; until then the current one stays on screen. */
    private suspend fun advanceFrom(e: FileEntry) {
        // A manual step is about to switch: it goes first; if it is cancelled, the item moves on then.
        if (nav.wait != null) {
            endedInMenu = true
            return
        }
        preloadNext()?.let { withTimeoutOrNull(MAX_PRELOAD_WAIT_MS) { it.join() } }
        if (current !== e) return
        if (paused || menuOpen || stopped || nav.wait != null) endedInMenu = true else next(auto = true)
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
        preloadNext(video = false)
        preloadPrevious()
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
        if (current !== e || timing?.loop != false || single) return
        if (paused || menuOpen || stopped) endedInMenu = true else scope.launch { advanceFrom(e) }
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

    /** A video that has a listening position: where the slideshow left it goes back to the book. */
    private fun saveListened(e: FileEntry) {
        if (e.type != MediaType.VIDEO || e.path !in listened) return
        val pos = positionOf(e).takeIf { it > 0 } ?: videoPositionMs
        val finished = meta?.durationMs?.let { Book.isFinished(pos, it) } == true
        listened = listened + (e.path to FileHistory(e.path, if (finished) 0 else pos, finished))
        vm.saveListenPosition(e.path, pos, finished)
    }

    /** The slideshow goes away, by any way out (Back, the menu, Esc): the item on screen saves its listening position. */
    fun onLeave() {
        current?.let(::saveListened)
    }

    fun close(message: String? = null) {
        message?.let { vm.errors.notify(it, log = false) }
        vm.closeSlideshow(current?.path)
    }

    /** Saved for device folders too, so Back knows the last item; the app never resumes those (todo §11.4). */
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

    /**
     * Runs the item timer; restarted (by the screen) whenever the item, its timing, pause or the
     * menu changes, and when the app is visible again. Time already shown is kept across pauses and timing changes, and belongs to
     * this item only: [show] resets the clock before a cancelled tick of the previous item ends.
     */
    suspend fun tick() {
        stopClock()
        val token = ++tickToken
        tickJob = currentCoroutineContext()[Job]
        val t = timing ?: return
        val e = current ?: return
        if (paused || menuOpen || stopped || single) return
        if (endedInMenu) {
            advanceFrom(e)
            return
        }
        runningSince = SystemClock.uptimeMillis()
        try {
            val playsOnce = !t.loop && e.type == MediaType.VIDEO
            val left = t.showMs - elapsedMs
            delay(left - PRELOAD_LEAD_MS)
            preloadNext()
            // A video that plays once advances when it ends; the timer is only a fallback.
            delay(minOf(left, PRELOAD_LEAD_MS) + if (playsOnce) 2000 else 0)
            if (current === e) advanceFrom(e)
        } finally {
            if (tickToken == token) stopClock()
        }
    }

    private companion object {
        /** A manual step that waits longer than this shows a small spinner (todo §11.1). */
        const val SPINNER_DELAY_MS = 300L
        /** Next, previous and a manual target; one more for a target moved on while waiting. */
        const val MAX_PRELOADS = 4
        const val MAX_VISITS = 15
        /** Going back to an item with less time than this left starts it over. */
        const val MIN_LEFT_MS = 2_000L
        /** The next item starts loading once this one is ready, and at the latest this long before it is due. */
        const val PRELOAD_LEAD_MS = 3_000L
        /** A preload that takes longer than this no longer holds the current item; the next one is shown anyway. */
        const val MAX_PRELOAD_WAIT_MS = 30_000L
    }
}
