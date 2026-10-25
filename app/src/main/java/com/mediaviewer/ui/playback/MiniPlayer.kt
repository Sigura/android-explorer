package com.mediaviewer.ui.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediaviewer.playback.BookPlayer
import com.mediaviewer.playback.NowPlaying
import com.mediaviewer.ui.common.TipIconButton
import com.mediaviewer.ui.common.formatDuration
import com.mediaviewer.ui.common.middleEllipsis
import kotlinx.coroutines.delay
import java.io.File

/** Space the mini player takes at the bottom: grids and lists pad by it, so their last row stays visible. */
val LocalMiniPlayerHeight = staticCompositionLocalOf { 0.dp }

val MINI_PLAYER_HEIGHT: Dp = 64.dp

/**
 * Mini player (todo §4.3): a bar at the bottom of the root and folder screens while a book is
 * loaded. File name, book and position, ⏮, −30 s, play / pause, +30 s, ⏭; ✕ stops while paused.
 */
@Composable
fun MiniPlayer(now: NowPlaying, player: BookPlayer, modifier: Modifier = Modifier) {
    // The position moves on its own while playing; the bar polls it twice a second, only then.
    LaunchedEffect(now.playing) {
        while (now.playing) {
            delay(500)
            player.refresh()
        }
    }
    val narrow = LocalConfiguration.current.screenWidthDp < 480
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            LinearProgressIndicator(
                progress = { if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(
                Modifier.height(MINI_PLAYER_HEIGHT - 2.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).widthIn(min = 0.dp)) {
                    Text(middleEllipsis(now.title, 48), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    val time = if (now.durationMs > 0) "${formatDuration(now.positionMs)} / ${formatDuration(now.durationMs)}" else formatDuration(now.positionMs)
                    Text(
                        "${File(now.folder).name} · ${now.index + 1} / ${now.count} · $time",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Phones: six buttons would leave the name no room; slightly smaller targets there.
                val b = Modifier.size(if (narrow) 40.dp else 48.dp)
                TipIconButton("Previous file", onClick = { player.previous() }, modifier = b) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous file") }
                TipIconButton("Back 30 seconds", onClick = { player.skipBack() }, modifier = b) { Icon(Icons.Rounded.Replay30, contentDescription = "Back 30 seconds") }
                TipIconButton(if (now.playing) "Pause" else "Play", onClick = { player.togglePlay() }, modifier = b) {
                    Icon(
                        if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (now.playing) "Pause" else "Play",
                    )
                }
                TipIconButton("Forward 30 seconds", onClick = { player.skipForward() }, modifier = b) { Icon(Icons.Rounded.Forward30, contentDescription = "Forward 30 seconds") }
                TipIconButton("Next file", onClick = { player.next() }, enabled = now.index < now.count - 1, modifier = b) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next file")
                }
                if (!now.playing) TipIconButton("Stop", onClick = { player.stop() }, modifier = b) { Icon(Icons.Rounded.Close, contentDescription = "Stop") }
            }
        }
    }
}
