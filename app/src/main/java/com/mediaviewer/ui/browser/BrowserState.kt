package com.mediaviewer.ui.browser

import android.content.Context
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import com.mediaviewer.data.mtp.MtpPaths
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.common.ExternalApps
import com.mediaviewer.ui.common.shareFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A move or copy waiting for its destination in the folder picker. */
data class Transfer(val move: Boolean, val entries: List<FileEntry>)

/** Selection mode and tile actions of the folder view (README §4.5, §4.6). */
@Stable
class BrowserState(
    private val vm: AppViewModel,
    private val context: Context,
    val grid: LazyGridState,
) {
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    val selectionMode: Boolean get() = selection.isNotEmpty()

    /** Current entries, without the back tile (grid index = entry index + 1). */
    var entries: List<FileEntry> = emptyList()

    /** Root position of the grid, for drag-to-select hit testing. */
    var gridOrigin: Offset = Offset.Zero

    /** The tile with keyboard focus, for Delete, `Ctrl` + C and Page Up / Down (README §2, "Desktops"). */
    var focused: FileEntry? = null

    var pendingTransfer by mutableStateOf<Transfer?>(null)

    /**
     * The folder is on an MTP device (todo §9): read-only for now, so share, move, delete and
     * editing are left out, and files are fetched before they open.
     */
    val remote: Boolean get() = MtpPaths.isMtp(vm.listing.value?.path)

    private var dragAnchor = -1
    private var clickAnchor = -1
    private var dragSelecting = true
    private var dragBase: Set<String> = emptySet()

    fun goUp() = vm.goUp()

    fun open(e: FileEntry) {
        if (MtpPaths.isMtp(e.path)) return openRemote(e)
        when {
            e.isFolder -> vm.openFolder(e.path)
            // Other apps open only from the plain folder view, never in selection mode.
            e.type == MediaType.VIDEO && !selectionMode -> openVideo(e)
            e.isMedia -> vm.openSlideshow(e)
            e.type == MediaType.PARTIAL || selectionMode -> Unit
            canEdit(e) && vm.settings.value.textInApp -> edit(e)
            else -> openWith(e, chooser = false)
        }
    }

    private fun openRemote(e: FileEntry) {
        when {
            e.isFolder -> vm.openFolder(e.path)
            e.isMedia -> vm.openSlideshow(e)
            e.type == MediaType.PARTIAL || selectionMode -> Unit
            else -> vm.openDeviceFileWith(e) { local -> ExternalApps.open(context, local, chooser = false) }
        }
    }

    /**
     * Text, code, and files of unknown type, which may turn out to be text (todo §6.2). The editor
     * checks the content: binary or very large files still open with another app.
     */
    fun canEdit(e: FileEntry): Boolean =
        !MtpPaths.isMtp(e.path) && e.type == MediaType.OTHER && (e.other.hasTextPreview || e.other == OtherKind.UNKNOWN)

    /** The context menu offers Edit when a tap opens text files with other apps. */
    fun offersEdit(e: FileEntry): Boolean = canEdit(e) && !vm.settings.value.textInApp

    fun edit(e: FileEntry) {
        if (selectionMode) return
        vm.openText(e, orElse = { openWith(e, chooser = false) })
    }

    /** `Shift` + Enter and "Play in slideshow": media never leave the app. */
    fun openInApp(e: FileEntry) {
        if (e.isMedia) vm.openSlideshow(e) else open(e)
    }

    /** Context menu "Open with…": always the system chooser. */
    fun openWith(e: FileEntry, chooser: Boolean = true) {
        if (selectionMode) return
        if (MtpPaths.isMtp(e.path)) return vm.openDeviceFileWith(e) { local -> ExternalApps.open(context, local, chooser) }
        vm.viewModelScope.launch {
            if (!ExternalApps.open(context, e, chooser)) vm.errors.notify("No app can open ${e.name}", e.path, log = false)
        }
    }

    /** Long videos open in the external player when one is installed (README §3.11), the rest in the slideshow. */
    private fun openVideo(e: FileEntry) {
        val limit = vm.settings.value.longVideoMin * 60_000L
        if (limit <= 0 || !ExternalApps.hasVideoPlayer.value) return vm.openSlideshow(e)
        vm.viewModelScope.launch {
            // Usually cached in MetaStore; otherwise a short read under the thumbnail watchdog.
            val duration = try {
                withContext(Dispatchers.IO) { vm.thumbnails.videoMeta(e).durationMs }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                0L
            }
            if (duration > limit && ExternalApps.open(context, e)) return@launch
            vm.openSlideshow(e)
        }
    }

    /** Long press / double-click / right-click / Space: enter selection mode, or toggle. */
    fun select(e: FileEntry) {
        dragBase = selection
        dragAnchor = entries.indexOfFirst { it.path == e.path }
        dragSelecting = e.path !in selection
        toggle(e)
    }

    /**
     * Desktop mouse clicks (README §2, "Desktops"): `Ctrl` + click toggles, `Shift` + click adds the range
     * from the last clicked tile, a plain click toggles only in selection mode (otherwise it just
     * focuses the tile). Double-click opens.
     */
    fun desktopClick(e: FileEntry, ctrl: Boolean, shift: Boolean) {
        val idx = entries.indexOfFirst { it.path == e.path }
        when {
            shift && clickAnchor >= 0 && idx >= 0 -> {
                selection = selection + (minOf(clickAnchor, idx)..maxOf(clickAnchor, idx)).map { entries[it].path }
                return
            }
            ctrl || shift -> toggle(e)
            selectionMode -> toggle(e)
        }
        clickAnchor = idx
    }

    /** What a context-menu action applies to: the selection if [e] is part of it, else [e] alone. */
    fun targets(e: FileEntry): List<FileEntry> = if (e.path in selection) selectedEntries() else listOf(e)

    /** Delete key and `Ctrl` + C: the selection, or the focused tile. */
    fun keyTargets(): List<FileEntry> = if (selectionMode) selectedEntries() else listOfNotNull(focused)

    fun delete(list: List<FileEntry>) {
        if (list.isEmpty() || remote) return
        clearSelection()
        vm.delete(list)
    }

    fun share(list: List<FileEntry>) {
        if (!remote) shareFiles(context, list.filter { !it.isFolder })
    }

    fun transfer(list: List<FileEntry>, move: Boolean) {
        if (move && remote) return
        if (list.isNotEmpty()) pendingTransfer = Transfer(move, list)
    }

    fun toggle(e: FileEntry) {
        selection = if (e.path in selection) selection - e.path else selection + e.path
    }

    fun selectAll() {
        selection = entries.mapTo(HashSet()) { it.path }
    }

    fun invert() {
        selection = entries.mapNotNullTo(HashSet()) { if (it.path in selection) null else it.path }
    }

    fun clearSelection() {
        selection = emptySet()
    }

    /** Leaving the folder or deleting ends selection mode (README §4.6). */
    fun prune(valid: List<FileEntry>) {
        if (selection.isEmpty()) return
        val paths = valid.mapTo(HashSet()) { it.path }
        val kept = selection.filterTo(HashSet()) { it in paths }
        if (kept.size != selection.size) selection = kept
    }

    fun selectedEntries(): List<FileEntry> = entries.filter { it.path in selection }

    fun dragSelectTo(root: Offset) {
        if (dragAnchor < 0) return
        var info = grid.layoutInfo
        val local = root - gridOrigin
        // Near the top or bottom edge the grid scrolls, so a range can span more than one screen.
        val edge = info.viewportSize.height * 0.12f
        val delta = when {
            local.y < edge -> -(edge - local.y) / 2f
            local.y > info.viewportSize.height - edge -> (local.y - (info.viewportSize.height - edge)) / 2f
            else -> 0f
        }
        if (delta != 0f) {
            grid.dispatchRawDelta(delta)
            info = grid.layoutInfo
        }
        val y = local.y + info.viewportStartOffset
        val hit = info.visibleItemsInfo.firstOrNull {
            local.x >= it.offset.x && local.x < it.offset.x + it.size.width &&
                y >= it.offset.y && y < it.offset.y + it.size.height
        } ?: return
        val idx = hit.index - 1
        if (idx < 0 || idx >= entries.size) return
        val range = (minOf(dragAnchor, idx)..maxOf(dragAnchor, idx)).map { entries[it].path }
        selection = if (dragSelecting) dragBase + range else dragBase - range.toSet()
    }

    /** Two-finger tap at [local] (grid coordinates): like a long press on that tile. */
    fun twoFingerTap(local: Offset) {
        val info = grid.layoutInfo
        val y = local.y + info.viewportStartOffset
        val hit = info.visibleItemsInfo.firstOrNull {
            local.x >= it.offset.x && local.x < it.offset.x + it.size.width &&
                y >= it.offset.y && y < it.offset.y + it.size.height
        } ?: return
        entries.getOrNull(hit.index - 1)?.let { select(it) }
    }

    fun dragSelectEnd() {
        dragAnchor = -1
    }

    fun focusConsumed() = vm.consumeFocusTarget()
}
