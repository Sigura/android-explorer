package com.mediaviewer.ui.root

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.mediaviewer.data.thumbs.Still
import com.mediaviewer.data.thumbs.Thumb
import com.mediaviewer.data.thumbs.Thumbnails
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.browser.Collage
import com.mediaviewer.ui.browser.LocalThumbnails
import com.mediaviewer.ui.Overlay
import com.mediaviewer.ui.common.formatSize
import com.mediaviewer.ui.common.middleEllipsis
import com.mediaviewer.ui.common.volumeIcon
import com.mediaviewer.ui.playback.LocalMiniPlayerHeight

/** README §4 "Root": recent folders, favourites and storage volumes. No back tile. */
@Composable
fun RootScreen(vm: AppViewModel) {
    val volumes by vm.volumes.collectAsState()
    val recent by vm.settingsStore.recent.collectAsState()
    val settings by vm.settings.collectAsState()
    LaunchedEffect(Unit) { vm.refreshVolumes() }
    val pad = WindowInsets.safeDrawing.asPaddingValues()

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 16.dp + LocalMiniPlayerHeight.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Media Viewer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.showOverlay(Overlay.SETTINGS) }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Recent") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(recent.take(settings.recentCount), key = { it.path }) { r ->
                        BigFolderTile(
                            path = r.path,
                            name = r.path.substringAfterLast('/').ifEmpty { r.path },
                            subtitle = r.path.substringBeforeLast('/'),
                            onClick = { vm.openFolder(r.path, focus = r.lastViewed) },
                            onLongClick = { vm.removeRecent(r.path) },
                        )
                    }
                }
            }
        }
        if (settings.favourites.isNotEmpty()) {
            item { SectionTitle("Favourites") }
            items(settings.favourites.sorted(), key = { "f:$it" }) { p ->
                RootRow(Icons.Rounded.Star, p.substringAfterLast('/'), p, onClick = { vm.openFolder(p) }, onLongClick = { vm.toggleFavourite(p) })
            }
        }
        item { SectionTitle("Storage") }
        items(volumes, key = { "v:" + it.path }) { v ->
            RootRow(volumeIcon(v), v.label, v.path, onClick = { vm.openFolder(v.path) }) {
                if (v.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { 1f - v.freeBytes.toFloat() / v.totalBytes },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(
                        "${formatSize(v.freeBytes)} free of ${formatSize(v.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RootRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            extra()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigFolderTile(path: String, name: String, subtitle: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // The same cheap-first collage as folder tiles (README §4.1, §7.2).
    val thumbs = LocalThumbnails.current
    val sizePx = with(LocalDensity.current) { IntSize(152.dp.roundToPx(), 96.dp.roundToPx()) }
    var stills by remember(path) { mutableStateOf<List<Still>>(emptyList()) }
    LaunchedEffect(path) {
        try {
            thumbs.load(FileEntry(path, name, MediaType.FOLDER, 0, 0), sizePx.width, sizePx.height).collect { t ->
                if (t is Thumb.Folder) stills = t.groups[0] ?: thumbs.loadFolderGroup(t, 0, sizePx.width, sizePx.height)
            }
        } catch (e: Thumbnails.DecodeFailed) {
            // Unreadable folder: plain icon.
        }
    }
    Column(
        Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))
            Collage(stills)
        }
        Text(middleEllipsis(name, 24), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
