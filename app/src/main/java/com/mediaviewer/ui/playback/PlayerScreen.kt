package com.mediaviewer.ui.playback

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.mediaviewer.data.history.FileHistory
import com.mediaviewer.data.tags.FileInfo
import com.mediaviewer.data.tags.TrackExtras
import com.mediaviewer.domain.BookTimes
import com.mediaviewer.domain.Chapter
import com.mediaviewer.domain.Chapters
import com.mediaviewer.domain.Lyrics
import com.mediaviewer.domain.PlayerSpeed
import com.mediaviewer.domain.SleepTimer
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import com.mediaviewer.playback.BookPlayer
import com.mediaviewer.playback.NowPlaying
import com.mediaviewer.playback.SleepMode
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.common.TipIconButton
import com.mediaviewer.ui.common.formatDuration
import com.mediaviewer.ui.common.middleEllipsis
import com.mediaviewer.ui.common.shareFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** What the lower part of the player screen lists. */
private enum class Pane { FILES, CHAPTERS, LYRICS }

/**
 * The player screen (todo §4.1, §4.2): an overlay over the root or folder screen, so Back returns
 * where it was. Cover, file name and tag title, where we are in the book, synced lyrics, a seek bar
 * with chapter ticks, the book bar, the transport buttons, speed, sleep timer and shuffle; below,
 * the queue, the chapters or the lyrics. Two panes on wide windows (landscape tablets, DeX).
 */
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val player = vm.bookPlayer
    val nowOrNull by player.state.collectAsState()
    val now = nowOrNull
    // Stopped (✕, the end of the book, the service gone): nothing left to show.
    LaunchedEffect(now == null) { if (now == null) vm.showOverlay(null) }
    if (now == null) return
    // The position moves on its own while playing; polled twice a second, only then.
    LaunchedEffect(now.playing) {
        while (now.playing) {
            delay(500)
            player.refresh()
        }
    }
    val times by player.times.collectAsState()
    val extrasNow by player.extras.collectAsState()
    val extras = extrasNow?.takeIf { it.path == now.path }
    var filesVersion by remember { mutableIntStateOf(0) }
    val files by produceState(emptyMap<String, FileHistory>(), now.folder, now.index, now.playing, filesVersion) {
        value = player.fileHistory(now.folder)
    }
    var pane by remember { mutableStateOf(Pane.FILES) }
    var listExpanded by remember { mutableStateOf(false) }
    var speedDialog by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    val coverFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        // Keys must reach this screen, not the folder grid under it. Keyboards and TV remotes
        // start on the cover (←/→ skip, Enter plays); touch gets the screen itself, which shows nothing.
        runCatching { if (inputMode.inputMode == InputMode.Keyboard) coverFocus.requestFocus() else rootFocus.requestFocus() }
    }
    val openPane: (Pane) -> Unit = { p ->
        pane = p
        listExpanded = true
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev -> ev.type == KeyEventType.KeyDown && playerKey(ev, now, player, extras, onClose = { vm.showOverlay(null) }) }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The cover, blurred, behind everything (blur needs Android 12; older ones get a dim copy).
            extras?.cover?.let { bmp ->
                val image = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier)
                        .alpha(if (Build.VERSION.SDK_INT >= 31) 0.35f else 0.12f),
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val wide = maxWidth >= 720.dp && maxWidth > maxHeight
                val coverMax = if (wide) minOf(maxHeight * 0.45f, 360.dp) else minOf(maxHeight * 0.34f, maxWidth - 48.dp, 420.dp)
                Column(Modifier.fillMaxSize()) {
                    PlayerTopBar(now, player, onBack = { vm.showOverlay(null) }, onOpenFolder = {
                        vm.showOverlay(null)
                        vm.openFolder(now.folder, focus = now.path)
                    })
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Controls(now, player, times, extras, coverMax, coverFocus, openPane, { speedDialog = true }, { sleepDialog = true })
                            }
                            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 8.dp)) {
                                ListPane(now, player, times, extras, files, { filesVersion++ }, pane, { pane = it }, header = null)
                            }
                        }
                    } else {
                        if (!listExpanded) {
                            // The controls take what they need (scrolling on short screens); the list the rest, at least a quarter.
                            Column(
                                Modifier.weight(3f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Controls(now, player, times, extras, coverMax, coverFocus, openPane, { speedDialog = true }, { sleepDialog = true })
                            }
                        } else {
                            CollapsedControls(now, player)
                        }
                        Column(Modifier.weight(1f)) {
                            ListPane(
                                now, player, times, extras, files, { filesVersion++ }, pane, { pane = it },
                                header = { SheetHandle(listExpanded, onToggle = { listExpanded = it }) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (speedDialog) {
        val skipSilence = vm.settings.collectAsState().value.skipSilence
        SpeedDialog(
            now.speed, onSpeed = { player.setSpeed(it) },
            skipSilence = skipSilence, onSkipSilence = { on -> vm.settingsStore.update { it.copy(skipSilence = on) } },
            onDismiss = { speedDialog = false },
        )
    }
    if (sleepDialog) SleepDialog(now, hasChapters = extras?.chapters?.isNotEmpty() == true, player, onDismiss = { sleepDialog = false })
}

/** The keyboard (todo §4.2). Returns true when the key was used. */
private fun playerKey(ev: androidx.compose.ui.input.key.KeyEvent, now: NowPlaying, player: BookPlayer, extras: TrackExtras?, onClose: () -> Unit): Boolean {
    if (ev.isCtrlPressed) return false
    return when (ev.key) {
        Key.Spacebar, Key.K, Key.MediaPlayPause -> player.togglePlay().let { true }
        Key.J -> player.skipBack().let { true }
        Key.L -> player.skipForward().let { true }
        Key.DirectionLeft -> if (ev.isShiftPressed) player.previous().let { true } else false
        Key.DirectionRight -> if (ev.isShiftPressed) player.next().let { true } else false
        Key.P, Key.MediaPrevious -> player.previous().let { true }
        Key.N, Key.MediaNext -> player.next().let { true }
        Key.LeftBracket -> player.setSpeed(PlayerSpeed.step(now.speed, -1)).let { true }
        Key.RightBracket -> player.setSpeed(PlayerSpeed.step(now.speed, +1)).let { true }
        Key.MoveHome -> player.seekTo(0).let { true }
        Key.MoveEnd -> {
            // The end of the chapter, or of the file (which moves on to the next one).
            val ch = extras?.chapters.orEmpty()
            val end = if (ch.isNotEmpty()) Chapters.endAt(ch, now.positionMs, now.durationMs) else now.durationMs
            if (end > 0) player.seekTo((end - 1_000).coerceAtLeast(0))
            true
        }
        Key.Escape -> onClose().let { true }
        else -> false
    }
}

@Composable
private fun PlayerTopBar(now: NowPlaying, player: BookPlayer, onBack: () -> Unit, onOpenFolder: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TipIconButton("Back (Esc)", onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(
            middleEllipsis(File(now.folder).name, 60),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Open the folder", onClick = onOpenFolder)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Box {
            TipIconButton("More", onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open the folder") }, leadingIcon = { Icon(Icons.Rounded.Folder, null) }, onClick = { menu = false; onOpenFolder() })
                DropdownMenuItem(text = { Text("File info") }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { menu = false; info = true })
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                    onClick = {
                        menu = false
                        val f = File(now.path)
                        shareFiles(context, listOf(FileEntry(f.path, f.name, MediaType.OTHER, 0, 0, OtherKind.AUDIO)))
                    },
                )
                DropdownMenuItem(text = { Text("Start over") }, onClick = { menu = false; player.startOver() })
                DropdownMenuItem(text = { Text("Mark as finished") }, onClick = { menu = false; player.markFinished() })
                DropdownMenuItem(text = { Text("Stop") }, onClick = { menu = false; player.stop() })
            }
        }
    }
    if (info) FileInfoDialog(now.path, onDismiss = { info = false })
}

/** ⋮ *File info* (todo §4.6): read on IO; lines appear when they are known. */
@Composable
private fun FileInfoDialog(path: String, onDismiss: () -> Unit) {
    val lines by produceState<List<Pair<String, String>>?>(null, path) { value = FileInfo.read(path) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("File info") },
        text = {
            val l = lines
            if (l == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    l.forEach { (label, value) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(label, Modifier.width(112.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        },
    )
}

/** Cover, names, lyrics, seek bar, book bar and buttons: the upper half, or the left pane. */
@Composable
private fun Controls(
    now: NowPlaying,
    player: BookPlayer,
    times: BookTimes?,
    extras: TrackExtras?,
    coverMax: androidx.compose.ui.unit.Dp,
    coverFocus: FocusRequester,
    openPane: (Pane) -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
) {
    val chapters = extras?.chapters.orEmpty()
    Spacer(Modifier.height(8.dp))
    Cover(now, player, extras, Modifier.size(coverMax), coverFocus)
    Spacer(Modifier.height(12.dp))
    val name = File(now.path).name
    Text(
        middleEllipsis(name, 90),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.widthIn(max = 560.dp),
    )
    extras?.tagTitle?.let {
        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        positionLine(now, times),
        maxLines = 1,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val ci = Chapters.indexAt(chapters, now.positionMs)
    if (ci >= 0) {
        Text(
            "Chapter ${ci + 1} / ${chapters.size}" + chapters[ci].title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openPane(Pane.CHAPTERS) }.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    if (now.cc) extras?.lyrics?.let { LyricsPreview(it, now.positionMs, onOpen = { openPane(Pane.LYRICS) }) }
    Spacer(Modifier.height(8.dp))
    SeekRow(now, player, Modifier.fillMaxWidth().widthIn(max = 640.dp).height(40.dp), marks = chapters.map { it.startMs }, bubble = true)
    if (!now.music && now.count >= 2) {
        BookBar(now, times, onPick = { player.playFile(it) }, Modifier.fillMaxWidth().widthIn(max = 600.dp).height(16.dp).padding(horizontal = 16.dp, vertical = 4.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val b = Modifier.size(56.dp)
        PreviousButton(player, b)
        SkipBackButton(now, player, b, iconSize = 30.dp)
        PlayPauseButton(now, player, Modifier.size(72.dp), big = true)
        SkipForwardButton(now, player, b, iconSize = 30.dp)
        NextButton(now, player, b, hasNextChapter = chapters.isNotEmpty() && Chapters.nextStart(chapters, now.positionMs) != null)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSpeed, modifier = Modifier.semantics { contentDescription = "Playback speed ${PlayerSpeed.label(now.speed)}" }) {
            Text(PlayerSpeed.label(now.speed), style = MaterialTheme.typography.labelLarge)
        }
        SleepChip(now, player, onSleep)
        IconToggleButton(
            checked = now.music,
            onCheckedChange = { player.setShuffle(it) },
            modifier = Modifier.semantics { contentDescription = if (now.music) "Shuffle on (music mode)" else "Shuffle off" },
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = if (now.music) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Repeat exists only in music mode: a book stops after its last file.
        if (now.music) {
            val (icon, label) = when (now.repeat) {
                Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat to "Repeat all"
                Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne to "Repeat this file"
                else -> Icons.Rounded.Repeat to "Repeat off"
            }
            TipIconButton(label, onClick = { player.cycleRepeat() }) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (now.repeat == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (chapters.isNotEmpty()) {
            TipIconButton("Chapters", onClick = { openPane(Pane.CHAPTERS) }) { Icon(Icons.AutoMirrored.Rounded.Toc, contentDescription = "Chapters") }
        }
        // CC: the synced lines under the title, on or off for this folder; all lines stay in the list's tab.
        if (extras?.lyrics?.synced == true) {
            val label = if (now.cc) "Hide lyrics and subtitles" else "Show lyrics and subtitles"
            TipIconButton(label, onClick = { player.setCc(!now.cc) }) {
                Icon(
                    if (now.cc) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled,
                    contentDescription = label,
                    tint = if (now.cc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (extras?.lyrics != null) {
            TipIconButton("Lyrics", onClick = { openPane(Pane.LYRICS) }) { Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = "Lyrics") }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** The list expanded: one row with the title and play / pause, so the sound stays at hand. */
@Composable
private fun CollapsedControls(now: NowPlaying, player: BookPlayer) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(middleEllipsis(File(now.path).name, 60), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text("${formatDuration(now.positionMs)} / ${formatDuration(now.durationMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SkipBackButton(now, player, Modifier.size(48.dp))
        PlayPauseButton(now, player, Modifier.size(48.dp))
        SkipForwardButton(now, player, Modifier.size(48.dp))
    }
}

/** `Track 7 / 42 · 3 h 12 min left` (book), `Track 7 / 42 · shuffled` (music). */
private fun positionLine(now: NowPlaying, times: BookTimes?): String {
    val track = "Track ${now.index + 1} / ${now.count}"
    if (now.music) return "$track · shuffled"
    if (times == null || now.count < 2) {
        return if (now.durationMs > 0) "$track · ${humanTime(((now.durationMs - now.positionMs) / now.speed).toLong())} left" else track
    }
    val p = times.progress(now.index, now.positionMs, now.durationMs)
    return "$track · ${if (p.exact) "" else "≈ "}${humanTime(p.leftMs(now.speed))} left"
}

/** `3 h 12 min`, `12 min`, `45 s`. */
internal fun humanTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
        s >= 60 -> "${s / 60} min"
        else -> "$s s"
    }
}

/**
 * The cover: the file's picture, the folder's cover image, or the folder's initial on a colour
 * from its name. Tap = play / pause, double tap on a half = skip back / forward, a horizontal
 * swipe = previous / next file. Focusable: ←/→ skip, `Shift` + ←/→ change file, Enter plays.
 */
@Composable
private fun Cover(now: NowPlaying, player: BookPlayer, extras: TrackExtras?, modifier: Modifier, focus: FocusRequester) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val p by rememberUpdatedState(player)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
            .focusRequester(focus)
            .focusable(interactionSource = interaction)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                // A held arrow (TV remote) changes the file once: the first repeat; later repeats do nothing.
                val repeat = ev.nativeKeyEvent.repeatCount
                when (ev.key) {
                    Key.DirectionLeft -> {
                        if (repeat == 0) p.skipBack() else if (repeat == 1) p.previous()
                        true
                    }
                    Key.DirectionRight -> {
                        if (repeat == 0) p.skipForward() else if (repeat == 1) p.next()
                        true
                    }
                    Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                        if (repeat == 0) p.togglePlay()
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { p.togglePlay() },
                    onDoubleTap = { o -> if (o.x < size.width / 2) p.skipBack() else p.skipForward() },
                )
            }
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        val t = 64.dp.toPx()
                        if (dx > t) p.previous() else if (dx < -t) p.next()
                    },
                ) { change, amount ->
                    change.consume()
                    dx += amount
                }
            }
            .semantics { contentDescription = "Cover. Tap to play or pause; double tap a side to skip; swipe to change the file" },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = extras?.cover
        if (bmp != null) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val name = File(now.folder).name.ifEmpty { File(now.path).name }
            val hue = ((name.hashCode() % 360) + 360) % 360
            Box(Modifier.fillMaxSize().background(Color.hsl(hue.toFloat(), 0.45f, 0.32f)), contentAlignment = Alignment.Center) {
                Text(
                    name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
        if (now.buffering) LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

/** Synced lyrics: the line before, the line playing (highlighted), the line after. A tap opens them all. */
@Composable
private fun LyricsPreview(lyrics: Lyrics, positionMs: Long, onOpen: () -> Unit) {
    if (!lyrics.synced) return
    val i = lyrics.indexAt(positionMs)
    val at = if (i >= 0) i else lyrics.lines.indexOfFirst { it.startMs > positionMs }.let { if (it < 0) lyrics.lines.size else it } - 1
    Column(
        Modifier
            .padding(top = 8.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Show all lines", onClick = onOpen)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (k in at - 1..at + 1) {
            val line = lyrics.lines.getOrNull(k)?.text ?: ""
            val current = k == i
            Text(
                line,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = if (current) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.bodyMedium,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun SleepChip(now: NowPlaying, player: BookPlayer, onOpen: () -> Unit) {
    val active = now.sleepMode != SleepMode.OFF
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(
        onClick = { if (now.sleepFading) player.extendSleep() else onOpen() },
        modifier = Modifier.semantics { contentDescription = sleepLabel(now) },
    ) {
        Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        val text = when {
            now.sleepFading -> "+${SleepTimer.EXTEND_MS / 60_000} min"
            now.sleepMode == SleepMode.TIME -> formatDuration((now.sleepAtMs - System.currentTimeMillis()).coerceAtLeast(0))
            now.sleepMode == SleepMode.END_OF_FILE -> "file end"
            now.sleepMode == SleepMode.END_OF_CHAPTER -> "chapter end"
            else -> ""
        }
        if (text.isNotEmpty()) Text(" $text", style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** The handle above the list: tap or drag up for the full list, down for the controls again. */
@Composable
private fun SheetHandle(expanded: Boolean, onToggle: (Boolean) -> Unit) {
    val toggle by rememberUpdatedState(onToggle)
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(onClickLabel = if (expanded) "Show the controls" else "Show the whole list") { toggle(!expanded) }
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onDragEnd = { if (dy < -24.dp.toPx()) toggle(true) else if (dy > 24.dp.toPx()) toggle(false) },
                ) { change, amount ->
                    change.consume()
                    dy += amount
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
            Icon(
                if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        }
    }
}

/** Files, chapters or lyrics, with tabs for the ones this file has. */
@Composable
private fun ListPane(
    now: NowPlaying,
    player: BookPlayer,
    times: BookTimes?,
    extras: TrackExtras?,
    files: Map<String, FileHistory>,
    onFilesChanged: () -> Unit,
    pane: Pane,
    onPane: (Pane) -> Unit,
    header: (@Composable () -> Unit)?,
) {
    val panes = buildList {
        add(Pane.FILES)
        if (extras?.chapters?.isNotEmpty() == true) add(Pane.CHAPTERS)
        if (extras?.lyrics != null) add(Pane.LYRICS)
    }
    val shown = if (pane in panes) pane else Pane.FILES
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            header?.invoke()
            if (panes.size > 1) {
                TabRow(selectedTabIndex = panes.indexOf(shown), containerColor = Color.Transparent) {
                    panes.forEach { p ->
                        Tab(
                            selected = p == shown,
                            onClick = { onPane(p) },
                            text = {
                                Text(
                                    when (p) {
                                        Pane.FILES -> "Files"
                                        Pane.CHAPTERS -> "Chapters"
                                        Pane.LYRICS -> if (extras?.lyrics?.lines?.any { it.endMs >= 0 } == true) "Subtitles" else "Lyrics"
                                    },
                                )
                            },
                        )
                    }
                }
            } else {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text("  ${now.count} files", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (shown) {
                Pane.FILES -> FileList(now, player, times, files, onFilesChanged)
                Pane.CHAPTERS -> ChapterList(now, player, extras?.chapters.orEmpty())
                Pane.LYRICS -> extras?.lyrics?.let { LyricsList(it, now.positionMs, player) }
            }
        }
    }
}

/** Keeps the playing row in view, unless the user is scrolling the list. */
@Composable
private fun FollowCurrent(state: LazyListState, index: Int) {
    LaunchedEffect(index) {
        if (index < 0 || state.isScrollInProgress) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == index } || visible.lastOrNull()?.index == index) {
            state.animateScrollToItem((index - 2).coerceAtLeast(0))
        }
    }
}

/**
 * The queue: ✓ heard to the end, ◐ partly heard (with a small bar), the file playing marked.
 * A tap plays a file (at its saved place in book mode); a long press marks it heard or not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(now: NowPlaying, player: BookPlayer, times: BookTimes?, files: Map<String, FileHistory>, onChanged: () -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (now.index - 2).coerceAtLeast(0))
    FollowCurrent(state, now.index)
    val scope = rememberCoroutineScope()
    val paths = times?.paths.orEmpty()
    var menuFor by remember { mutableStateOf<String?>(null) }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(paths, key = { _, p -> p }) { i, path ->
            val h = files[path]
            val current = i == now.index
            val duration = if (current && now.durationMs > 0) now.durationMs else times?.durationsMs?.getOrNull(i)
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .combinedClickable(
                            onClickLabel = "Play",
                            onLongClickLabel = "Mark as heard or not heard",
                            onClick = { player.playFile(i, if (now.music || h == null || h.finished) 0 else h.positionMs) },
                            onLongClick = { menuFor = path },
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when {
                            current -> Icon(Icons.Rounded.Equalizer, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            h?.finished == true -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Heard", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            h != null && h.positionMs > 0 -> Text("◐", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            middleEllipsis(File(path).name, 70),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (!current && h != null && !h.finished && h.positionMs > 0 && duration != null && duration > 0) {
                            LinearProgressIndicator(
                                progress = { (h.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                                modifier = Modifier.padding(top = 4.dp).width(96.dp).height(2.dp),
                                drawStopIndicator = {},
                            )
                        }
                    }
                    Text(duration?.let(::formatDuration) ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuFor == path, onDismissRequest = { menuFor = null }) {
                    val heard = h?.finished == true
                    DropdownMenuItem(
                        text = { Text(if (heard) "Mark as not heard" else "Mark as heard") },
                        leadingIcon = { Icon(Icons.Rounded.Check, contentDescription = null) },
                        onClick = {
                            menuFor = null
                            scope.launch {
                                player.setFileFinished(path, !heard)
                                onChanged()
                            }
                        },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun ChapterList(now: NowPlaying, player: BookPlayer, chapters: List<Chapter>) {
    val current = Chapters.indexAt(chapters, now.positionMs)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    FollowCurrent(state, current)
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(chapters) { i, c ->
            val on = i == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(onClickLabel = "Play from here") { player.seekTo(c.startMs) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    c.title.ifBlank { "Chapter ${i + 1}" },
                    Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(formatDuration(c.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** All lines; synced ones follow the playback, and a tap on a line plays from it. */
@Composable
private fun LyricsList(lyrics: Lyrics, positionMs: Long, player: BookPlayer) {
    val current = lyrics.indexAt(positionMs)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    LaunchedEffect(current) {
        if (current >= 0 && !state.isScrollInProgress) state.animateScrollToItem((current - 3).coerceAtLeast(0))
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val on = i == current
            Text(
                line.text.ifEmpty { " " },
                style = if (on) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = if (lyrics.synced) 0.75f else 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (lyrics.synced) Modifier.clickable(onClickLabel = "Play from this line") { player.seekTo(line.startMs) } else Modifier)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/** Speed (todo §4.2): presets, ±0.05 steps and a slider over 0.5–3×. Kept per book. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedDialog(speed: Float, onSpeed: (Float) -> Unit, skipSilence: Boolean, onSkipSilence: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Speed") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    TipIconButton("Slower ([)", onClick = { onSpeed(PlayerSpeed.step(speed, -1)) }) { Icon(Icons.Rounded.Remove, contentDescription = "Slower") }
                    Text(PlayerSpeed.label(speed), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(96.dp), textAlign = TextAlign.Center)
                    TipIconButton("Faster (])", onClick = { onSpeed(PlayerSpeed.step(speed, +1)) }) { Icon(Icons.Rounded.Add, contentDescription = "Faster") }
                }
                Slider(
                    value = speed,
                    onValueChange = { onSpeed(PlayerSpeed.snap(it)) },
                    valueRange = PlayerSpeed.MIN..PlayerSpeed.MAX,
                    steps = ((PlayerSpeed.MAX - PlayerSpeed.MIN) / PlayerSpeed.STEP).toInt() - 1,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayerSpeed.PRESETS.forEach { p ->
                        FilterChip(selected = PlayerSpeed.isSame(p, speed), onClick = { onSpeed(p) }, label = { Text(PlayerSpeed.label(p)) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSkipSilence(!skipSilence) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Skip silence")
                        Text("For every book and album", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = skipSilence, onCheckedChange = onSkipSilence)
                }
            }
        },
    )
}

/** Sleep timer (todo §4.2): preset or typed minutes, the end of this file or chapter, off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepDialog(now: NowPlaying, hasChapters: Boolean, player: BookPlayer, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    val minutes = custom.toIntOrNull()?.takeIf { it in 1..SleepTimer.MAX_MINUTES }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = minutes != null, onClick = { minutes?.let(player::sleepIn); onDismiss() }) { Text("Set") }
        },
        dismissButton = {
            if (now.sleepMode != SleepMode.OFF) TextButton(onClick = { player.cancelSleep(); onDismiss() }) { Text("Turn off") }
        },
        title = { Text("Sleep timer") },
        text = {
            Column {
                Text(
                    sleepLabel(now) + ". The sound fades out over the last ${SleepTimer.FADE_MS / 1000} s; shake the phone then for ${SleepTimer.EXTEND_MS / 60_000} more minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SleepTimer.PRESET_MINUTES.forEach { m ->
                        FilterChip(selected = false, onClick = { player.sleepIn(m); onDismiss() }, label = { Text("$m min") })
                    }
                    FilterChip(selected = now.sleepMode == SleepMode.END_OF_FILE, onClick = { player.sleepAtEndOfFile(); onDismiss() }, label = { Text("End of file") })
                    if (hasChapters) {
                        FilterChip(selected = now.sleepMode == SleepMode.END_OF_CHAPTER, onClick = { player.sleepAtEndOfChapter(); onDismiss() }, label = { Text("End of chapter") })
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { v -> custom = v.filter(Char::isDigit).take(3) },
                    label = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                )
            }
        },
    )
}
