package com.mediaviewer.ui.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediaviewer.domain.BookTimes
import com.mediaviewer.domain.PlayerSpeed
import com.mediaviewer.domain.SleepTimer
import com.mediaviewer.playback.BookPlayer
import com.mediaviewer.playback.NowPlaying
import com.mediaviewer.playback.SleepMode
import com.mediaviewer.ui.common.TipIconButton
import com.mediaviewer.ui.common.formatDuration
import com.mediaviewer.ui.common.middleEllipsis
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

/** Space the player takes at the bottom: grids and lists pad by it, so their last row stays visible. */
val LocalMiniPlayerHeight = staticCompositionLocalOf { 0.dp }

/** The collapsed player: one row. */
val MINI_PLAYER_HEIGHT: Dp = 64.dp

// The full player's rows, top to bottom.
private val BOOK_BAR = 10.dp
private val INFO_ROW = 46.dp
private val SEEK_ROW = 36.dp
private val CONTROL_ROW = 48.dp
private val BOTTOM_PAD = 4.dp
val FULL_PLAYER_HEIGHT: Dp = BOOK_BAR + INFO_ROW + SEEK_ROW + CONTROL_ROW + BOTTOM_PAD

private val SPEEDS = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SLEEP_MINUTES = intArrayOf(15, 30, 60)

/** The skip buttons, as long as the settings say for books or music mode (todo §4.6); icons with a number for 5, 10 and 30 s. */
@Composable
internal fun SkipBackButton(now: NowPlaying, player: BookPlayer, modifier: Modifier, iconSize: Dp = 24.dp) {
    val sec = now.skipMs / 1000
    val label = "Back $sec seconds"
    val icon = when (sec) {
        5L -> Icons.Rounded.Replay5
        10L -> Icons.Rounded.Replay10
        30L -> Icons.Rounded.Replay30
        else -> Icons.Rounded.FastRewind
    }
    TipIconButton(label, onClick = { player.skipBack() }, modifier = modifier) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun SkipForwardButton(now: NowPlaying, player: BookPlayer, modifier: Modifier, iconSize: Dp = 24.dp) {
    val sec = now.skipMs / 1000
    val label = "Forward $sec seconds"
    val icon = when (sec) {
        5L -> Icons.Rounded.Forward5
        10L -> Icons.Rounded.Forward10
        30L -> Icons.Rounded.Forward30
        else -> Icons.Rounded.FastForward
    }
    TipIconButton(label, onClick = { player.skipForward() }, modifier = modifier) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
    }
}

/**
 * The player (todo §4.3), at the bottom of the root and folder screens while a book is loaded.
 * Full: the book bar (one segment per file, tap to jump), title, folder and where we are in the
 * book, a seek bar with elapsed and remaining time, then speed, ⏮, −30 s, play / pause, +30 s, ⏭
 * and the sleep timer. [compact] is the old one-row bar. A swipe down collapses it, a swipe up
 * expands it, and a swipe up on the expanded bar or a tap on the title opens the player screen.
 */
@Composable
fun MiniPlayer(
    now: NowPlaying,
    player: BookPlayer,
    compact: Boolean,
    onCompact: (Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The position moves on its own while playing; the bar polls it twice a second, only then.
    LaunchedEffect(now.playing) {
        while (now.playing) {
            delay(500)
            player.refresh()
        }
    }
    val setCompact by rememberUpdatedState(onCompact)
    val openPlayer by rememberUpdatedState(onOpenPlayer)
    val isCompact by rememberUpdatedState(compact)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().pointerInput(Unit) {
            var dy = 0f
            detectVerticalDragGestures(
                onDragStart = { dy = 0f },
                onDragEnd = {
                    val t = 24.dp.toPx()
                    when {
                        dy < -t && isCompact -> setCompact(false)
                        dy < -t -> openPlayer()
                        dy > t -> setCompact(true)
                    }
                },
            ) { change, amount ->
                change.consume()
                dy += amount
            }
        },
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            if (compact) CompactPlayer(now, player, onCompact, onOpenPlayer) else FullPlayer(now, player, onCompact, onOpenPlayer)
        }
    }
}

