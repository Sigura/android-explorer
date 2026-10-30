package com.mediaviewer.ui.slideshow

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * The slideshow's two video players (todo §11.1): one for the slide on screen, and a spare that
 * prepares the video a manual step (or the timer, shortly before it is due) goes to, so the switch
 * shows a video that is already loaded instead of a black slide. When that video is shown the
 * spare becomes the slide's player, and the other one, paused on its last frame while its slide
 * fades out, becomes the spare. Main thread only.
 */
@Stable
class SlidePlayers(build: () -> ExoPlayer) {
    private val players = listOf(build(), build())
    /** The path each player holds, or null. */
    private val loaded = arrayOfNulls<String>(2)
    private var onScreen = 0

    /** Bumped when a player gets another video, so each slide picks its player again. */
    var version by mutableIntStateOf(0)
        private set

    /** The player of the slide on screen. */
    val current: ExoPlayer get() = players[onScreen]

    val all: List<ExoPlayer> get() = players

    /** The player holding [path], for its slide's view; null once it holds another video. */
    fun playerFor(path: String): ExoPlayer? {
        version // read, so the slide recomposes when players change hands
        return players.indices.firstOrNull { loaded[it] == path }?.let { players[it] }
    }

    /**
     * Prepares [path] in the spare player at [positionMs], paused, and waits until it is ready
     * (null) or fails (the reason). When the spare is given another video meanwhile, or the video
     * is shown before it is ready, this ends with null: whoever is waiting no longer needs it.
     * **Verify** whether the first frame is decoded before the slide gives the player a surface.
     */
    suspend fun prepare(uri: Uri, path: String, positionMs: Long): String? {
        val i = players.indices.firstOrNull { loaded[it] == path } ?: (1 - onScreen).also { s ->
            val p = players[s]
            p.playWhenReady = false
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            if (positionMs > 0) p.seekTo(positionMs)
            loaded[s] = path
            version++
        }
        val p = players[i]
        // A player kept from earlier (the video before, paused or played to its end) goes back to
        // where the item should start; one that ended would otherwise never be READY again.
        if (i != onScreen && (p.playbackState == Player.STATE_ENDED || kotlin.math.abs(p.currentPosition - positionMs) > SEEK_SLACK_MS)) {
            p.seekTo(positionMs)
        }
        if (p.playbackState == Player.STATE_IDLE && p.playerError == null) p.prepare()
        while (true) {
            if (loaded[i] != path || i == onScreen) return null
            p.playerError?.let { return it.errorCodeName }
            if (p.playbackState == Player.STATE_READY) return null
            delay(POLL_MS)
        }
    }

    /**
     * The slide now shows [path] from [positionMs]. The spare becomes the slide's player when it
     * holds [path] (true); otherwise the on-screen player loads it, as with one player (false).
     */
    fun show(uri: Uri, path: String, positionMs: Long, play: Boolean): Boolean {
        val spare = 1 - onScreen
        // A spare that failed is not handed over: the slide's own player tries, and reports the error.
        val prepared = loaded[spare] == path && players[spare].playerError == null
        if (prepared) {
            // Paused, not stopped: its slide shows the last frame while it fades out.
            players[onScreen].pause()
            onScreen = spare
            val p = players[onScreen]
            if (kotlin.math.abs(p.currentPosition - positionMs) > SEEK_SLACK_MS) p.seekTo(positionMs)
        } else {
            // A spare that failed with this video lets go of it, so its slide finds only this player.
            if (loaded[spare] == path) {
                players[spare].stop()
                loaded[spare] = null
            }
            val p = players[onScreen]
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            if (positionMs > 0) p.seekTo(positionMs)
            loaded[onScreen] = path
        }
        current.repeatMode = Player.REPEAT_MODE_OFF
        current.playWhenReady = play
        version++
        return prepared
    }

    /** The slide on screen is not a video: its player stops. The spare keeps a video it prepared. */
    fun stopCurrent() {
        val p = players[onScreen]
        if (loaded[onScreen] == null && p.mediaItemCount == 0) return
        p.stop()
        p.clearMediaItems()
        loaded[onScreen] = null
        version++
    }

    fun release() {
        players.forEach { it.release() }
    }

    private companion object {
        const val POLL_MS = 50L
        /** A prepared video this close to the position it should start at isn't seeked again. */
        const val SEEK_SLACK_MS = 500L
    }
}
