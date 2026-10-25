package com.mediaviewer.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediaviewer.data.abandonable
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.fs.Volumes
import com.mediaviewer.domain.NaturalOrder
import com.mediaviewer.ui.AppViewModel
import java.io.File

/**
 * Destination picker for "Move/Copy to…" (README §4.6). Starts in the current folder; shortcuts
 * to storage volumes, favourites and recent folders. Choosing a destination is the only step:
 * there is no confirmation dialog afterwards.
 */
@Composable
fun FolderPicker(vm: AppViewModel, start: String, move: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val volumes by vm.volumes.collectAsState()
    val settings by vm.settings.collectAsState()
    val recent by vm.settingsStore.recent.collectAsState()
    var dir by remember { mutableStateOf(start) }
    var subdirs by remember { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(dir) {
        subdirs = null
        subdirs = abandonable(3000) {
            File(dir).listFiles { f -> f.isDirectory && !FolderScanner.isHidden(f.name) }
                ?.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        } ?: emptyList()
    }
    val atVolumeRoot = volumes.any { it.path == dir } || File(dir).parentFile == null
    val shortcuts = remember(volumes, settings.favourites, recent) {
        (volumes.map { it.path to it.label } +
            settings.favourites.map { it to it.substringAfterLast('/') } +
            recent.take(6).map { it.path to it.path.substringAfterLast('/') })
            .distinctBy { it.first }
    }
    val verb = if (move) "Move" else "Copy"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$verb to…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shortcuts, key = { it.first }) { (p, label) ->
                        AssistChip(onClick = { dir = p }, label = { Text(label, maxLines = 1) })
                    }
                }
                val vol = Volumes.rootOf(dir, volumes)
                Text(
                    vol?.let { it.label + dir.removePrefix(it.path) } ?: dir,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (!atVolumeRoot) {
                        item {
                            PickerRow(Icons.Rounded.ArrowUpward, "..") { File(dir).parent?.let { dir = it } }
                        }
                    }
                    val list = subdirs
                    if (list == null) {
                        item { Text("Reading…", Modifier.padding(12.dp)) }
                    } else if (list.isEmpty()) {
                        item { Text("No subfolders", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        items(list, key = { it.path }) { f -> PickerRow(Icons.Rounded.Folder, f.name) { dir = f.path } }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(dir) }, enabled = dir != start) { Text("$verb here") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(name, Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
