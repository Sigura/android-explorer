package com.mediaviewer.ui.slideshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.common.formatDuration
import com.mediaviewer.ui.common.formatSize
import com.mediaviewer.ui.common.shareFiles
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Slideshow menu (README §6). The timer pauses while it is open; it closes after ~5 s without input
 * and after any button action except file info.
 */
@Composable
fun SlideshowMenu(c: SlideshowController, vm: AppViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsState()
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    var showInfo by remember { mutableStateOf(false) }
    val e = c.current

    LaunchedEffect(c.menuTouch, showInfo) {
        // Not while the file info is open: it is there to be read.
        if (showInfo) return@LaunchedEffect
        delay(5000)
        c.closeMenu()
    }
    LaunchedEffect(Unit) {
        try {
            first.requestFocus()
        } catch (ex: IllegalStateException) {
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .onGloballyPositioned { c.menuBounds = it.boundsInParent() }
            .pointerInput(Unit) {
                // Any touch on the menu counts as input and keeps it open.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        c.touchMenu()
                    }
                }
            },
    ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The full name, wrapped over as many lines as it needs; only absurd names are cut.
                val name = e?.name ?: ""
                Text(
                    if (name.length > MAX_NAME) name.take(MAX_NAME - 1) + "…" else name,
                    Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                val (pos, total) = c.progress
                if (total > 0) {
                    Text("%,d / %,d".format(pos, total), style = MaterialTheme.typography.labelMedium)
                }
            }

            // 1. Interval R, 1–40 s, applied at once and saved.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Interval", Modifier.width(72.dp))
                Slider(
                    value = settings.intervalSec.toFloat(),
                    onValueChange = { v ->
                        c.touchMenu()
                        vm.settingsStore.update { it.copy(intervalSec = v.toInt().coerceIn(1, 40)) }
                    },
                    valueRange = 1f..40f,
                    steps = 38,
                    modifier = Modifier.weight(1f).focusRequester(first),
                )
                Text("${settings.intervalSec} s", Modifier.width(48.dp).padding(start = 8.dp))
            }

            // 2. Random order.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Text("Random order", Modifier.weight(1f).padding(start = 12.dp))
                Switch(checked = settings.random, onCheckedChange = { c.touchMenu(); c.setRandom(it) })
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MenuAction(c, if (c.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (c.paused) "Play" else "Pause") {
                    c.paused = !c.paused
                }
                MenuAction(
                    c,
                    if (settings.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    if (settings.muted) "Unmute" else "Mute",
                ) { vm.settingsStore.update { it.copy(muted = !it.muted) } }
                MenuButton(Icons.Rounded.Info, "File info") { showInfo = !showInfo }
                // Device files aren't on this phone to share (as in the folder view).
                if (!c.onDevice) MenuAction(c, Icons.Rounded.Share, "Share") { e?.let { shareFiles(ctx, listOf(it)) } }
                // 3. Delete without confirmation (undo snackbar).
                MenuAction(c, Icons.Rounded.Delete, "Delete") { c.deleteCurrent() }
                Spacer(Modifier.width(8.dp))
                // 4. Back to folder view.
                MenuAction(c, Icons.AutoMirrored.Rounded.ArrowBack, "Back to folder") { c.close() }
            }

            if (showInfo && e != null) {
                val m = c.meta
                val lines = buildList {
                    add(e.path)
                    if (m != null && m.width > 0) add("${m.width} × ${m.height}")
                    if (e.size > 0) add(formatSize(e.size))
                    if (e.mtime > 0) add(DateFormat.getDateTimeInstance().format(Date(e.mtime)))
                    if (m != null && m.durationMs > 0) add("Duration ${formatDuration(m.durationMs)}")
                    if (m != null && m.frameCount > 1) add("${m.frameCount} frames")
                }
                Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val MAX_NAME = 256

@Composable
private fun MenuButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
}

/** A menu button whose action is complete once done, so the menu closes after it. */
@Composable
private fun MenuAction(c: SlideshowController, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    MenuButton(icon, label) {
        c.closeMenu()
        onClick()
    }
}
