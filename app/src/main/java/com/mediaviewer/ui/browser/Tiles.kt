package com.mediaviewer.ui.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassDisabled
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.thumbs.Still
import com.mediaviewer.data.thumbs.Thumb
import com.mediaviewer.data.thumbs.Thumbnails
import com.mediaviewer.domain.CropMode
import com.mediaviewer.domain.SmartCrop
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import com.mediaviewer.ui.common.copyFiles
import com.mediaviewer.ui.common.formatDuration
import com.mediaviewer.ui.common.formatSize
import com.mediaviewer.ui.common.middleEllipsis
import com.mediaviewer.ui.common.startFileDrag
import kotlinx.coroutines.delay
import kotlin.math.min

/** Tile settings that every tile reads. */
data class TileConfig(
    val sizePx: IntSize,
    val cropMode: CropMode,
    val cropThresholdPct: Int,
    val isTv: Boolean,
    /** Always show names on media tiles (otherwise only on focus, hover and in selection mode). */
    val showNames: Boolean = false,
    /** Mouse clicks follow desktop rules (README §2, "Desktops"). */
    val desktopClicks: Boolean = false,
    /** The folder is still being listed: GIF and video tiles show their first frame only, without scrubbing or previews. */
    val firstFrameOnly: Boolean = false,
    /** Videos longer than this open in an external player (README §3.11); 0 = off or no player installed. */
    val longVideoMs: Long = 0,
)

private const val RESIZE_SETTLE_MS = 300L

val LocalThumbnails = staticCompositionLocalOf<Thumbnails> { error("no Thumbnails") }
val LocalTileConfig = staticCompositionLocalOf { TileConfig(IntSize(320, 240), CropMode.SMART, 10, false) }

sealed interface TileState {
    data object Placeholder : TileState
    data object Loading : TileState
    data class Ready(val thumb: Thumb) : TileState
    data class Failed(val timedOut: Boolean) : TileState
}

/**
 * Per-tile thumbnail state (README §2, rule 3): `Placeholder → Loading → Ready | Failed`. Work
 * starts only when the grid is idle and is cancelled when the tile leaves the screen.
 */
@Composable
fun rememberThumb(entry: FileEntry, gate: ScrollGate, full: Boolean = false, firstOnly: Boolean = false): TileState {
    val thumbs = LocalThumbnails.current
    val size = LocalTileConfig.current.sizePx
    var state by remember(entry.path) {
        mutableStateOf(
            thumbs.cached(entry.path)?.let { TileState.Ready(it) }
                ?: thumbs.failure(entry.path)?.let { TileState.Failed(it) }
                ?: TileState.Placeholder,
        )
    }
    LaunchedEffect(entry.path, entry.mtime, size, full, firstOnly) {
        val hit = thumbs.usable(entry, size.width, full, firstOnly)
        if (hit != null) {
            state = TileState.Ready(hit)
            return@LaunchedEffect
        }
        // A smaller thumbnail stays on screen; while a window is being resized the size changes
        // every frame, so the sharper decode starts only once it has settled (README §3.9).
        if (state is TileState.Ready) delay(RESIZE_SETTLE_MS)
        // A broken file keeps its icon; a timed-out one is tried again whenever thumbnail work is idle.
        while (true) {
            thumbs.failure(entry.path)?.let { timedOut ->
                if (state !is TileState.Ready) state = TileState.Failed(timedOut)
                if (!timedOut) return@LaunchedEffect
                thumbs.awaitRetry(entry.path)
            }
            gate.awaitIdle()
            if (state !is TileState.Ready) state = TileState.Loading
            try {
                thumbs.load(entry, size.width, size.height, full, firstOnly).collect { state = TileState.Ready(it) }
                return@LaunchedEffect
            } catch (e: Thumbnails.DecodeFailed) {
                if (state !is TileState.Ready) state = TileState.Failed(e.timedOut)
                if (!e.timedOut) return@LaunchedEffect
            }
        }
    }
    return state
}

