package com.mediaviewer.playback

import android.content.ComponentName
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mediaviewer.MediaViewerApp
import com.mediaviewer.data.history.BookFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

/**
 * The UI side of [PlaybackService] (todo §3.3): one `MediaController` shared by the folder Play
 * button and the mini player. Controller calls run on the main thread (they are cheap: the player
 * lives in this process); building the queue and reading history run on IO.
 */
class BookPlayer(private val app: MediaViewerApp) {
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

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

    /** Stops playback and hides the mini player; the position is saved by the service. */
    fun stop() {
        controller?.let {
            it.pause()
            it.stop()
            it.clearMediaItems()
        }
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
        )
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
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
