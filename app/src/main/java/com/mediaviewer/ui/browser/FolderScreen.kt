package com.mediaviewer.ui.browser

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mediaviewer.data.fs.FolderListing
import com.mediaviewer.data.fs.Volume
import com.mediaviewer.data.fs.Volumes
import com.mediaviewer.data.history.FolderHistory
import com.mediaviewer.data.mtp.MtpPaths
import com.mediaviewer.domain.BookKind
import com.mediaviewer.domain.ListenStatus
import com.mediaviewer.domain.SortBy
import com.mediaviewer.domain.SortSpec
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.playback.NowPlaying
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.Overlay
import com.mediaviewer.ui.common.CodeFonts
import com.mediaviewer.ui.common.ExternalApps
import com.mediaviewer.ui.common.StepSlider
import com.mediaviewer.ui.common.stepIn
import com.mediaviewer.ui.playback.LocalMiniPlayerHeight
import com.mediaviewer.ui.common.TipIconButton
import com.mediaviewer.ui.common.copyFiles
import com.mediaviewer.ui.common.formatSize
import com.mediaviewer.ui.common.topInset
import com.mediaviewer.ui.common.volumeIcon
import com.mediaviewer.ui.desktopClicks
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

const val BACK_KEY = ".."

/** Grid densities for pinch, `Ctrl` + wheel and `+`/`-` (README §4.5). */
val TILE_STEPS = intArrayOf(72, 90, 110, 130, 160, 200, 240, 300, 380, 480)

fun stepTile(current: Int, dir: Int): Int = stepIn(TILE_STEPS, current, dir)

/**
 * Automatic thumbnail size (README §2, "Folder view"): about 3 columns on a phone, 4–5 on small tablets and
 * one column per ~200 dp on wide windows (DeX, Chromebooks, large tablets), snapped to a step.
 */
fun autoTileDp(width: Dp, tv: Boolean): Int {
    val w = width.value
    val columns = when {
        tv -> 5f
        w < 480f -> 3f
        w < 720f -> 4f
        w < 1000f -> 5f
        else -> w / 200f
    }
    return TILE_STEPS.minBy { abs(it - w / columns) }
}

