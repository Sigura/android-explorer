package com.mediaviewer.ui.root

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediaviewer.data.history.FolderHistory
import com.mediaviewer.domain.ListenStatus
import com.mediaviewer.playback.NowPlaying
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.Overlay
import com.mediaviewer.ui.browser.ListenOverlay
import com.mediaviewer.ui.browser.TilePlayButton
import com.mediaviewer.ui.common.middleEllipsis
import java.io.File

/**
 * *Continue listening* (todo §3.4): the last books, newest first — cover, name, where they stand
 * (the folder tiles' pill and progress line) and ▶. A tap opens the folder at the current file;
 * ▶ plays or continues (a finished book asks *Start over?*), a long press on ▶ opens the player too.
 */
@Composable
internal fun ContinueListeningRow(vm: AppViewModel, books: List<FolderHistory>, now: NowPlaying?) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(books, key = { "c:" + it.folder }) { h ->
            val live = now?.takeIf { it.folder == h.folder }
            val status = if (live != null) {
                ListenStatus(live.index, live.count, live.positionMs, live.durationMs, finished = false, playing = live.playing)
            } else {
                ListenStatus(h.index, h.total, h.positionMs, h.durationMs, h.finished)
            }
            BookCard(
                vm, h, status,
                onOpen = { vm.openFolder(h.folder, focus = h.currentPath) },
                onPlay = { show ->
                    when {
                        live == null -> vm.playBook(h.folder, show)
                        show -> vm.showOverlay(Overlay.PLAYER)
                        else -> vm.bookPlayer.togglePlay()
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(vm: AppViewModel, h: FolderHistory, status: ListenStatus, onOpen: () -> Unit, onPlay: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val px = with(LocalDensity.current) { 152.dp.roundToPx() }
    val cover by produceState<Bitmap?>(null, h.currentPath) { value = vm.bookCover(h, px) }
    val name = File(h.folder).name.ifEmpty { h.folder }
    Column(
        Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClickLabel = "Open the folder", onClick = onOpen)
            .padding(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            val bmp = cover
            if (bmp != null) {
                val image = remember(bmp) { bmp.asImageBitmap() }
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f), modifier = Modifier.fillMaxSize(0.6f))
            }
            ListenOverlay(status)
            TilePlayButton(playing = status.playing, onClick = { onPlay(false) }, onLongClick = { onPlay(true) })
        }
        Text(middleEllipsis(name, 24), style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Text(
            h.currentPath?.let { File(it).nameWithoutExtension } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
