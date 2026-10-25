package com.mediaviewer.ui.browser

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.mediaviewer.domain.SmartCrop
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import java.io.File

/**
 * Live playback slots for selected GIF / video tiles (README §4.3). Tiles queue in the order they
 * ask; the first [MAX_LIVE] play, the rest keep their frame strip until a slot frees up (a tile
 * is deselected or scrolls away). Hardware video decoders are few, so the cap stays small.
 */
private object LiveSlots {
    val queue = mutableStateListOf<String>()
}

private const val MAX_LIVE = 4

/**
 * Plays a selected GIF or video inside its tile, whole and looped, muted, with the tile's smart
 * crop. Draws over the strip frame, which stays visible until the first frame is ready.
 */
@Composable
fun BoxScope.LivePreview(entry: FileEntry, srcW: Int, srcH: Int) {
    DisposableEffect(entry.path) {
        LiveSlots.queue.add(entry.path)
        onDispose { LiveSlots.queue.remove(entry.path) }
    }
    val idx = LiveSlots.queue.indexOf(entry.path)
    if (idx !in 0 until MAX_LIVE) return
    val cfg = LocalTileConfig.current
    val crop = SmartCrop.shouldCrop(srcW, srcH, cfg.sizePx.width.toFloat(), cfg.sizePx.height.toFloat(), cfg.cropMode, cfg.cropThresholdPct)
    if (entry.type == MediaType.VIDEO) {
        LiveVideo(entry, srcW, srcH, crop)
    } else {
        val ctx = LocalContext.current
        val request = remember(entry.path, cfg.sizePx) {
            ImageRequest.Builder(ctx).data(File(entry.path)).size(cfg.sizePx.width, cfg.sizePx.height).build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BoxScope.LiveVideo(entry: FileEntry, srcW: Int, srcH: Int, crop: Boolean) {
    val ctx = LocalContext.current
    var shown by remember(entry.path) { mutableStateOf(false) }
    val player = remember(entry.path) {
        val renderers = DefaultRenderersFactory(ctx).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(ctx, renderers).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(entry.path))))
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    shown = true
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            player.release()
        }
    }
    // A TextureView (not a SurfaceView) so the tile's clip, rounding and focus scale apply.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (w, h) = scaled(maxWidth, maxHeight, srcW, srcH, crop)
        AndroidView(
            factory = { TextureView(it).also(player::setVideoTextureView) },
            onRelease = { player.clearVideoTextureView(it) },
            modifier = Modifier.align(Alignment.Center).requiredSize(w, h).alpha(if (shown) 1f else 0f),
        )
    }
}

/** View size for a [srcW]×[srcH] video that fills (crop) or fits the tile. */
private fun scaled(tileW: Dp, tileH: Dp, srcW: Int, srcH: Int, crop: Boolean): Pair<Dp, Dp> {
    if (srcW <= 0 || srcH <= 0) return tileW to tileH
    val sx = tileW.value / srcW
    val sy = tileH.value / srcH
    val s = if (crop) maxOf(sx, sy) else minOf(sx, sy)
    return Dp(srcW * s) to Dp(srcH * s)
}