fun isTelevision(context: Context): Boolean =
    context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(vm: AppViewModel, listing: FolderListing) {
    val settings by vm.settings.collectAsState()
    val entries by listing.entries.collectAsState()
    val status by listing.status.collectAsState()
    val focusTarget by vm.focusTarget.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val devices by vm.devices.collectAsState()
    val remote = MtpPaths.isMtp(listing.path)
    val sort by vm.folderSort.collectAsState()
    val nowPlaying by vm.bookPlayer.state.collectAsState()
    val playingPath = nowPlaying?.path?.takeIf { nowPlaying?.folder == listing.path }
    // Books among the subfolders (todo §10.1): read again when playback pauses, changes file or book.
    val books by produceState(emptyMap<String, FolderHistory>(), listing.path, nowPlaying?.folder, nowPlaying?.index, nowPlaying?.playing) {
        if (value.isNotEmpty()) delay(500) // let the player's save on pause / file change land first
        value = vm.booksIn(listing.path)
    }
    val folderSorts by vm.settingsStore.folderSorts.collectAsState()
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val grid = rememberLazyGridState()
    val browser = remember { BrowserState(vm, ctx, grid) }
    val hasPlayer by ExternalApps.hasVideoPlayer.collectAsState()
    val miniHeight = LocalMiniPlayerHeight.current
    // Play button (todo §3.7): book playback for audio, mixed and lecture folders, else the slideshow.
    // Classified on IO once the listing is complete, and again when the folder changes.
    var bookKind by remember(listing.path) { mutableStateOf<BookKind?>(null) }
    LaunchedEffect(entries, status, settings.longVideoMin) {
        if (status != FolderListing.Status.DONE) return@LaunchedEffect
        delay(300) // live updates come in bursts
        bookKind = vm.bookKind(entries)
    }
    val canPlay = bookKind != null || entries.any { it.isMedia }
    browser.entries = entries
    val gate = remember { ScrollGate(grid) }
    val isTv = remember { isTelevision(ctx) }
    val rootFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    var barVisible by remember { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // New text file or folder (todo §6.1).
    var newItem by remember { mutableStateOf<NewItem?>(null) }
    newItem?.let { kind ->
        NewItemDialog(
            kind, entries,
            onCreate = { name, onError -> if (kind == NewItem.FILE) vm.createFile(name, onError) else vm.createFolder(name, onError) },
            onDismiss = { newItem = null },
        )
    }

    LaunchedEffect(entries) { browser.prune(entries) }
    // Back leaves selection mode first, instead of going up and losing the selection.
    BackHandler(enabled = browser.selectionMode) { browser.clearSelection() }
    browser.pendingTransfer?.let { t ->
        FolderPicker(
            // From a device (todo §9.5): start in the last local folder.
            vm, if (remote) vm.settingsStore.recent.value.firstOrNull { !MtpPaths.isMtp(it.path) }?.path ?: volumes.firstOrNull()?.path ?: "/" else listing.path, t.move,
            onPick = { dest ->
                browser.pendingTransfer = null
                browser.clearSelection()
                vm.transfer(t.entries, dest, t.move)
            },
            onDismiss = { browser.pendingTransfer = null },
        )
    }

    // README §4.7: scroll to the last viewed / previous child folder; the tile then takes focus.
    LaunchedEffect(focusTarget, entries.size) {
        val t = focusTarget ?: return@LaunchedEffect
        val idx = if (t == BACK_KEY) -1 else entries.indexOfFirst { it.path == t }
        if (idx < 0 && t != BACK_KEY) {
            if (status != FolderListing.Status.LOADING) vm.consumeFocusTarget()
            return@LaunchedEffect
        }
        val gi = idx + 1
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == gi }) grid.scrollToItem(gi)
    }

    // The mini player moved to another file (⏮ / ⏭, or the end of one): its tile takes the focus.
    var lastPlaying by remember(listing.path) { mutableStateOf(nowPlaying?.path) }
    LaunchedEffect(nowPlaying?.path) {
        val p = nowPlaying?.path
        val previous = lastPlaying
        lastPlaying = p
        if (p != null && previous != null && p != previous && !browser.selectionMode && entries.any { it.path == p }) vm.setFocusTarget(p)
    }

    // README §7.2: restore the saved scroll anchor as soon as it streams in.
    val recent = remember { vm.recentFor(listing.path) }
    var anchorDone by remember { mutableStateOf(focusTarget != null || recent?.anchorKey == null) }
    LaunchedEffect(entries.size, status) {
        if (anchorDone) return@LaunchedEffect
        if (grid.firstVisibleItemIndex > 0) {
            anchorDone = true
            return@LaunchedEffect
        }
        val key = recent?.anchorKey
        val idx = if (key == BACK_KEY) -1 else entries.indexOfFirst { it.path == key }
        if (idx >= 0 || key == BACK_KEY) {
            grid.scrollToItem(idx + 1, recent?.anchorOffset ?: 0)
            anchorDone = true
        } else if (status != FolderListing.Status.LOADING) {
            anchorDone = true
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }
            .drop(1)
            .debounce(700)
            .collect { (_, off) ->
                if (!anchorDone) return@collect
                val key = grid.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
                vm.saveAnchor(listing.path, key, off)
            }
    }
    LaunchedEffect(Unit) {
        if (focusTarget == null) {
            try {
                rootFocus.requestFocus()
            } catch (e: IllegalStateException) {
            }
        }
    }

    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -4f) barVisible = false else if (available.y > 4f) barVisible = true
                return Offset.Zero
            }
        }
    }

    // The size in use and the column count, also while the size is automatic.
    val layout = remember { intArrayOf(settings.tileDp, 1) }

    fun zoom(dir: Int) {
        // Pinch out / Ctrl+wheel up: bigger thumbnails. Any manual change ends the automatic size.
        vm.settingsStore.update { it.copy(tileDp = stepTile(layout[0], dir), tileAuto = false) }
    }

    /** Page Up / Down, Home / End: move the focus by a screen, or to the ends (README §2, "Desktops"). */
    fun focusBy(page: Int?, end: Int = 0) {
        val cols = layout[1]
        val visible = grid.layoutInfo.visibleItemsInfo.size
        val current = browser.focused?.let { f -> entries.indexOfFirst { it.path == f.path } + 1 } ?: grid.firstVisibleItemIndex
        val target = when {
            page != null -> current + page * cols * max(1, visible / cols - 1)
            end < 0 -> 0
            else -> entries.size
        }.coerceIn(0, entries.size)
        vm.setFocusTarget(if (target == 0) BACK_KEY else entries[target - 1].path)
    }

    val statusTop = topInset()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    ev.key == Key.Plus || ev.key == Key.Equals || ev.key == Key.NumPadAdd -> zoom(1).let { true }
                    ev.key == Key.Minus || ev.key == Key.NumPadSubtract -> zoom(-1).let { true }
                    ev.key == Key.Backspace || (ev.isAltPressed && ev.key == Key.DirectionLeft) -> vm.goUp().let { true }
                    ev.key == Key.Comma -> vm.showOverlay(Overlay.SETTINGS).let { true }
                    ev.key == Key.N && !ev.isCtrlPressed && !browser.selectionMode && !remote -> {
                        newItem = if (ev.isShiftPressed) NewItem.FOLDER else NewItem.FILE
                        true
                    }
                    ev.key == Key.S && !ev.isCtrlPressed && !browser.selectionMode -> {
                        barVisible = true
                        sortMenu = true
                        true
                    }
                    ev.key == Key.P && !ev.isCtrlPressed && canPlay && !browser.selectionMode ->
                        vm.playFolder(listing.path, entries, bookKind).let { true }
                    ev.key == Key.Escape && browser.selectionMode -> browser.clearSelection().let { true }
                    ev.isCtrlPressed && ev.key == Key.A -> browser.selectAll().let { true }
                    ev.isCtrlPressed && ev.key == Key.C && !remote -> copyFiles(ctx, browser.keyTargets()).let { true }
                    ev.key == Key.Delete -> browser.delete(browser.keyTargets()).let { true }
                    ev.key == Key.PageDown -> focusBy(1).let { true }
                    ev.key == Key.PageUp -> focusBy(-1).let { true }
                    ev.key == Key.MoveHome -> focusBy(null, -1).let { true }
                    ev.key == Key.MoveEnd -> focusBy(null, 1).let { true }
                    !gridHasFocus && ev.key in setOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight) -> {
                        val first = grid.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String ?: BACK_KEY
                        vm.setFocusTarget(first)
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        val tileDp = if (settings.tileAuto) autoTileDp(maxWidth, isTv) else settings.tileDp
        val columns = max(1, (maxWidth / tileDp.dp).toInt())
        layout[0] = tileDp
        layout[1] = columns
        val cellW = maxWidth / columns
        val sizePx = with(density) { IntSize(cellW.roundToPx(), (cellW * 0.75f).roundToPx()) }
        // Text previews (README §3.10): 10 sp, growing with the system font size up to 13 sp; 12 sp on TV.
        val textPx = (if (isTv) 12f else 10f) * density.fontScale.coerceAtMost(1.3f) * density.density
        val textPadPx = with(density) { 6.dp.roundToPx() }
        SideEffect {
            vm.thumbnails.textPx = textPx
            vm.thumbnails.textPadPx = textPadPx
            vm.thumbnails.monoTypeface = CodeFonts.typeface(ctx, settings.codeFontBundled)
        }
        val cfg = TileConfig(
            sizePx, settings.cropMode, settings.cropThresholdPct, isTv, settings.showNames, desktopClicks(settings.mouseMode),
            firstFrameOnly = status == FolderListing.Status.LOADING,
            longVideoMs = if (hasPlayer) settings.longVideoMin * 60_000L else 0L,
        )
        LaunchedEffect(columns, maxHeight) {
            val rows = (maxHeight / (cellW * 0.75f)).toInt() + 3
            vm.thumbnails.ensureCapacity(rows * columns)
        }
        // Drop files from other apps into this folder (todo §10.5); MTP folders are read-only.
        var dropHover by remember { mutableStateOf(false) }
        val drop = remember(listing.path) {
            FileDrop(ctx as? Activity, onHover = { dropHover = it }) { uris, release -> vm.importDropped(uris, listing.path, release) }
        }
        CompositionLocalProvider(LocalTileConfig provides cfg) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = statusTop + TOP_BAR_HEIGHT, bottom = navBottom + 8.dp + miniHeight),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { browser.gridOrigin = it.positionInRoot() }
                    .onFocusChanged { gridHasFocus = it.hasFocus }
                    .nestedScroll(hideOnScroll)
                    .densityGestures(::zoom, onTwoFingerTap = browser::twoFingerTap)
                    .dragAndDropTarget(shouldStartDragAndDrop = { !remote && FileDrop.accepts(it) }, target = drop)
                    .then(if (dropHover) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier),
            ) {
                item(key = BACK_KEY, contentType = "back") {
                    BackTile(browser, requestFocus = focusTarget == BACK_KEY, modifier = Modifier.aspectRatio(4f / 3f))
                }
                items(entries, key = { it.path }, contentType = { it.type }) { e ->
                    EntryTile(
                        e, browser, gate, requestFocus = e.path == focusTarget, playing = e.path == playingPath,
                        listen = if (e.type == MediaType.FOLDER) listenStatus(e.path, nowPlaying, books) else null,
                        modifier = Modifier.aspectRatio(4f / 3f),
                    )
                }
            }
        }

        FastScroller(
            grid, entries, sort.by, gate,
            Modifier.align(Alignment.CenterEnd).padding(top = statusTop + TOP_BAR_HEIGHT, bottom = navBottom + miniHeight),
        )

        AnimatedVisibility(
            visible = barVisible || browser.selectionMode,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (browser.selectionMode) {
                SelectionBar(browser, remote)
            } else {
                TopBar(
                    vm, listing.path,
                    if (remote) devices.firstNotNullOfOrNull { d -> d.storages.firstOrNull { it.path == MtpPaths.rootOf(listing.path) } }
                    else Volumes.rootOf(listing.path, volumes),
                    settings.favourites, tileDp, compact = maxWidth < 600.dp, remote = remote,
                    play = if (canPlay) ({ vm.playFolder(listing.path, entries, bookKind) }) else null,
                    playLabel = if (bookKind != null) "Listen" else "Slideshow",
                    onNew = { newItem = it },
                    sort = SortChoice(
                        sort, own = listing.path in folderSorts, open = sortMenu,
                        onOpen = { sortMenu = it },
                        onPick = vm::setFolderSort,
                        onReset = vm::resetFolderSort,
                    ),
                )
            }
        }

        if (status == FolderListing.Status.STOPPED) {
            Surface(
                shape = RoundedCornerShape(50),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusTop + TOP_BAR_HEIGHT + 8.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { vm.continueListing() },
            ) {
                Text("Listing stopped — tap to continue", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (entries.isEmpty() && status == FolderListing.Status.DONE) {
            Text("Empty folder", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entries.isEmpty() && status == FolderListing.Status.FAILED) {
            Text(
                "This folder can't be read",
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private val TOP_BAR_HEIGHT = 44.dp

/**
 * Grid-level touch (README §2, "Folder view"): pinch and `Ctrl` + wheel change the thumbnail size, and a
 * two-finger tap selects the tile under the first finger, the touch alternative to long press.
 * Never consumes plain scrolls; once a second finger is down, the tiles' own taps are cancelled
 * (their up events are consumed), so a two-finger tap never opens anything.
 */
private fun Modifier.densityGestures(onZoom: (Int) -> Unit, onTwoFingerTap: (Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            val start = android.os.SystemClock.uptimeMillis()
            val downAt = HashMap<androidx.compose.ui.input.pointer.PointerId, Offset>()
            downAt[first.id] = first.position
            var acc = 1f
            var maxPointers = 1
            var travel = 0f // largest distance any finger moved from where it went down
            var zoomed = false
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                for (c in ev.changes) {
                    val d = downAt.getOrPut(c.id) { c.position }
                    travel = max(travel, (c.position - d).getDistance())
                }
                maxPointers = max(maxPointers, ev.changes.count { it.pressed })
                // A second finger cancels the tiles' own taps.
                if (maxPointers >= 2) ev.changes.forEach { c -> if (!c.pressed && c.previousPressed) c.consume() }
                if (ev.changes.count { it.pressed } >= 2) {
                    acc *= ev.calculateZoom()
                    if (acc > 1.3f) {
                        onZoom(1)
                        acc = 1f
                        zoomed = true
                    } else if (acc < 0.77f) {
                        onZoom(-1)
                        acc = 1f
                        zoomed = true
                    }
                }
            } while (ev.changes.any { it.pressed })
            val quick = android.os.SystemClock.uptimeMillis() - start < viewConfiguration.longPressTimeoutMillis
            if (maxPointers == 2 && !zoomed && travel <= viewConfiguration.touchSlop && quick) onTwoFingerTap(first.position)
        }
    }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.type == PointerEventType.Scroll && ev.keyboardModifiers.isCtrlPressed) {
                    val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f) onZoom(if (dy < 0) 1 else -1)
                    ev.changes.forEach { it.consume() }
                }
            }
        }
    }