/**
 * Common tile shell: focus visuals (scale + outline), gestures, keys and the selection mark.
 */
@Composable
fun TileFrame(
    entry: FileEntry?,
    browser: BrowserState,
    input: TileInput,
    scrubbable: Boolean,
    requestFocus: Boolean,
    modifier: Modifier = Modifier,
    /** A horizontal swipe scrubs; off for folders, which cycle their previews on their own. */
    swipeScrub: Boolean = scrubbable,
    content: @Composable BoxScope.() -> Unit,
) {
    val selected = entry != null && entry.path in browser.selection
    val selectionMode = browser.selectionMode
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isTv = LocalTileConfig.current.isTv
    val desktop = LocalTileConfig.current.desktopClicks
    val view = LocalView.current
    val density = LocalDensity.current
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var okLongHandled by remember { mutableStateOf(false) }
    var pendingMove by remember { mutableIntStateOf(0) }
    val currentEntry by rememberUpdatedState(entry)

    fun tap() {
        val e = currentEntry
        if (e == null) browser.goUp() else if (browser.selectionMode) browser.toggle(e) else browser.open(e)
    }

    fun select() {
        currentEntry?.let { browser.select(it) }
    }

    fun focusSelf() {
        try {
            focusRequester.requestFocus()
        } catch (e: IllegalStateException) {
        }
    }

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            try {
                focusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // Not laid out yet.
            }
            browser.focusConsumed()
        }
    }

    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .onGloballyPositioned { coords = it }
            .graphicsLayer {
                val s = if (input.focused) 1.05f else 1f
                scaleX = s
                scaleY = s
            }
            .then(if (input.focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerHoverIcon(PointerIcon.Hand)
            .tileHover(input)
            .tileGestures(
                scrubbable = swipeScrub,
                input = input,
                coords = { coords },
                isSelectionMode = { browser.selectionMode },
                onTap = ::tap,
                onSelect = ::select,
                onDragSelect = browser::dragSelectTo,
                onDragSelectEnd = browser::dragSelectEnd,
                desktop = { desktop },
                onDesktopClick = { ctrl, shift ->
                    focusSelf()
                    currentEntry?.let { browser.desktopClick(it, ctrl, shift) }
                },
                onContextMenu = { p ->
                    focusSelf()
                    if (currentEntry != null) menuAt = p
                },
                onDragOut = { currentEntry?.let { startFileDrag(view, browser.targets(it)) } },
            )
            .onFocusChanged {
                input.focused = it.isFocused
                if (it.isFocused) {
                    browser.focused = currentEntry
                } else {
                    input.keyStep = null
                    if (browser.focused?.path == currentEntry?.path) browser.focused = null
                }
            }
            .onKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                val repeat = ev.nativeKeyEvent.repeatCount
                when (ev.key) {
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (down) {
                            if (repeat == 1 && entry != null) {
                                okLongHandled = true
                                select()
                            }
                        } else if (ev.type == KeyEventType.KeyUp) {
                            val e = currentEntry
                            // Shift + Enter: play in the slideshow, even a long video (README §3.11).
                            if (!okLongHandled) {
                                if (ev.isShiftPressed && e != null && !browser.selectionMode) browser.openInApp(e) else tap()
                            }
                            okLongHandled = false
                        }
                        true
                    }
                    Key.Spacebar -> {
                        if (ev.type == KeyEventType.KeyUp && entry != null) select()
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val dir = if (ev.key == Key.DirectionLeft) -1 else 1
                        when {
                            !scrubbable -> false
                            ev.isShiftPressed -> {
                                if (down) input.keyStep = (input.keyStep ?: 0) + dir
                                true
                            }
                            // TV: long-press ←/→ scrubs, a short press moves focus on release.
                            isTv && down && repeat == 0 -> {
                                pendingMove = dir
                                true
                            }
                            isTv && down -> {
                                pendingMove = 0
                                input.keyStep = (input.keyStep ?: 0) + dir
                                true
                            }
                            isTv && ev.type == KeyEventType.KeyUp -> {
                                val move = pendingMove
                                pendingMove = 0
                                input.keyStep = null
                                if (move != 0) focusManager.moveFocus(if (move < 0) FocusDirection.Left else FocusDirection.Right)
                                true
                            }
                            else -> false
                        }
                    }
                    Key.ShiftLeft, Key.ShiftRight -> {
                        if (ev.type == KeyEventType.KeyUp) input.keyStep = null
                        false
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        content()
        val menuEntry = entry
        if (menuAt != null && menuEntry != null) {
            val at = menuAt ?: Offset.Zero
            val h = coords?.size?.height ?: 0
            TileMenu(
                menuEntry, browser,
                offset = with(density) { DpOffset(at.x.toDp(), (at.y - h).toDp()) },
                onOpen = ::tap,
                onDismiss = { menuAt = null },
            )
        }
        if (selectionMode && entry != null) {
            if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            )
        }
    }
}