@Composable
private fun CompactPlayer(now: NowPlaying, player: BookPlayer, onCompact: (Boolean) -> Unit, onOpenPlayer: () -> Unit) {
    val narrow = LocalConfiguration.current.screenWidthDp < 480
    LinearProgressIndicator(
        progress = { trackFraction(now) },
        modifier = Modifier.fillMaxWidth().height(2.dp),
        drawStopIndicator = {},
    )
    Row(
        Modifier.height(MINI_PLAYER_HEIGHT - 2.dp).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val time = if (now.durationMs > 0) "${formatDuration(now.positionMs)} / ${formatDuration(now.durationMs)}" else formatDuration(now.positionMs)
        TitleBlock(now, "${now.index + 1} / ${now.count} · $time", onOpenPlayer, Modifier.weight(1f))
        // Phones: the transport buttons only, so the name keeps some room.
        val b = Modifier.size(if (narrow) 40.dp else 48.dp)
        if (!narrow) PreviousButton(player, b)
        SkipBackButton(now, player, b)
        PlayPauseButton(now, player, b)
        SkipForwardButton(now, player, b)
        if (!narrow) NextButton(now, player, b)
        if (!now.playing) StopButton(player, b)
        TipIconButton("Expand player", onClick = { onCompact(false) }, modifier = b) { Icon(Icons.Rounded.ExpandLess, contentDescription = "Expand player") }
    }
}

@Composable
private fun FullPlayer(now: NowPlaying, player: BookPlayer, onCompact: (Boolean) -> Unit, onOpenPlayer: () -> Unit) {
    val narrow = LocalConfiguration.current.screenWidthDp < 480
    val times by player.times.collectAsState()
    // Music mode has no book bar (todo §3.1); the space stays, so the grid's padding still fits.
    if (now.music) Spacer(Modifier.height(BOOK_BAR)) else BookBar(now, times, onPick = { player.playFile(it) }, Modifier.fillMaxWidth().height(BOOK_BAR))
    Row(Modifier.height(INFO_ROW).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TitleBlock(now, bookLine(now, times), onOpenPlayer, Modifier.weight(1f))
        if (!now.playing) StopButton(player, Modifier.size(40.dp))
        TipIconButton("Collapse player", onClick = { onCompact(true) }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.ExpandMore, contentDescription = "Collapse player")
        }
    }
    SeekRow(now, player, Modifier.fillMaxWidth().height(SEEK_ROW).padding(horizontal = 8.dp))
    Row(Modifier.fillMaxWidth().height(CONTROL_ROW).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Equal sides keep the transport buttons centred whatever the speed label says.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { SpeedButton(now, player) }
        val b = Modifier.size(if (narrow) 40.dp else 48.dp)
        PreviousButton(player, b)
        SkipBackButton(now, player, b)
        PlayPauseButton(now, player, b, big = true)
        SkipForwardButton(now, player, b)
        NextButton(now, player, b)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { SleepButton(now, player) }
    }
    Spacer(Modifier.height(BOTTOM_PAD))
}

