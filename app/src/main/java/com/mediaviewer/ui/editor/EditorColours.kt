package com.mediaviewer.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.domain.EditorTheme
import com.mediaviewer.domain.EditorThemes
import com.mediaviewer.domain.Highlight
import com.mediaviewer.domain.Language
import com.mediaviewer.domain.Role

/** An [EditorTheme] as Compose colours and span styles (todo §10.8). */
@Immutable
class EditorColours(val theme: EditorTheme, val background: Color, val text: Color, val cursor: Color, val selection: TextSelectionColors?) {
    /** One style per [Role]: the theme's colour plus bold / italic, as before themes. */
    val styles: Array<SpanStyle> = Role.entries.map { role ->
        val c = Color(theme.colour(role))
        when (role) {
            Role.COMMENT -> SpanStyle(color = c, fontStyle = FontStyle.Italic)
            Role.HEADING -> SpanStyle(color = c, fontWeight = FontWeight.Bold)
            Role.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
            Role.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
            else -> SpanStyle(color = c)
        }
    }.toTypedArray()

    /** Find matches; the current one stronger. */
    val found = SpanStyle(background = Color(theme.match)) to
        SpanStyle(background = Color(theme.currentMatch), color = Color(theme.currentMatchText))

    val bracket = SpanStyle(background = Color(theme.bracket))
    val currentLine = Color(theme.currentLine)
    val gutter = Color(theme.gutter)
}

/**
 * The editor's colours for the [id] setting. *Default* keeps today's look: the app's background,
 * text and accent colours (dynamic on Android 12+), with the palette of its day / night.
 */
@Composable
fun rememberEditorColours(id: String): EditorColours {
    val scheme = MaterialTheme.colorScheme
    val appDark = scheme.background.luminance() < 0.5f
    val theme = EditorThemes.resolve(id, appDark)
    return remember(theme, scheme.background, scheme.onBackground, scheme.primary) {
        if (theme.followsApp) {
            EditorColours(theme, scheme.background, scheme.onBackground, scheme.primary, null)
        } else {
            val cursor = Color(theme.cursor)
            EditorColours(theme, Color(theme.background), Color(theme.text), cursor, TextSelectionColors(cursor, Color(theme.selection)))
        }
    }
}

/** The theme name for settings; *Default* says what it follows. */
fun editorThemeName(id: String): String =
    EditorThemes.CHOICES.firstOrNull { it.id == id && !it.followsApp }?.name ?: "Default (app day / night)"

private const val SAMPLE = "// Say hello\nfun greet(name: String) =\n    println(\"Hi, \$name! \" + 42)"

/** Editor ⋮ → *Theme…* and Settings → Editor: every theme with a coloured three-line sample. */
@Composable
fun EditorThemeDialog(selected: String, code: FontFamily, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val sample = remember { Highlight.spans(SAMPLE, Language.KOTLIN) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editor theme") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(EditorThemes.CHOICES, key = { it.id }) { t ->
                    val id = if (t.followsApp) "" else t.id
                    val chosen = EditorThemes.resolve(selected, false).id == t.id
                    val colours = rememberEditorColours(id)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(id) }
                            .padding(vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen, onClick = { onSelect(id) })
                            Text(if (t.followsApp) editorThemeName("") else t.name)
                        }
                        val styled = remember(colours) {
                            val b = AnnotatedString.Builder(SAMPLE)
                            for (i in 0 until sample.size) b.addStyle(colours.styles[sample.role(i).ordinal], sample.start(i), sample.end(i))
                            b.toAnnotatedString()
                        }
                        Text(
                            styled,
                            style = TextStyle(fontFamily = code, fontSize = 12.sp, color = colours.text),
                            softWrap = false,
                            maxLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(colours.background)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
