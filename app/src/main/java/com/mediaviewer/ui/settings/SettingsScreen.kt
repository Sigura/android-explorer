package com.mediaviewer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mediaviewer.data.settings.AppSettings
import com.mediaviewer.data.settings.MouseMode
import com.mediaviewer.data.settings.RestoreMode
import com.mediaviewer.data.settings.SettingsStore
import com.mediaviewer.data.settings.ThemeMode
import com.mediaviewer.data.settings.Transition
import com.mediaviewer.data.thumbs.TextDecode
import com.mediaviewer.domain.CropMode
import com.mediaviewer.domain.SortBy
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.Overlay
import com.mediaviewer.ui.editor.EDITOR_TEXT_STEPS
import java.text.DateFormat
import java.util.Date

/** README §9. Every change is stored at once; there is no Save button. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    fun up(t: (AppSettings) -> AppSettings) = vm.settingsStore.update(t)
    val pad = WindowInsets.safeDrawing.asPaddingValues()

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showOverlay(null) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
        }
        item { Header("Slideshow") }
        item {
            SliderRow("Interval", s.intervalSec, 1..40, "${s.intervalSec} s") { v -> up { it.copy(intervalSec = v) } }
        }
        item { Choice("Transition", Transition.entries, s.transition, { it.name.lowercase() }) { v -> up { it.copy(transition = v) } } }
        item { SwitchRow("Invert swipe direction", "Swipe left = next, like most galleries", s.invertSwipe) { v -> up { it.copy(invertSwipe = v) } } }
        item { SwitchRow("Random order", null, s.random) { v -> up { it.copy(random = v) } } }
        item { SwitchRow("Video muted by default", null, s.muted) { v -> up { it.copy(muted = v) } } }
        item {
            Choice("Restore last session on launch", RestoreMode.entries, s.restore, {
                when (it) {
                    RestoreMode.OFF -> "off"
                    RestoreMode.FOLDER -> "folder only"
                    RestoreMode.FOLDER_AND_SLIDESHOW -> "folder and slideshow"
                }
            }) { v -> up { it.copy(restore = v) } }
        }

        item { Header("Folder view") }
        item {
            SwitchRow("Automatic thumbnail size", "Picked from the window width until you change it", s.tileAuto) { v -> up { it.copy(tileAuto = v) } }
        }
        if (!s.tileAuto) {
            item {
                SliderRow("Thumbnail size", s.tileDp, 72..480, "${s.tileDp} dp", step = 8) { v -> up { it.copy(tileDp = v) } }
            }
        }
        item { Choice("Image tile mode", CropMode.entries, s.cropMode, { it.name.lowercase() }) { v -> up { it.copy(cropMode = v) } } }
        if (s.cropMode == CropMode.SMART) {
            item {
                SliderRow("Smart crop threshold", s.cropThresholdPct, 0..30, "${s.cropThresholdPct} %") { v -> up { it.copy(cropThresholdPct = v) } }
            }
        }
        item {
            SliderRow("Large-GIF threshold", s.largeGifMb, 5..200, "${s.largeGifMb} MB", step = 5) { v -> up { it.copy(largeGifMb = v) } }
        }
        item {
            SliderRow(
                "Long videos open externally",
                s.longVideoMin,
                0..30,
                if (s.longVideoMin == 0) "off" else "> ${s.longVideoMin} min",
            ) { v -> up { it.copy(longVideoMin = v) } }
        }
        item {
            Choice("Text files without encoding", TEXT_CHARSETS, s.textCharset, { if (it.isEmpty()) "auto (${TextDecode.defaultLegacyCharset()})" else it }) { v ->
                up { it.copy(textCharset = v) }
            }
        }
        item { Choice("Sort by", SortBy.entries, s.sort.by, { it.name.lowercase() }) { v -> up { it.copy(sort = it.sort.copy(by = v)) } } }
        item { SwitchRow("Descending", null, s.sort.descending) { v -> up { it.copy(sort = it.sort.copy(descending = v)) } } }
        item { SwitchRow("Folders first", null, s.sort.foldersFirst) { v -> up { it.copy(sort = it.sort.copy(foldersFirst = v)) } } }
        item { SwitchRow("Show hidden files", "Dot files and folders with .nomedia", s.showHidden) { v -> up { it.copy(showHidden = v) } } }
        item {
            Choice("Mouse clicks", MouseMode.entries, s.mouseMode, {
                when (it) {
                    MouseMode.AUTO -> "auto (desktop in DeX)"
                    MouseMode.GALLERY -> "gallery: click opens"
                    MouseMode.DESKTOP -> "desktop: double-click opens"
                }
            }) { v -> up { it.copy(mouseMode = v) } }
        }
        item { SwitchRow("Always show file names", "Otherwise names show on focus, hover and in selection mode", s.showNames) { v -> up { it.copy(showNames = v) } } }

        item { Header("Editor") }
        item {
            SwitchRow("Open text files in the app", "Otherwise they open with other apps; Edit stays in the context menu", s.textInApp) { v ->
                up { it.copy(textInApp = v) }
            }
        }
        item {
            SliderRow("Text size", s.editorTextSp, EDITOR_TEXT_STEPS.first()..EDITOR_TEXT_STEPS.last(), "${s.editorTextSp} sp", step = 2) { v ->
                up { it.copy(editorTextSp = v) }
            }
        }
        item { SwitchRow("Wrap lines", "Off: long lines scroll sideways", s.editorWrap) { v -> up { it.copy(editorWrap = v) } } }
        item {
            SwitchRow("JetBrains Mono for code", "Code, logs and tables in the editor and on tiles. Off: the system's monospaced font", s.codeFontBundled) { v ->
                up { it.copy(codeFontBundled = v) }
            }
        }

        item { Header("Listening") }
        item {
            SliderRow("Skip in books", s.skipBookSec, 5..60, "${s.skipBookSec} s", step = 5) { v -> up { it.copy(skipBookSec = v) } }
        }
        item {
            SliderRow("Skip in music (shuffle)", s.skipMusicSec, 5..60, "${s.skipMusicSec} s", step = 5) { v -> up { it.copy(skipMusicSec = v) } }
        }
        item {
            SwitchRow("Skip silence", "Leaves out silent stretches; also in the speed dialog", s.skipSilence) { v -> up { it.copy(skipSilence = v) } }
        }

        item { Header("Home screen") }
        item {
            SliderRow("Recent folders", s.recentCount, 1..SettingsStore.MAX_RECENT, "${s.recentCount}") { v -> up { it.copy(recentCount = v) } }
        }

        item { Header("Appearance") }
        item {
            Choice("Theme", ThemeMode.entries, s.theme, { if (it == ThemeMode.AMOLED) "AMOLED black" else it.name.lowercase() }) { v ->
                up { it.copy(theme = v) }
            }
        }

        item { Header("Maintenance") }
        item {
            ActionRow("Clear thumbnail cache", "Frees memory; thumbnails are rebuilt when shown") { vm.thumbnails.clearMemory() }
        }
        item { ActionRow("Error log", "Files that couldn't be read") { vm.showOverlay(Overlay.ERROR_LOG) } }
        if (s.favourites.isNotEmpty()) {
            item { Header("Favourite folders") }
            items(s.favourites.sorted()) { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { vm.toggleFavourite(p) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Legacy 8-bit charsets for text previews (README §3.10); "" = by device language. */
private val TEXT_CHARSETS = listOf("", "windows-1251", "koi8-r", "ibm866", "windows-1252", "windows-1250", "iso-8859-2", "windows-1253", "windows-1254")

@Composable
private fun SliderRow(title: String, value: Int, range: IntRange, label: String, step: Int = 1, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(title, Modifier.weight(1f))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val steps = (range.last - range.first) / step - 1
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> onChange((Math.round((v - range.first) / step) * step + range.first).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps.coerceAtLeast(0),
        )
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** README §2.1: path, reason and time of the last 200 ignored errors. */
@Composable
fun ErrorLogScreen(vm: AppViewModel) {
    val log by vm.errors.log.collectAsState()
    val pad = WindowInsets.safeDrawing.asPaddingValues()
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showOverlay(null) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Error log", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.errors.clear() }) { Text("Clear") }
            }
        }
        if (log.isEmpty()) {
            item { Text("No errors", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(log.asReversed()) { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(r.reason, style = MaterialTheme.typography.bodyMedium)
                if (r.path.isNotEmpty()) Text(r.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmt.format(Date(r.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