/** Title (without the extension when it is the file name) over [info]; a tap opens the player screen. */
@Composable
private fun TitleBlock(now: NowPlaying, info: String, onOpenPlayer: () -> Unit, modifier: Modifier) {
    val name = File(now.path).name
    val title = if (now.title == name) File(name).nameWithoutExtension.ifEmpty { name } else now.title
    Column(
        modifier
            .widthIn(min = 0.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Open the player", onClick = onOpenPlayer)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        Row {
            // The folder name gives way first; the numbers stay.
            Text(
                middleEllipsis(File(now.folder).name, 40),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                " · $info",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `3 / 24 · 2:13:05 / 9:41:00 · 7:28:10 left`; `≈` until every file's duration is known. Music: the file's time. */
internal fun bookLine(now: NowPlaying, times: BookTimes?): String {
    val files = "${now.index + 1} / ${now.count}"
    if (now.music) return if (now.durationMs > 0) "$files · ${formatDuration(now.durationMs)}" else files
    if (now.count < 2 || times == null) return files
    val p = times.progress(now.index, now.positionMs, now.durationMs)
    val about = if (p.exact) "" else "≈"
    return "$files · ${formatDuration(p.elapsedMs)} / $about${formatDuration(p.totalMs)} · $about${formatDuration(p.leftMs(now.speed))} left"
}

internal fun trackFraction(now: NowPlaying): Float =
    if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f

/** One segment per file, as wide as it is long; played files filled. A tap starts the file under it. */
@Composable
internal fun BookBar(now: NowPlaying, times: BookTimes?, onPick: (Int) -> Unit, modifier: Modifier) {
    val weights = remember(times, now.index, now.durationMs) { times?.weights(now.index, now.durationMs) ?: List(now.count) { 1f } }
    val fill = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val fraction = trackFraction(now)
    val pick by rememberUpdatedState(onPick)
    Canvas(
        modifier
            .pointerInput(weights) {
                if (weights.size < 2) return@pointerInput
                detectTapGestures { o ->
                    val target = o.x / size.width * weights.sum()
                    var acc = 0f
                    val i = weights.indexOfFirst { w -> acc += w; acc >= target }
                    pick(if (i < 0) weights.lastIndex else i)
                }
            }
            .semantics { contentDescription = "File ${now.index + 1} of ${now.count}" },
    ) {
        val h = 4.dp.toPx()
        val total = weights.sum()
        val gap = if (size.width / weights.size > 8.dp.toPx()) 2.dp.toPx() else 0f
        var x = 0f
        weights.forEachIndexed { i, w ->
            val segW = size.width * w / total
            val drawW = (segW - if (i < weights.lastIndex) gap else 0f).coerceAtLeast(0f)
            drawRect(rest, Offset(x, 0f), Size(drawW, h))
            val done = when {
                i < now.index -> 1f
                i == now.index -> fraction
                else -> 0f
            }
            if (done > 0f) drawRect(fill, Offset(x, 0f), Size(drawW * done, h))
            x += segW
        }
    }
}

/**
 * Elapsed time, the seek bar, time left (at the playback speed). Dragging previews the target
 * time and seeks once on release, so the twice-a-second refresh can't pull the thumb back.
 * [marks] (chapter starts, ms) are drawn as ticks across the track; with [bubble], the target
 * time shows above the thumb while dragging (the player screen, todo §4.6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeekRow(now: NowPlaying, player: BookPlayer, modifier: Modifier, marks: List<Long> = emptyList(), bubble: Boolean = false) {
    var drag by remember(now.path) { mutableStateOf<Float?>(null) }
    // After release: shown until the player reports the new position.
    var target by remember(now.path) { mutableStateOf<Long?>(null) }
    LaunchedEffect(target) {
        if (target != null) {
            delay(1500)
            target = null
        }
    }
    val t = target
    if (t != null && abs(now.positionMs - t) < 1500) target = null
    val dur = now.durationMs
    val pos = drag?.toLong() ?: target ?: now.positionMs
    val interaction = remember { MutableInteractionSource() }
    val labelStyle = MaterialTheme.typography.labelMedium
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatDuration(pos),
            Modifier.width(56.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
            style = labelStyle,
            color = if (drag != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = pos.toFloat().coerceIn(0f, dur.coerceAtLeast(1).toFloat()),
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let {
                    target = it.toLong()
                    player.seekTo(it.toLong())
                }
                drag = null
            },
            valueRange = 0f..dur.coerceAtLeast(1).toFloat(),
            enabled = dur > 0,
            interactionSource = interaction,
            thumb = {
                Box(contentAlignment = Alignment.Center) {
                    val d = drag
                    if (bubble && d != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.inverseSurface,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.wrapContentSize(unbounded = true).offset(y = (-32).dp),
                        ) {
                            Text(
                                formatDuration(d.toLong()),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    SeekThumb(interaction)
                }
            },
            track = { state ->
                Box(contentAlignment = Alignment.Center) {
                    SliderDefaults.Track(state, Modifier.height(4.dp), drawStopIndicator = null, thumbTrackGapSize = 0.dp)
                    if (marks.isNotEmpty() && dur > 0) {
                        val tick = MaterialTheme.colorScheme.surface
                        Canvas(Modifier.matchParentSize()) {
                            for (m in marks) {
                                if (m <= 0 || m >= dur) continue
                                val x = size.width * m / dur
                                drawRect(tick, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { stateDescription = "${formatDuration(pos)} of ${formatDuration(dur)}" },
        )
        Text(
            if (dur > 0) "−" + formatDuration(((dur - pos).coerceAtLeast(0) / now.speed).toLong()) else "",
            Modifier.width(56.dp),
            maxLines = 1,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A small dot that grows while held. */
@Composable
private fun SeekThumb(interaction: MutableInteractionSource) {
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(if (dragged || pressed) 18.dp else 12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}

@Composable
internal fun PreviousButton(player: BookPlayer, modifier: Modifier) =
    TipIconButton("Previous file", onClick = { player.previous() }, modifier = modifier) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous file") }

@Composable
internal fun NextButton(now: NowPlaying, player: BookPlayer, modifier: Modifier, hasNextChapter: Boolean = false) =
    TipIconButton("Next file", onClick = { player.next() }, enabled = now.index < now.count - 1 || hasNextChapter, modifier = modifier) {
        Icon(Icons.Rounded.SkipNext, contentDescription = "Next file")
    }

/** ✕ stops while paused. */
@Composable
private fun StopButton(player: BookPlayer, modifier: Modifier) =
    TipIconButton("Stop", onClick = { player.stop() }, modifier = modifier) { Icon(Icons.Rounded.Close, contentDescription = "Stop") }

/** Play / pause, with a ring while the file loads or waits for data. */
@Composable
internal fun PlayPauseButton(now: NowPlaying, player: BookPlayer, modifier: Modifier, big: Boolean = false) {
    val label = if (now.playing) "Pause" else "Play"
    TipIconButton(label, onClick = { player.togglePlay() }, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            if (now.buffering) CircularProgressIndicator(Modifier.size(if (big) 38.dp else 32.dp), strokeWidth = 2.dp)
            Icon(
                if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = label,
                modifier = Modifier.size(if (big) 32.dp else 24.dp),
            )
        }
    }
}

private fun speedLabel(s: Float): String = PlayerSpeed.label(s)

/** Playback speed; the book keeps it (saved with the position). */
@Composable
private fun SpeedButton(now: NowPlaying, player: BookPlayer) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).semantics { contentDescription = "Playback speed ${speedLabel(now.speed)}" }) {
            Text(speedLabel(now.speed), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SPEEDS.forEach { s ->
                val current = abs(s - now.speed) < 0.01f
                DropdownMenuItem(
                    text = { Text(speedLabel(s)) },
                    leadingIcon = { if (current) Icon(Icons.Rounded.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        player.setSpeed(s)
                    },
                )
            }
        }
    }
}

/** What the sleep timer waits for, for the button's label and description. */
internal fun sleepLabel(now: NowPlaying): String {
    val minutesLeft = if (now.sleepAtMs > 0) ((now.sleepAtMs - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(1) else 0
    return when {
        now.sleepFading -> "Sleep: fading out — shake or tap for 5 more minutes"
        now.sleepMode == SleepMode.END_OF_CHAPTER -> "Sleep at the end of this chapter"
        now.sleepMode == SleepMode.END_OF_FILE -> "Sleep at the end of this file"
        now.sleepMode == SleepMode.TIME -> "Sleep in $minutesLeft min"
        else -> "Sleep timer"
    }
}

/**
 * Sleep timer: pauses after 15 / 30 / 60 min or at the end of this file (the player screen has
 * every option); the minutes left show beside the moon. While the sound fades, a tap adds 5 min.
 */
@Composable
private fun SleepButton(now: NowPlaying, player: BookPlayer) {
    var open by remember { mutableStateOf(false) }
    val minutesLeft = if (now.sleepAtMs > 0) ((now.sleepAtMs - System.currentTimeMillis() + 59_999) / 60_000).coerceAtLeast(1) else 0
    val active = now.sleepMode != SleepMode.OFF
    val label = sleepLabel(now)
    Box {
        TextButton(
            onClick = { if (now.sleepFading) player.extendSleep() else open = true },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).semantics { contentDescription = label },
        ) {
            val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            when {
                now.sleepFading -> Text(" +${SleepTimer.EXTEND_MS / 60_000} min", style = MaterialTheme.typography.labelMedium, color = tint)
                now.sleepAtMs > 0 -> Text(" $minutesLeft min", style = MaterialTheme.typography.labelMedium, color = tint)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (active) {
                DropdownMenuItem(text = { Text("Off") }, onClick = { open = false; player.cancelSleep() })
            }
            SLEEP_MINUTES.forEach { m ->
                DropdownMenuItem(text = { Text("In $m min") }, onClick = { open = false; player.sleepIn(m) })
            }
            DropdownMenuItem(text = { Text("At the end of this file") }, onClick = { open = false; player.sleepAtEndOfFile() })
        }
    }
}