@Composable
private fun TopBar(
    vm: AppViewModel,
    path: String,
    volume: Volume?,
    favourites: Set<String>,
    tileDp: Int,
    compact: Boolean,
    /** A folder on an MTP device (todo §9): no new files, no favourite. */
    remote: Boolean,
    /** Folder Play button (todo §3.7); null hides it. */
    play: (() -> Unit)?,
    playLabel: String,
    onNew: (NewItem) -> Unit,
    sort: SortChoice,
) {
    val statusTop = topInset()
    // Breadcrumbs below the volume root (or the file-system root); the root itself is an icon, since
    // a volume name like "Internal storage" can take half a phone's width in some languages.
    val base = volume?.path ?: "/"
    val crumbs = remember(path, base) {
        val list = ArrayList<Pair<String, String>>()
        val rest = path.removePrefix(base).trim('/')
        var cur = base.trimEnd('/')
        if (rest.isNotEmpty()) for (seg in rest.split('/')) {
            cur = "$cur/$seg"
            list.add(cur to seg)
        }
        list
    }
    val rowState = rememberLazyListState()
    LaunchedEffect(crumbs.size) { rowState.scrollToItem(max(0, crumbs.size - 1)) }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(top = statusTop).height(TOP_BAR_HEIGHT).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TipIconButton("Storage", onClick = vm::goRoot) { Icon(Icons.Rounded.Home, contentDescription = "Storage") }
            val rootLabel = volume?.label ?: "/"
            TipIconButton(rootLabel, onClick = { if (base != path) vm.openFolder(base, focus = path) }) {
                Icon(volumeIcon(volume), contentDescription = rootLabel)
            }
            LazyRow(state = rowState, modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(crumbs) { i, (p, label) ->
                    val last = i == crumbs.size - 1
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .then(if (last) Modifier else Modifier.widthIn(max = 140.dp))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { if (p != path) vm.openFolder(p, focus = path) }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            val setTile: (Int) -> Unit = { dp -> vm.settingsStore.update { it.copy(tileDp = dp, tileAuto = false) } }
            if (compact) {
                // Phones: the slider would squeeze the breadcrumbs; it opens from a button instead.
                var open by remember { mutableStateOf(false) }
                Box {
                    TipIconButton("Thumbnail size", onClick = { open = true }) { Icon(Icons.Rounded.GridView, contentDescription = "Thumbnail size") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            TileSizeSlider(tileDp, width = 200.dp, onChange = setTile)
                        }
                    }
                }
            } else {
                TileSizeSlider(tileDp, onChange = setTile)
                Box {
                    TipIconButton("Sort (S)", onClick = { sort.onOpen(true) }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort") }
                    SortMenu(sort)
                }
            }
            if (play != null) {
                TipIconButton("$playLabel (P)", onClick = play) {
                    Icon(if (playLabel == "Listen") Icons.Rounded.Headphones else Icons.Rounded.PlayArrow, contentDescription = playLabel)
                }
            }
            val fav = path in favourites
            val favLabel = if (fav) "Remove favourite" else "Add favourite"
            val favIcon = if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder
            var menu by remember { mutableStateOf(false) }
            @Composable
            fun Item(label: String, icon: ImageVector, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                onClick = {
                    menu = false
                    action()
                },
            )
            if (compact) {
                // Phones (todo §6.1): New, the favourite star and Settings share one menu, so the
                // breadcrumbs keep more room than one more icon would leave them.
                Box {
                    TipIconButton("More", onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Item("Sort…", Icons.AutoMirrored.Rounded.Sort) { sort.onOpen(true) }
                        if (!remote) {
                            Item("New text file", Icons.AutoMirrored.Rounded.NoteAdd) { onNew(NewItem.FILE) }
                            Item("New folder", Icons.Rounded.CreateNewFolder) { onNew(NewItem.FOLDER) }
                            Item(favLabel, favIcon) { vm.toggleFavourite(path) }
                        }
                        Item("Settings", Icons.Rounded.Settings) { vm.showOverlay(Overlay.SETTINGS) }
                    }
                    // Opened from the menu item or `S`, in the menu's place.
                    SortMenu(sort)
                }
            } else if (!remote) {
                Box {
                    TipIconButton("New (N)", onClick = { menu = true }) { Icon(Icons.Rounded.Add, contentDescription = "New") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Item("New text file (N)", Icons.AutoMirrored.Rounded.NoteAdd) { onNew(NewItem.FILE) }
                        Item("New folder (Shift+N)", Icons.Rounded.CreateNewFolder) { onNew(NewItem.FOLDER) }
                    }
                }
                TipIconButton(favLabel, onClick = { vm.toggleFavourite(path) }) { Icon(favIcon, contentDescription = favLabel) }
                TipIconButton("Settings ( , )", onClick = { vm.showOverlay(Overlay.SETTINGS) }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            } else {
                TipIconButton("Settings ( , )", onClick = { vm.showOverlay(Overlay.SETTINGS) }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            }
        }
    }
}

/** The folder's sort menu and what it needs; [own] = the folder has its own order, not the default. */
private class SortChoice(
    val spec: SortSpec,
    val own: Boolean,
    val open: Boolean,
    val onOpen: (Boolean) -> Unit,
    val onPick: (SortBy, Boolean) -> Unit,
    val onReset: () -> Unit,
)

/**
 * The folder's own order (README §2, "Folder view"): name (natural order) or date modified,
 * ascending or descending. Kept per folder between runs; "Default order" forgets it.
 */
@Composable
private fun SortMenu(c: SortChoice) {
    DropdownMenu(expanded = c.open, onDismissRequest = { c.onOpen(false) }) {
        @Composable
        fun Option(label: String, checked: Boolean, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { if (checked) Icon(Icons.Rounded.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
            enabled = enabled,
            onClick = {
                c.onOpen(false)
                action()
            },
        )
        val by = c.spec.by
        // A new key starts in its natural direction: names A–Z, dates newest first.
        Option("Name", by == SortBy.NAME) { if (by != SortBy.NAME) c.onPick(SortBy.NAME, false) }
        Option("Date modified", by == SortBy.DATE) { if (by != SortBy.DATE) c.onPick(SortBy.DATE, true) }
        HorizontalDivider()
        val (asc, desc) = when (by) {
            SortBy.NAME -> "A to Z" to "Z to A"
            SortBy.DATE -> "Oldest first" to "Newest first"
            else -> "Ascending" to "Descending"
        }
        Option(asc, !c.spec.descending) { c.onPick(by, false) }
        Option(desc, c.spec.descending) { c.onPick(by, true) }
        HorizontalDivider()
        Option("Default order", !c.own, enabled = c.own) { c.onReset() }
    }
}

/**
 * Small thumbnail-size slider in the top bar (README §4). It steps through the same sizes as
 * pinch and `Ctrl` + wheel; the value is a setting, so it is kept between runs.
 */
@Composable
private fun TileSizeSlider(tileDp: Int, width: Dp = 112.dp, onChange: (Int) -> Unit) =
    StepSlider(TILE_STEPS, tileDp, Icons.Rounded.GridView, "Thumbnail size", width, onChange)

/** Contextual bar of selection mode (README §4.6). */
@Composable
private fun SelectionBar(browser: BrowserState, remote: Boolean) {
    val statusTop = topInset()
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(top = statusTop).height(TOP_BAR_HEIGHT).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TipIconButton("Cancel (Esc)", onClick = browser::clearSelection) { Icon(Icons.Rounded.Close, contentDescription = "Cancel") }
            Text("${browser.selection.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TipIconButton("Select all (Ctrl+A)", onClick = browser::selectAll) { Icon(Icons.AutoMirrored.Rounded.PlaylistAddCheck, contentDescription = "Select all") }
            TipIconButton("Invert selection", onClick = browser::invert) { Icon(Icons.Rounded.SwapHoriz, contentDescription = "Invert selection") }
            if (remote) {
                // Device folders (todo §9.5): copying to this phone is the only action.
                TipIconButton("Copy to…", onClick = { browser.transfer(browser.selectedEntries(), false) }) {
                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = "Copy to…")
                }
                return@Row
            }
            TipIconButton("Share", onClick = { browser.share(browser.selectedEntries()) }) {
                Icon(Icons.Rounded.Share, contentDescription = "Share")
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                TipIconButton("Move or copy to…", onClick = { menu = true }) {
                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = "Move or copy to…")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; browser.transfer(browser.selectedEntries(), true) })
                    DropdownMenuItem(text = { Text("Copy to…") }, onClick = { menu = false; browser.transfer(browser.selectedEntries(), false) })
                }
            }
            TipIconButton("Delete (Del)", onClick = { browser.delete(browser.selectedEntries()) }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
        }
    }
}

