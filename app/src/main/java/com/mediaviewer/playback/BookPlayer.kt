package com.mediaviewer.playback

import android.content.ComponentName
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mediaviewer.MediaViewerApp
import com.mediaviewer.data.history.BookFolder
import com.mediaviewer.domain.BookTimes
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the mini player shows. */
data class NowPlaying(
    /** The file playing; the folder view marks and focuses its tile. */
    val path: String,
    val folder: String,
    val title: String,
    val index: Int,
    val count: Int,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float = 1f,
    /** Loading or waiting for data (slow USB storage): the play button shows a spinner. */
    val buffering: Boolean = false,
    /** Sleep timer: wall-clock time it pauses at, 0 = off. */
    val sleepAtMs: Long = 0,
    /** Sleep timer: pauses when this file ends. */
    val sleepEndOfFile: Boolean = false,
)

/**
 * The UI side of [PlaybackService] (todo §3.3): one `MediaController` shared by the folder Play
 * button and the mini player. Controller calls run on the main thread (they are cheap: the player
 * lives in this process); building the queue and reading history run on IO.
 */
class BookPlayer(private val app: MediaViewerApp) {
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private val _times = MutableStateFlow<BookTimes?>(null)
    /** Durations of the queue's files, for the book's total and the segmented bar. */
    val times: StateFlow<BookTimes?> = _times.asStateFlow()
    private var timesJob: Job? = null

    private var sleepJob: Job? = null
    private var sleepAt = 0L
    private var sleepEndOfFile = false

    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val waiting = ArrayList<(MediaController) -> Unit>()

    /** On app start: show the mini player if the service is still playing from before. */
    fun connectIfRunning() {
        if (PlaybackService.running) connect {}
    }

    /** Starts or continues the book in [folder]. Returns the file it starts with, or null when it has nothing to play. */
    suspend fun play(folder: String): String? {
        val point = withContext(Dispatchers.IO) { BookFolder.resume(folder, app.history, app.legacyCharset()) } ?: return null
        val items = PlaybackService.mediaItems(point)
        connect { c ->
            c.setMediaItems(items, point.index, point.positionMs)
            c.setPlaybackSpeed(point.speed)
            c.prepare()
            c.play()
        }
        return point.queue.getOrNull(point.index)?.path
    }

    fun togglePlay() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun skipBack() = controller?.seekBack()
    fun skipForward() = controller?.seekForward()

    /** ⏮: the previous file within the first 3 s of this one, otherwise back to its start. */
    fun previous() = controller?.seekToPrevious()

    /** ⏭: the next file in the queue. */
    fun next() = controller?.seekToNextMediaItem()

    /** The seek bar: a position in the file playing. */
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    /** The book bar: the start of file [index]. */
    fun playFile(index: Int) = controller?.seekTo(index, 0)

    /** Saved with the position, so the book resumes at this speed. */
    fun setSpeed(speed: Float) = controller?.setPlaybackSpeed(speed)

    /** Pauses in [minutes]; replaces a timer already set. */
    fun sleepIn(minutes: Int) {
        cancelSleep()
        val ms = minutes * 60_000L
        sleepAt = System.currentTimeMillis() + ms
        sleepJob = app.appScope.launch {
            delay(ms)
            sleepAt = 0
            controller?.pause()
            refresh()
        }
        refresh()
    }

    /** Pauses when the file playing ends. */
    fun sleepAtEndOfFile() {
        cancelSleep()
        sleepEndOfFile = true
        refresh()
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        sleepAt = 0
        sleepEndOfFile = false
        refresh()
    }

    /** Stops playback and hides the mini player; the position is saved by the service. */
    fun stop() {
        controller?.let {
            it.pause()
            it.stop()
            it.clearMediaItems()
        }
        cancelSleep()
        timesJob?.cancel()
        _times.value = null
        _state.value = null
    }

    /** Position for the mini player's progress line; polled by the UI while it is visible. */
    fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null || c.mediaItemCount == 0) {
            _state.value = null
            return
        }
        _state.value = NowPlaying(
            path = item.mediaId,
            folder = File(item.mediaId).parent ?: "",
            title = item.mediaMetadata.title?.toString() ?: File(item.mediaId).name,
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            speed = c.playbackParameters.speed,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            sleepAtMs = sleepAt,
            sleepEndOfFile = sleepEndOfFile,
        )
        val t = _times.value
        if (t == null || t.paths.size != c.mediaItemCount || t.paths.getOrNull(c.currentMediaItemIndex) != item.mediaId) loadTimes(c)
    }

    /**
     * Reads the duration of every file in the queue, one at a time on IO under the thumbnail
     * watchdog; cached in `meta.db`, so a book read once is instant. Publishes as it goes.
     */
    private fun loadTimes(c: MediaController) {
        val paths = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId }
        val durations = arrayOfNulls<Long>(paths.size)
        timesJob?.cancel()
        _times.value = BookTimes(paths, durations.toList())
        timesJob = app.appScope.launch(Dispatchers.IO) {
            var published = System.currentTimeMillis()
            paths.forEachIndexed { i, path ->
                durations[i] = try {
                    val f = File(path)
                    val entry = FileEntry(path, f.name, MediaType.OTHER, f.length(), f.lastModified(), OtherKind.AUDIO)
                    app.thumbnails.videoMeta(entry).durationMs.takeIf { it > 0 }
                } catch (e: Exception) {
                    ensureActive()
                    null
                }
                if (i == paths.lastIndex || System.currentTimeMillis() - published > 500) {
                    _times.value = BookTimes(paths, durations.toList())
                    published = System.currentTimeMillis()
                }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (sleepEndOfFile && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                sleepEndOfFile = false
                controller?.pause()
            }
        }
    }

    private fun connect(then: (MediaController) -> Unit) {
        controller?.let {
            then(it)
            return
        }
        waiting.add(then)
        if (connecting != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        connecting = future
        future.addListener({
            connecting = null
            val c = try {
                future.get() // already complete: doesn't block
            } catch (e: Exception) {
                Log.w("MediaViewer", "Can't connect to the player", e)
                waiting.clear()
                return@addListener
            }
            controller = c
            c.addListener(listener)
            val pending = waiting.toList()
            waiting.clear()
            pending.forEach { it(c) }
            refresh()
        }, ContextCompat.getMainExecutor(app))
    }
}
