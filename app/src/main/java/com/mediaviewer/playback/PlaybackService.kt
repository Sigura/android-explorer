package com.mediaviewer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.mediaviewer.MainActivity
import com.mediaviewer.MediaViewerApp
import com.mediaviewer.data.history.BookFolder
import com.mediaviewer.data.history.ResumePoint
import com.mediaviewer.data.mtp.MediaSources
import com.mediaviewer.data.mtp.MtpPaths
import com.mediaviewer.domain.Book
import com.mediaviewer.domain.Skip
import com.mediaviewer.domain.model.FileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Background playback of books, albums and lectures (todo §3.3). One ExoPlayer with the video
 * track switched off — videos are listened to, never shown (todo §3.6). The system media
 * notification gives lock-screen, Bluetooth, headset, watch and car controls. Positions go to
 * `history.db` every ~5 s while playing and at once on pause, file change and seek; the writes
 * run on IO, never on the main thread.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val app get() = application as MediaViewerApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    /** The file playing, for marking it finished when the player moves on by itself. */
    private var currentPath: String? = null
    /** When the user paused, for smart rewind on resume; 0 = not paused by the user. */
    private var pausedAt = 0L
    private var saveJob: Job? = null
    private var idleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Platform decoders first; the bundled FFmpeg extension for AC-3, DTS and the like.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            // Books on a camera or phone over USB play from the device (todo §11.4).
            .setMediaSourceFactory(MediaSources.factory(this, app.mtp::connection))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Listen only: no video decoding, so a lecture costs about as much battery as an MP3.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        player.addListener(listener)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, SkipPlayer(player)).setCallback(callback).setSessionActivity(open).build()
        // Skip silence is a setting (todo §4.6); the service shares the app's process and settings.
        scope.launch {
            app.settings.settings.map { it.skipSilence }.distinctUntilChanged().collect { player.skipSilenceEnabled = it }
        }
        running = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        save()
        // Swiped away while paused: stop and remove the notification. While playing, keep playing.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        save()
        running = false
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    /** Skip length now: the settings for books or music mode (todo §4.6). */
    private fun skipMs(): Long = app.settings.settings.value.let { Skip.ms(musicMode, it.skipBookSec, it.skipMusicSec) }

    /**
     * The player the session (and so the app, the notification, the lock screen and headsets)
     * sees: ExoPlayer's seek increments are fixed when it is built, so skips are done here, with
     * the length from the settings and the folder's mode. Past the end of a file: the next one.
     */
    private inner class SkipPlayer(p: ExoPlayer) : ForwardingPlayer(p) {
        override fun getSeekBackIncrement(): Long = skipMs()
        override fun getSeekForwardIncrement(): Long = skipMs()

        override fun seekBack() = seekTo((currentPosition - skipMs()).coerceAtLeast(0))

        override fun seekForward() {
            val dur = duration
            val target = currentPosition + skipMs()
            when {
                dur == C.TIME_UNSET || target < dur -> seekTo(target)
                hasNextMediaItem() -> seekToNextMediaItem()
            }
        }
    }

    private val callback = object : MediaSession.Callback {
        // Controllers send media items without their URI; it is rebuilt from the path (the media ID).
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapTo(ArrayList()) { it.buildUpon().setUri(uriOf(it.mediaId)).build() })

        /** Bluetooth / headset Play after the app was killed: continue the last book. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val point = withContext(Dispatchers.IO) {
                    app.history.recentFolders(1).firstOrNull()?.let { BookFolder.resume(it.folder, app.history, app.legacyCharset()) }
                }
                if (point == null) {
                    result.setException(IllegalStateException("Nothing to resume"))
                } else {
                    player.setPlaybackSpeed(point.speed)
                    player.volume = 1f
                    player.repeatMode = point.repeat
                    musicMode = point.music
                    result.set(MediaSession.MediaItemsWithStartPosition(mediaItems(point), point.index, point.positionMs))
                }
            }
            return result
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = currentPath
            currentPath = mediaItem?.mediaId
            if (previous != null && previous != currentPath && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // Played to the end: the next time this file starts from the beginning.
                write(previous, 0, finished = true)
            }
            save()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                idleJob?.cancel()
                if (pausedAt > 0) {
                    // Music mode resumes where it stopped (todo §3.1).
                    val back = if (musicMode) 0L else Book.rewindMs(System.currentTimeMillis() - pausedAt)
                    if (back > 0) player.seekTo((player.currentPosition - back).coerceAtLeast(0))
                    pausedAt = 0
                }
                startSaving()
            } else {
                saveJob?.cancel()
                save()
                if (!player.playWhenReady && player.playbackState == Player.STATE_READY) {
                    pausedAt = System.currentTimeMillis()
                    stopWhenIdle()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                currentPath?.let { write(it, 0, finished = true) }
                val folder = currentPath?.let(BookFolder::parentOf)
                if (folder != null) app.appScope.launch(Dispatchers.IO) { app.history.markBookFinished(folder, true) }
                stopWhenIdle()
            }
        }

        // A new speed is saved with the position: the book resumes at it.
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = save()

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) save()
        }
    }

    private fun startSaving() {
        saveJob?.cancel()
        saveJob = scope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                save()
            }
        }
    }

    /** Paused for 30 min (or the book ended): stop the service, which removes the notification. */
    private fun stopWhenIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_STOP_MS)
            if (!player.isPlaying) {
                save()
                player.stop()
                stopSelf()
            }
        }
    }

    /** Saves the current file and position. Reads the player here (main thread), writes on IO. */
    private fun save() {
        if (player.mediaItemCount == 0) return
        val path = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        val duration = player.duration
        write(path, position, finished = duration != C.TIME_UNSET && Book.isFinished(position, duration))
    }

    private fun write(path: String, positionMs: Long, finished: Boolean) {
        val folder = BookFolder.parentOf(path) ?: return
        val speed = player.playbackParameters.speed
        // Where we are in the book, for the folder tiles (todo §10.1).
        val total = player.mediaItemCount
        val index = if (player.currentMediaItem?.mediaId == path) {
            player.currentMediaItemIndex
        } else {
            (0 until total).firstOrNull { player.getMediaItemAt(it).mediaId == path } ?: -1
        }
        val duration = if (index == player.currentMediaItemIndex && player.duration != C.TIME_UNSET) player.duration else 0L
        // The app scope, so a save started in onDestroy still completes.
        app.appScope.launch(Dispatchers.IO) {
            val size = if (MtpPaths.isMtp(path)) app.mtp.connection(path)?.sizeOf(path)?.takeIf { it >= 0 } ?: 0L else File(path).length()
            app.history.save(folder, path, size, positionMs, finished, speed, index, total, duration)
        }
    }

    companion object {
        /** The service is alive in this process: the UI connects to it on start. */
        @Volatile var running = false
            private set

        /**
         * The folder playing is in music mode (todo §3.1): no smart rewind on resume. Set by
         * [BookPlayer], which runs in the same process, when a book starts or shuffle changes.
         */
        @Volatile var musicMode = false

        private const val SAVE_EVERY_MS = 5_000L
        private const val IDLE_STOP_MS = 30 * 60_000L

        /** Media items for [point]'s queue. The media ID is the file path. */
        /** A local file, or a file on a camera or phone over USB, streamed (todo §11.4). */
        fun uriOf(path: String): Uri = if (MtpPaths.isMtp(path)) Uri.parse(path) else Uri.fromFile(File(path))

        fun mediaItems(point: ResumePoint): List<MediaItem> {
            val book = File(point.folder).name
            return point.queue.map { e -> mediaItem(e, book) }
        }

        private fun mediaItem(e: FileEntry, book: String): MediaItem =
            MediaItem.Builder()
                .setMediaId(e.path)
                .setUri(uriOf(e.path))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(e.name)
                        .setAlbumTitle(book)
                        .setArtist(book)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build(),
                )
                .build()
    }
}
