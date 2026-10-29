package com.mediaviewer.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.data.fs.Volume
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.ui.Busy
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

/**
 * Non-modal wait indicator (README §2.1): appears after ~300 ms, shows the elapsed time after 3 s,
 * and has a Stop button. Back / Esc also stop it (handled by the caller).
 */
@Composable
fun WaitIndicator(busy: Busy?, onStop: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(busy?.id) {
        visible = false
        if (busy == null) return@LaunchedEffect
        delay(300)
        visible = true
        while (true) {
            elapsed = android.os.SystemClock.uptimeMillis() - busy.startedAt
            delay(500)
        }
    }
    AnimatedVisibility(visible && busy != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val p = busy?.progress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.width(80.dp))
                    } else {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    }
                    val label = busy?.label.orEmpty()
                    Text(
                        if (elapsed >= 3000) "$label  ${elapsed / 1000} s" else label,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 360.dp),
                    )
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}

/**
 * Top inset for screens with their own top bar: the status bar, or the title bar of a desktop
 * window (DeX, Chromebooks, desktop windowing), which is a caption bar, not a status bar (README §3.9).
 */
@Composable
fun topInset(): Dp = WindowInsets.statusBars.union(WindowInsets.captionBar).asPaddingValues().calculateTopPadding()

/** Icon button with a hand cursor and a tooltip on mouse hover or long press (README §3.9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipIconButton(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tip = rememberTooltipState()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(500)
            tip.show()
        } else {
            tip.dismiss()
        }
    }
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = tip) {
        IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = modifier.pointerHoverIcon(PointerIcon.Hand)) { content() }
    }
}

/**
 * [TipIconButton] that also takes a long press (the folder ▶ button: long press opens the player
 * screen, todo §4.1). [longClickLabel] is read by TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TipIconButton(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    longClickLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tip = rememberTooltipState()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(500)
            tip.show()
        } else {
            tip.dismiss()
        }
    }
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = tip) {
        Box(
            modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    role = Role.Button,
                    onClickLabel = label,
                    onLongClickLabel = longClickLabel,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * A small slider over fixed [steps] with an [icon] in front: the thumbnail size in the folder
 * bar (README §4) and the text size in the editor (todo §6.6). [value] snaps to the nearest step.
 */
@Composable
fun StepSlider(steps: IntArray, value: Int, icon: ImageVector, label: String, width: Dp = 112.dp, onChange: (Int) -> Unit) {
    val idx = steps.indexOfFirst { it >= value }.let { if (it < 0) steps.lastIndex else it }
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(
        value = idx.toFloat(),
        onValueChange = { v ->
            val step = steps[v.roundToInt().coerceIn(0, steps.lastIndex)]
            if (step != value) onChange(step)
        },
        valueRange = 0f..steps.lastIndex.toFloat(),
        steps = steps.size - 2,
        modifier = Modifier.width(width).padding(horizontal = 6.dp).semantics { contentDescription = label },
    )
}

/** One step up ([dir] > 0) or down from [current] in [steps]. */
fun stepIn(steps: IntArray, current: Int, dir: Int): Int {
    val i = steps.indexOfFirst { it >= current }.let { if (it < 0) steps.size - 1 else it }
    return steps[(i + dir).coerceIn(0, steps.size - 1)]
}

/** Icon for a storage root; `null` is the file-system root. */
fun volumeIcon(v: Volume?): ImageVector = when {
    v == null -> Icons.Rounded.Storage
    v.device -> Icons.Rounded.PhotoCamera
    !v.removable -> Icons.Rounded.PhoneAndroid
    v.label.contains("USB", ignoreCase = true) -> Icons.Rounded.Usb
    else -> Icons.Rounded.SdCard
}

/** Middle ellipsis so the extension stays visible (README §3, "File names"). */
fun middleEllipsis(name: String, max: Int): String {
    if (name.length <= max || max < 8) return name
    val dot = name.lastIndexOf('.')
    val tail = if (dot > 0 && name.length - dot <= 8) name.length - dot + 4 else max / 3
    val head = max - tail - 1
    return name.take(head) + "…" + name.takeLast(tail)
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

fun formatDuration(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun shareFiles(context: Context, entries: List<FileEntry>) {
    val uris = entries.mapNotNull { uriFor(context, it.path) }
    if (uris.isEmpty()) return
    val mime = entries.map { FileTypes.mimeType(it.name, it.type) }.distinct().singleOrNull() ?: "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.apply {
        type = mime
        clipData = ClipData.newRawUri(null, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: ActivityNotFoundException) {
        // Nothing can receive it; ignore.
    } catch (e: RuntimeException) {
        // Thousands of files overflow the Binder transaction limit (TransactionTooLargeException).
        Log.w(TAG, "Can't share ${entries.size} files", e)
        Toast.makeText(context, "Too many files to share at once", Toast.LENGTH_SHORT).show()
    }
}

/** Null (and logged) for a path the provider can't resolve; that file is left out. */
private fun uriFor(context: Context, path: String): Uri? = try {
    FileProvider.getUriForFile(context, context.packageName + ".files", File(path))
} catch (e: IllegalArgumentException) {
    Log.w(TAG, "No content URI for $path", e)
    null
}

/** `Ctrl` + C: the files go to the clipboard as content URIs, for pasting into another app (README §2, "Desktops"). */
fun copyFiles(context: Context, entries: List<FileEntry>) {
    val files = entries.filter { !it.isFolder }
    if (files.isEmpty()) return
    val uris = files.mapNotNull { uriFor(context, it.path) }
    if (uris.isEmpty()) return
    val clip = ClipData.newUri(context.contentResolver, files[0].name, uris[0])
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    try {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Can't copy ${files.size} files", e)
        Toast.makeText(context, "Too many files to copy at once", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Starts a system drag of [entries] that other apps can accept, e.g. My Files or a mail app next
 * to this window in DeX (README §3.9). Must be called while the mouse button is still down.
 */
fun startFileDrag(view: View, entries: List<FileEntry>) {
    val files = entries.filter { !it.isFolder }
    if (files.isEmpty()) return
    val uris = files.mapNotNull { uriFor(view.context, it.path) }
    if (uris.isEmpty()) return
    val mimes = files.map { FileTypes.mimeType(it.name, it.type) }.distinct().toTypedArray()
    val clip = ClipData(ClipDescription(files[0].name, mimes), ClipData.Item(uris[0]))
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    val label = if (files.size == 1) middleEllipsis(files[0].name, 24) else "${files.size} files"
    try {
        view.startDragAndDrop(clip, LabelShadow(view.resources.displayMetrics.density, label), null, View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Can't drag ${files.size} files", e)
    }
}

/** Drag shadow: a small dark label with the file name or count. */
private class LabelShadow(density: Float, private val label: String) : View.DragShadowBuilder() {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 14 * density
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0303030.toInt() }
    private val pad = 12 * density
    private val w = (text.measureText(label) + 2 * pad).toInt()
    private val h = (text.descent() - text.ascent() + 2 * pad).toInt()

    override fun onProvideShadowMetrics(size: Point, touch: Point) {
        size.set(w, h)
        touch.set(w / 2, h)
    }

    override fun onDrawShadow(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), pad, pad, bg)
        canvas.drawText(label, pad, pad - text.ascent(), text)
    }
}