/**
 * Draggable fast-scroll thumb with a position bubble (README §8.1): flinging through 29k items
 * by finger alone is not usable.
 */
@Composable
private fun FastScroller(grid: LazyGridState, entries: List<FileEntry>, sortBy: SortBy, gate: ScrollGate, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(-1f) }
    val scrolling = grid.isScrollInProgress
    LaunchedEffect(scrolling, gate.dragging) {
        if (scrolling || gate.dragging) {
            show = true
        } else {
            delay(1500)
            show = false
        }
    }
    val info = grid.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total <= visible * 3 || visible == 0) return
    val density = LocalDensity.current
    val thumbH = with(density) { 48.dp.toPx() }
    BoxWithConstraints(modifier.fillMaxHeight().width(36.dp)) {
        val trackH = constraints.maxHeight.toFloat()
        val range = (total - visible).coerceAtLeast(1)
        val frac = if (dragY >= 0) ((dragY - thumbH / 2) / (trackH - thumbH)).coerceIn(0f, 1f)
        else (grid.firstVisibleItemIndex.toFloat() / range).coerceIn(0f, 1f)
        val y = frac * (trackH - thumbH)

        fun jump(py: Float) {
            dragY = py
            val f = ((py - thumbH / 2) / (trackH - thumbH)).coerceIn(0f, 1f)
            scope.launch { grid.scrollToItem((f * range).roundToInt()) }
        }

        // Only grabbable while visible (after a scroll), so the strip never blocks the tiles under it.
        val grabbable = show || gate.dragging
        Box(
            Modifier
                .fillMaxSize()
                .then(if (!grabbable) Modifier else Modifier.pointerInput(trackH) {
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            gate.dragging = true
                            jump(o.y)
                        },
                        onDragEnd = {
                            gate.dragging = false
                            dragY = -1f
                        },
                        onDragCancel = {
                            gate.dragging = false
                            dragY = -1f
                        },
                    ) { change, _ ->
                        change.consume()
                        jump(change.position.y)
                    }
                }),
        ) {
            AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .offset { IntOffset(0, y.roundToInt()) }
                            .align(Alignment.TopEnd)
                            .padding(end = 3.dp)
                            .width(8.dp)
                            .height(48.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
        if (dragY >= 0) {
            val idx = ((frac * range).roundToInt() - 1).coerceIn(0, max(0, entries.size - 1))
            val label = entries.getOrNull(idx)?.let { bubbleLabel(it, sortBy) } ?: ""
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .offset { IntOffset(-with(density) { 96.dp.roundToPx() }, y.roundToInt()) }
                    .align(Alignment.TopEnd),
            ) {
                Text(label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun bubbleLabel(e: FileEntry, sortBy: SortBy): String = when (sortBy) {
    SortBy.NAME -> e.name.firstOrNull()?.uppercaseChar()?.toString() ?: ""
    SortBy.DATE -> SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(e.mtime))
    SortBy.SIZE -> formatSize(e.size)
    SortBy.TYPE -> File(e.name).extension.lowercase().ifEmpty { "—" }
}

/** The live player for the book playing, else its saved history; null = never listened to (todo §10.1). */
private fun listenStatus(path: String, now: NowPlaying?, books: Map<String, FolderHistory>): ListenStatus? {
    if (now != null && now.folder == path) {
        return ListenStatus(now.index, now.count, now.positionMs, now.durationMs, finished = false, playing = now.playing)
    }
    val h = books[path] ?: return null
    return ListenStatus(h.index, h.total, h.positionMs, h.durationMs, h.finished)
}
