package com.mediaviewer.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.domain.ListenLabel
import com.mediaviewer.domain.ListenStatus

private val PillStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
private val IconSize = 14.dp
private val PadH = 5.dp
private val Gap = 3.dp

/**
 * Where a book stands, over its folder tile's collage (todo §10.1): a pill with an icon (playing,
 * started, finished) and the longest label variant that fits, and a progress line along the bottom.
 */
@Composable
internal fun BoxScope.ListenOverlay(s: ListenStatus) {
    val variants = remember(s) { ListenLabel.variants(s) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(6.dp)) {
        val room = with(density) { (maxWidth - IconSize - PadH * 2 - Gap).toPx() }
        // Shortest first: keep the last one that fits; the short one always shows.
        val label = remember(variants, room) {
            variants.lastOrNull { it.isEmpty() || measurer.measure(it, PillStyle, maxLines = 1).size.width <= room } ?: variants.first()
        }
        val icon = when {
            s.finished -> Icons.Rounded.CheckCircle
            s.playing -> Icons.Rounded.GraphicEq
            else -> Icons.Rounded.Headphones
        }
        // The longest variant for TalkBack, whatever fits on screen.
        val spoken = (if (s.playing) "Playing, " else if (s.finished) "" else "Listening, ") + variants.last().ifEmpty { "finished" }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (s.playing) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = PadH, vertical = 2.dp)
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(IconSize))
            if (label.isNotEmpty()) {
                Spacer(Modifier.width(Gap))
                Text(label, style = PillStyle, maxLines = 1, softWrap = false)
            }
        }
    }
    ListenProgress(ListenLabel.progress(s) ?: return)
}

@Composable
private fun BoxScope.ListenProgress(fraction: Float) {
    val track = Color.Black.copy(alpha = 0.45f)
    val bar = MaterialTheme.colorScheme.primary
    Spacer(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .drawBehind {
                drawRect(track)
                drawRect(bar, Offset.Zero, Size(size.width * fraction, size.height))
            },
    )
}