/** Right-click menu with desktop clicks (README §2, "Desktops"); actions apply to the selection if the tile is in it. */
@Composable
private fun TileMenu(entry: FileEntry, browser: BrowserState, offset: DpOffset, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val targets = browser.targets(entry)
    val files = targets.count { !it.isFolder }
    val many = targets.size > 1
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, offset = offset) {
        @Composable
        fun Item(label: String, enabled: Boolean = true, action: () -> Unit) =
            DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { onDismiss(); action() })
        if (!many) Item("Open") { if (browser.selectionMode) browser.open(entry) else onOpen() }
        // Other apps open only from the plain folder view (README §3.11).
        if (!many && !browser.selectionMode) {
            if (entry.type == MediaType.VIDEO && LocalTileConfig.current.longVideoMs > 0) {
                Item("Play in slideshow") { browser.openInApp(entry) }
            }
            if (browser.offersEdit(entry)) Item("Edit") { browser.edit(entry) }
            if (!entry.isFolder && entry.type != MediaType.PARTIAL) Item("Open with…") { browser.openWith(entry) }
        }
        Item(if (entry.path in browser.selection) "Deselect" else "Select") { browser.select(entry) }
        Item(if (many) "Share ${targets.size} items" else "Share", enabled = files > 0) { browser.share(targets) }
        Item("Copy (Ctrl+C)", enabled = files > 0) { copyFiles(ctx, targets) }
        Item("Move to…") { browser.transfer(targets, move = true) }
        Item("Copy to…") { browser.transfer(targets, move = false) }
        Item(if (many) "Delete ${targets.size} items" else "Delete") { browser.delete(targets) }
    }
}

@Composable
private fun ThumbImage(image: ImageBitmap, srcW: Int, srcH: Int, modifier: Modifier = Modifier) {
    val cfg = LocalTileConfig.current
    val crop = SmartCrop.shouldCrop(srcW, srcH, cfg.sizePx.width.toFloat(), cfg.sizePx.height.toFloat(), cfg.cropMode, cfg.cropThresholdPct)
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
        alignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun BoxScope.CenterIcon(icon: ImageVector, desc: String?) {
    Icon(
        icon,
        contentDescription = desc,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.align(Alignment.Center).fillMaxSize(0.4f),
    )
}

@Composable
private fun BoxScope.FailedIcon(state: TileState.Failed) =
    CenterIcon(if (state.timedOut) Icons.Rounded.HourglassDisabled else Icons.Rounded.BrokenImage, "Can't be read")

@Composable
private fun BoxScope.NameOverlay(name: String, focused: Boolean) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        TileName(name, 40, focused, style = TextStyle(color = Color.White, fontSize = 11.sp), align = Alignment.CenterStart)
    }
}

