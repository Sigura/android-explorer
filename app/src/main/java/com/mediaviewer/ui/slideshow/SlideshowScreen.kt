package com.mediaviewer.ui.slideshow

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import com.mediaviewer.data.fs.FolderListing
import com.mediaviewer.data.settings.SlideshowSession
import com.mediaviewer.data.settings.Transition
import com.mediaviewer.domain.SlideTiming
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.MouseBack
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun SlideshowScreen(vm: AppViewModel, listing: FolderListing, start: String, resume: SlideshowSession?) {
    val scope = rememberCoroutineScope()
    val c = remember { SlideshowController(vm, listing, start, resume, scope) }
    val settings by vm.settings.collectAsState()
    val ctx = LocalContext.current
    val view = LocalView.current
    val zoom = remember { ZoomState() }
    val focus = remember { FocusRequester() }
    var slideSize by remember { mutableStateOf(IntSize.Zero) }
    /** GIFs on screen (two during a transition), so the one being left can say where it was. */
    val gifs = remember { HashMap<String, SlideGif>() }
    val player = remember {
        // Platform decoders first; the bundled FFmpeg extension when the device has none.
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        // Silent until the mute setting is applied, so a video never blips sound on start.
        ExoPlayer.Builder(ctx, renderers).build().apply { volume = 0f }
    }

    // README §5.3: the screen stays on; immersive, black.
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctl = WindowCompat.getInsetsController(window, view)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        MouseBack.handler = {
            c.previous()
            true
        }
        c.positionOf = { e ->
            when (e.type) {
                MediaType.VIDEO -> player.currentPosition
                MediaType.ANIMATED -> gifs[e.path]?.positionMs ?: 0L
                else -> 0L
            }
        }
        c.preloadImage = { e ->
            val size = slideSize
            if (size == IntSize.Zero) null else SingletonImageLoader.get(ctx).execute(slideRequest(ctx, e, size))
        }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctl.show(WindowInsetsCompat.Type.systemBars())
            MouseBack.handler = null
            c.videoPositionMs = player.currentPosition
            c.onLeave()
            player.release()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_PAUSE -> {
                    c.videoPositionMs = player.currentPosition
                    c.saveSession(force = true)
                }
                // Stop, not pause: in DeX and split screen (Android 9) a visible window is paused
                // as soon as another window gets focus, and the video should keep playing (README §3.9).
                Lifecycle.Event.ON_STOP -> {
                    player.pause()
                    c.onVisible(false)
                }
                Lifecycle.Event.ON_START -> {
                    c.onVisible(true)
                    if (!c.paused) player.play()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    BackHandler(enabled = c.menuOpen) { c.closeMenu() }

    val current = c.current
    LaunchedEffect(c.generation) { zoom.reset() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(c.menuOpen) { if (!c.menuOpen) focus.requestFocus() }

    // The item timer (README §5.1).
    LaunchedEffect(c.generation, c.timing, c.paused, c.menuOpen, c.starts, c.single) { c.tick() }

    // Video playback with one player instance.
    LaunchedEffect(c.generation) {
        val e = current
        if (e == null || e.type != MediaType.VIDEO) {
            player.stop()
            player.clearMediaItems()
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(e.path))))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.prepare()
        if (c.resumePositionMs > 0) player.seekTo(c.resumePositionMs)
        player.playWhenReady = !c.paused
        while (true) {
            delay(1000)
            c.videoPositionMs = player.currentPosition
            c.saveSession()
        }
    }
    LaunchedEffect(settings.intervalSec) { c.onIntervalChanged() }
    LaunchedEffect(settings.muted) {
        player.volume = if (settings.muted) 0f else 1f
        // With sound on, the video takes audio focus, so a playing book pauses (todo §3.3).
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            !settings.muted,
        )
    }
    // The open menu only stops the timer; the video keeps playing.
    LaunchedEffect(c.paused) { if (c.current?.type == MediaType.VIDEO) player.playWhenReady = !c.paused }
    LaunchedEffect(c.timing, c.single) {
        if (c.current?.type == MediaType.VIDEO) {
            player.repeatMode = if (c.timing?.loop == true || c.single) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val e = c.current ?: return
                if (e.type != MediaType.VIDEO) return
                when (state) {
                    Player.STATE_READY -> {
                        val size = player.videoSize
                        c.onReady(e, player.duration.takeIf { it > 0 }, size.width, size.height)
                    }
                    Player.STATE_ENDED -> c.onPlaybackEnded(e)
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                c.current?.let { c.onFailed(it, error.errorCodeName) }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { slideSize = it }
            .onKeyEvent { ev -> handleKey(c, ev.key, ev.type, settings.invertSwipe) }
            .focusRequester(focus)
            .focusable()
            .slideGestures(
                zoom = zoom,
                isMenuOpen = { c.menuOpen },
                isInMenu = { p -> c.menuBounds?.contains(p) == true },
                onZone = { zone ->
                    when (zone) {
                        TapZone.LEFT -> c.previous()
                        TapZone.RIGHT -> c.next()
                        TapZone.CENTER -> c.openMenu()
                    }
                },
                onCloseMenu = c::closeMenu,
                onSwipe = { dx ->
                    // Original request: swipe left = previous, swipe right = next (README §5.2).
                    val forward = if (settings.invertSwipe) dx < 0 else dx > 0
                    if (forward) c.next() else c.previous()
                },
                onWheel = { forward -> if (forward) c.next() else c.previous() },
            ),
    ) {
        AnimatedContent(
            targetState = current,
            contentKey = { it?.path },
            transitionSpec = { transitionFor(settings.transition, c.direction) },
            label = "slide",
        ) { e ->
            // The image is decoded for the slide's size, so it waits for the first layout.
            if (e != null && slideSize != IntSize.Zero) Slide(e, c, player, zoom, gifs, vm.thumbnails.largeGifBytes, slideSize)
        }
        val t = c.timing
        if (t != null && !c.single && current != null) {
            ItemTimer(c, current, t, player)
        }
        PositionFlash(c)
        if (c.paused && !c.menuOpen) {
            Icon(
                Icons.Rounded.Pause,
                contentDescription = "Paused",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .padding(6.dp)
                    .size(28.dp),
            )
        }
        if (!settings.zonesHintShown) {
            ZonesHint { vm.settingsStore.update { it.copy(zonesHintShown = true) } }
        } else if (!settings.windowHintShown && isWindowed()) {
            WindowHint { vm.settingsStore.update { it.copy(windowHintShown = true) } }
        }
        if (c.menuOpen) {
            SlideshowMenu(c, vm, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** After a manual step, the position (e.g. `12 / 29,102`) shows for a moment. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PositionFlash(c: SlideshowController) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(c.generation) {
        if (c.reachedAutomatically || c.menuOpen || c.progress.second == 0) return@LaunchedEffect
        visible = true
        delay(1500)
        visible = false
    }
    androidx.compose.animation.AnimatedVisibility(
        visible && !c.menuOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 16.dp),
    ) {
        val (pos, total) = c.progress
        Text(
            "%,d / %,d".format(pos, total),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** True when the app runs in a window smaller than the screen (DeX, Chromebooks, split screen). */
@Composable
private fun isWindowed(): Boolean {
    val activity = LocalView.current.context as? Activity ?: return false
    val cfg = LocalConfiguration.current
    return remember(cfg) {
        if (Build.VERSION.SDK_INT >= 30) {
            val wm = activity.windowManager
            wm.currentWindowMetrics.bounds != wm.maximumWindowMetrics.bounds
        } else {
            activity.isInMultiWindowMode
        }
    }
}

/**
 * One-time hint in a windowed slideshow (README §3.9): an app can't maximise its own window, so
 * full screen is the window's maximise button (DeX also starts the app full screen).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.WindowHint(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(4000)
        onDone()
    }
    Text(
        "Maximise the window for full screen",
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 24.dp)
            .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * One-time overlay that explains the tap zones (README §5.2). Disappears on its own after a few
 * seconds or on the first touch, and is not shown again.
 */
@Composable
private fun ZonesHint(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(4500)
        onDone()
    }
    Row(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // The touch that dismisses the hint must not also navigate.
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    onDone()
                }
            },
    ) {
        listOf("‹  Previous", "Menu\n(tap, double tap or hold)", "Next  ›").forEachIndexed { i, label ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = if (i == 1) 0.35f else 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun handleKey(c: SlideshowController, key: Key, type: KeyEventType, invert: Boolean): Boolean {
    if (type != KeyEventType.KeyDown) return false
    if (c.menuOpen) {
        c.touchMenu()
        return when (key) {
            Key.Escape, Key.M, Key.Menu -> {
                c.closeMenu()
                true
            }
            else -> false
        }
    }
    return when (key) {
        Key.DirectionLeft, Key.MediaPrevious -> c.previous().let { true }
        Key.DirectionRight, Key.Spacebar, Key.MediaNext -> c.next().let { true }
        Key.M, Key.Menu, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> c.openMenu().let { true }
        Key.P, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
            c.paused = !c.paused
            true
        }
        Key.Escape, Key.Backspace -> c.close().let { true }
        else -> false
    }
}

private fun transitionFor(t: Transition, dir: Int): ContentTransform = when (t) {
    Transition.NONE -> EnterTransition.None togetherWith ExitTransition.None
    Transition.FADE -> fadeIn(tween(250)) togetherWith fadeOut(tween(250))
    Transition.SLIDE -> slideInHorizontally(tween(300)) { w -> w * dir } togetherWith
        slideOutHorizontally(tween(300)) { w -> -w * dir }
}

@Composable
private fun Slide(
    e: FileEntry,
    c: SlideshowController,
    player: ExoPlayer,
    zoom: ZoomState,
    gifs: MutableMap<String, SlideGif>,
    largeGifBytes: Long,
    size: IntSize,
) {
    val ctx = LocalContext.current
    val zoomMod = Modifier
        .fillMaxSize()
        .graphicsLayer {
            scaleX = zoom.scale
            scaleY = zoom.scale
            translationX = zoom.offset.x
            translationY = zoom.offset.y
        }
    when (e.type) {
        MediaType.VIDEO -> AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            onRelease = { it.player = null },
            modifier = zoomMod,
        )
        MediaType.IMAGE, MediaType.ANIMATED -> {
            // GIFs play with the app's own decoder, which can start where the GIF was left and pause
            // on a frame; very large ones, and files that aren't GIFs inside, use the platform's.
            val gif = remember(e.path) {
                if (e.type == MediaType.ANIMATED && e.size <= largeGifBytes) SlideGif(File(e.path), c.resumePositionMs) else null
            }
            if (gif != null && gif.state != SlideGif.State.UNSUPPORTED) {
                PlayedGif(e, gif, c, gifs, zoomMod)
            } else {
                val request = remember(e.path) { slideRequest(ctx, e, size) }
                AsyncImage(
                    model = request,
                    contentDescription = e.name,
                    contentScale = ContentScale.Fit,
                    modifier = zoomMod,
                    onSuccess = { s -> c.onReady(e, null, s.result.image.width, s.result.image.height) },
                    onError = { s -> c.onFailed(e, s.result.throwable.message ?: "can't decode") },
                )
            }
        }
        else -> Unit
    }
}

/**
 * The slide's image request. The preload (README §5.3) builds the same one, so the slide finds the
 * decoded bitmap in Coil's memory cache; an animation isn't preloaded, it would be decoded twice.
 */
private fun slideRequest(ctx: android.content.Context, e: FileEntry, size: IntSize): ImageRequest =
    ImageRequest.Builder(ctx).data(File(e.path)).size(size.width, size.height).scale(Scale.FIT).build()

@Composable
private fun PlayedGif(e: FileEntry, gif: SlideGif, c: SlideshowController, gifs: MutableMap<String, SlideGif>, modifier: Modifier) {
    DisposableEffect(gif) {
        gifs[e.path] = gif
        onDispose { if (gifs[e.path] === gif) gifs.remove(e.path) }
    }
    LaunchedEffect(gif) { gif.play() }
    // Paused, the GIF stays on its frame and goes on from there.
    LaunchedEffect(gif, c.paused) { gif.running.value = !c.paused }
    LaunchedEffect(gif.state) {
        when (gif.state) {
            SlideGif.State.PLAYING -> gif.frame?.let { c.onReady(e, null, it.width, it.height) }
            SlideGif.State.FAILED -> c.onFailed(e, gif.error ?: "can't decode")
            else -> Unit
        }
    }
    gif.frame?.let { Image(it, contentDescription = e.name, contentScale = ContentScale.Fit, modifier = modifier) }
}

/**
 * Thin timer bar at the bottom (README §5.1): how much of the item's time has passed. It follows
 * the controller's clock, so it stops while paused or in the menu and ends when the item does.
 * A video that plays once follows the player instead.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ItemTimer(c: SlideshowController, e: FileEntry, t: SlideTiming, player: ExoPlayer) {
    var progress by remember(c.generation) { mutableFloatStateOf(0f) }
    LaunchedEffect(c.generation, t) {
        while (true) {
            progress = if (e.type == MediaType.VIDEO && !t.loop) {
                player.currentPosition.toFloat() / player.duration.coerceAtLeast(1)
            } else {
                c.shownMs().toFloat() / t.showMs.coerceAtLeast(1)
            }.coerceIn(0f, 1f)
            delay(100)
        }
    }
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
        color = Color.White.copy(alpha = 0.7f),
        trackColor = Color.Transparent,
    )
}