/**
 * A tile's name line; long names are shortened in the middle. On TV the focused tile
 * shows the whole name instead and, if it doesn't fit, scrolls it round and round: a remote has
 * no hover or tooltip to read it otherwise.
 */
@Composable
private fun TileName(
    name: String,
    max: Int,
    focused: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    align: Alignment = Alignment.Center,
) {
    val scroll = focused && LocalTileConfig.current.isTv
    Box(modifier, contentAlignment = align) {
        if (scroll) {
            // The marquee only moves when the text is wider than the tile.
            Text(
                name, style = style, maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 800, repeatDelayMillis = 1500),
            )
        } else {
            Text(middleEllipsis(name, max), style = style, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

/** Bottom-left ↗: a tap opens this long video in another app (README §3.11). */
@Composable
private fun BoxScope.ExternalBadge() {
    Icon(
        Icons.AutoMirrored.Rounded.OpenInNew,
        contentDescription = "Opens in external player",
        tint = Color.White,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(5.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(2.dp)
            .size(12.dp),
    )
}

@Composable
private fun BoxScope.Badge(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(5.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun BackTile(browser: BrowserState, requestFocus: Boolean, modifier: Modifier = Modifier) {
    val input = remember { TileInput() }
    TileFrame(null, browser, input, scrubbable = false, requestFocus = requestFocus, modifier = modifier) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(48.dp))
            Text("..", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun EntryTile(
    entry: FileEntry,
    browser: BrowserState,
    gate: ScrollGate,
    requestFocus: Boolean,
    /** The mini player is playing this file. */
    playing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val input = remember(entry.path) { TileInput() }
    val scrubbable = entry.type == MediaType.FOLDER || entry.type == MediaType.ANIMATED || entry.type == MediaType.VIDEO
    TileFrame(entry, browser, input, scrubbable, requestFocus, modifier, swipeScrub = entry.type != MediaType.FOLDER) {
        when (entry.type) {
            MediaType.FOLDER -> FolderContent(entry, input, gate)
            MediaType.IMAGE -> ImageContent(entry, input, gate)
            MediaType.ANIMATED, MediaType.VIDEO -> StripContent(entry, input, gate, selected = entry.path in browser.selection)
            MediaType.PARTIAL -> OtherContent(entry, Icons.Rounded.Downloading, "incomplete", input.focused)
            MediaType.OTHER ->
                if (entry.other.hasTextPreview || entry.other == OtherKind.UNKNOWN) TextContent(entry, gate, input.focused)
                else OtherContent(entry, iconFor(entry.other), formatSize(entry.size), input.focused)
        }
        val cfg = LocalTileConfig.current
        val preview = entry.isMedia || LocalThumbnails.current.cached(entry.path) is Thumb.Text
        val showName = cfg.showNames || input.focused || input.hovered || browser.selectionMode || input.noThumb
        if (preview && showName) NameOverlay(entry.name, input.focused)
        if (playing) PlayingBadge()
    }
}

/** Top-left mark on the tile of the file the mini player is playing. */
@Composable
private fun BoxScope.PlayingBadge() {
    Icon(
        Icons.Rounded.GraphicEq,
        contentDescription = "Playing",
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .size(26.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(4.dp),
    )
}

private fun iconFor(kind: OtherKind): ImageVector = when (kind) {
    OtherKind.TEXT -> Icons.AutoMirrored.Rounded.Notes
    OtherKind.CODE -> Icons.Rounded.Code
    OtherKind.DOCUMENT -> Icons.Rounded.Description
    OtherKind.PDF -> Icons.Rounded.PictureAsPdf
    OtherKind.AUDIO -> Icons.Rounded.AudioFile
    OtherKind.ARCHIVE -> Icons.Rounded.FolderZip
    OtherKind.APK -> Icons.Rounded.Android
    OtherKind.UNKNOWN -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

@Composable
private fun BoxScope.ImageContent(entry: FileEntry, input: TileInput, gate: ScrollGate) {
    val st = rememberThumb(entry, gate)
    SideEffect { input.noThumb = st is TileState.Failed }
    when (st) {
        is TileState.Ready -> when (val t = st.thumb) {
            is Thumb.Image -> ThumbImage(t.still.image, t.still.srcWidth, t.still.srcHeight)
            is Thumb.Strip -> t.frames.firstOrNull()?.let { ThumbImage(it, t.srcWidth, t.srcHeight) }
            else -> Unit
        }
        is TileState.Failed -> FailedIcon(st)
        else -> CenterIcon(Icons.Rounded.Image, null)
    }
}

/**
 * Animated image and video tiles (README §4.3): one of 5 frames, scrubbed or auto-cycled; when
 * selected, the whole GIF or video plays in the tile.
 */
@Composable
private fun BoxScope.StripContent(entry: FileEntry, input: TileInput, gate: ScrollGate, selected: Boolean) {
    // Large GIFs: only the first frame until the user hovers or swipes (README §4.3, step 3).
    // Every GIF and video: only the first frame while the folder is still being listed.
    val firstOnly = LocalTileConfig.current.firstFrameOnly
    var wantFull by remember(entry.path) { mutableStateOf(false) }
    if (!firstOnly && (input.swipe != null || input.hovered || input.keyStep != null)) wantFull = true
    val st = rememberThumb(entry, gate, full = wantFull, firstOnly = firstOnly)
    val strip = (st as? TileState.Ready)?.thumb as? Thumb.Strip
    SideEffect { input.noThumb = st is TileState.Failed }
    if (st is TileState.Ready && st.thumb is Thumb.Image) {
        ThumbImage(st.thumb.still.image, st.thumb.still.srcWidth, st.thumb.still.srcHeight)
        return
    }
    if (strip == null || strip.frames.isEmpty()) {
        if (st is TileState.Failed) FailedIcon(st) else CenterIcon(Icons.Rounded.Image, null)
        return
    }
    val n = strip.frames.size
    // Auto preview while the pointer rests on the tile, or while it has D-pad focus.
    var resting by remember { mutableStateOf(false) }
    LaunchedEffect(input.lastMove, input.hovered) {
        resting = false
        if (input.hovered) {
            delay(600)
            resting = true
        }
    }
    val cycling = n > 1 && input.swipe == null && input.keyStep == null && ((input.hovered && resting) || (input.focused && !input.hovered))
    var cycleIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(cycling, n) {
        if (!cycling) return@LaunchedEffect
        val step = 400L + min(400L, (strip.durationMs ?: 0L) / 300)
        while (true) {
            delay(step)
            cycleIdx = (cycleIdx + 1) % n
        }
    }
    val idx = (if (cycling) cycleIdx else input.index(n) ?: 0).coerceIn(0, n - 1)
    ThumbImage(strip.frames[idx], strip.srcWidth, strip.srcHeight)
    if (selected && !firstOnly) LivePreview(entry, strip.srcWidth, strip.srcHeight)
    if (!strip.complete && wantFull && strip.gif) {
        CircularProgressIndicator(Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp), strokeWidth = 2.dp, color = Color.White)
    }
    if (strip.gif) Badge("GIF") else strip.durationMs?.let { Badge(formatDuration(it)) }
    val longMs = LocalTileConfig.current.longVideoMs
    if (!strip.gif && longMs > 0 && (strip.durationMs ?: 0) > longMs) ExternalBadge()
}

/**
 * Folder collage (README §4.1): up to 4 previews like photos on a table. The groups of 4 cycle on
 * their own while the grid is idle; hover and `Shift` + arrows still pick a group.
 */
@Composable
private fun BoxScope.FolderContent(entry: FileEntry, input: TileInput, gate: ScrollGate) {
    val st = rememberThumb(entry, gate)
    val folder = (st as? TileState.Ready)?.thumb as? Thumb.Folder
    val thumbs = LocalThumbnails.current
    val size = LocalTileConfig.current.sizePx
    val firstOnly = LocalTileConfig.current.firstFrameOnly
    var autoGroup by remember(entry.path) { mutableIntStateOf(0) }
    val groupCount = folder?.groupCount ?: 0
    LaunchedEffect(groupCount, input.scrubbing, firstOnly) {
        if (groupCount < 2 || input.scrubbing || firstOnly) return@LaunchedEffect
        // Staggered per folder, so the visible tiles don't all change at the same moment.
        val stagger = (entry.path.hashCode() and 0x7fff) % FOLDER_CYCLE_MS
        delay(stagger)
        while (true) {
            delay(FOLDER_CYCLE_MS)
            gate.awaitIdle()
            autoGroup = (autoGroup + 1) % groupCount
        }
    }
    // An empty or unreadable folder (e.g. Android/data) has no groups at all.
    val group = folder?.takeIf { it.groupCount > 0 }?.let { input.index(it.groupCount) ?: autoGroup.coerceIn(0, it.groupCount - 1) } ?: 0
    var stills by remember(entry.path) { mutableStateOf<List<Still>>(emptyList()) }
    LaunchedEffect(folder, group) {
        if (folder == null) return@LaunchedEffect
        folder.groups[group]?.let {
            stills = it
            return@LaunchedEffect
        }
        stills = thumbs.loadFolderGroup(folder, group, size.width, size.height)
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxSize(0.95f),
            )
            if (stills.isEmpty() && folder != null && folder.candidates.isEmpty()) {
                Text(
                    "${folder.itemCount} items",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Collage(stills)
        }
        TileName(
            entry.name, 40, input.focused,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        )
    }
}

private const val FOLDER_CYCLE_MS = 3000L

private val COLLAGE_ANGLES = floatArrayOf(-7f, 5f, 8f, -4f)
private val COLLAGE_ALIGN = arrayOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)

@Composable
internal fun BoxScope.Collage(stills: List<Still>) {
    Box(Modifier.matchParentSize().padding(horizontal = 10.dp, vertical = 12.dp)) {
        stills.take(4).forEachIndexed { i, s ->
            val single = stills.size == 1
            Box(
                Modifier
                    .align(if (single) Alignment.Center else COLLAGE_ALIGN[i])
                    .fillMaxSize(if (single) 0.8f else 0.58f)
                    .rotate(COLLAGE_ANGLES[i])
                    .shadow(3.dp, RoundedCornerShape(3.dp))
                    .background(Color.White, RoundedCornerShape(3.dp))
                    .padding(2.dp)
                    .clip(RoundedCornerShape(2.dp)),
            ) {
                Image(s.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Text, code and extensionless text files (README §3.10): the first lines on a paper-like background,
 * with the extension as a badge. The icon stays until the preview is ready, and for binary files.
 */
@Composable
private fun BoxScope.TextContent(entry: FileEntry, gate: ScrollGate, focused: Boolean) {
    val text = ((rememberThumb(entry, gate) as? TileState.Ready)?.thumb as? Thumb.Text)
    if (text == null) {
        OtherContent(entry, iconFor(entry.other), formatSize(entry.size), focused)
        return
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
    Image(
        bitmap = text.image,
        contentDescription = null,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.87f)),
        contentScale = ContentScale.None,
        alignment = Alignment.TopStart,
        modifier = Modifier.fillMaxSize(),
    )
    FileTypes.extension(entry.name)?.let { Badge(it.uppercase()) }
}

@Composable
private fun BoxScope.OtherContent(entry: FileEntry, icon: ImageVector, detail: String, focused: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f).fillMaxWidth(0.5f),
        )
        TileName(entry.name, 32, focused, style = MaterialTheme.typography.labelSmall)
        Text(detail, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
