package com.mediaviewer.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mediaviewer.data.fs.TextFile
import com.mediaviewer.domain.Highlight
import com.mediaviewer.domain.Language
import com.mediaviewer.domain.TextSearch
import com.mediaviewer.ui.common.TipIconButton

/**
 * The find bar under the editor's top bar (todo §10.6): query, `3 / 17`, previous / next,
 * *Match case* and close; ⌄ (or `Ctrl` + H) shows the replace row. `Enter` / `Shift` + `Enter`
 * step through the matches, `Esc` closes.
 */
@Composable
internal fun FindBar(state: EditorState, focus: FocusRequester) {
    // The field keeps its own selection; the text itself lives in the state (the screen searches it).
    var field by remember { mutableStateOf(TextFieldValue(state.query, TextRange(0, state.query.length))) }
    if (field.text != state.query) field = TextFieldValue(state.query, TextRange(state.query.length))
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: IllegalStateException) {
        }
    }
    val count = state.matches.size
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.height(44.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.doc.readOnly) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                } else {
                    TipIconButton(if (state.replaceOpen) "Hide replace" else "Replace (Ctrl+H)", onClick = { state.replaceOpen = !state.replaceOpen }) {
                        Icon(
                            if (state.replaceOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (state.replaceOpen) "Hide replace" else "Show replace",
                        )
                    }
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        state.query = it.text
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { state.findNext() }),
                    decorationBox = { inner ->
                        if (field.text.isEmpty()) Text("Find", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        inner()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.Enter, Key.NumPadEnter -> state.findNext(forward = !ev.isShiftPressed).let { true }
                                else -> false
                            }
                        },
                )
                Text(
                    when {
                        state.query.isEmpty() -> ""
                        count == 0 -> "No matches"
                        count >= TextSearch.LIMIT -> "${state.current + 1} / ${TextSearch.LIMIT}+"
                        else -> "${state.current + 1} / $count"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.query.isNotEmpty() && count == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 4.dp),
                )
                FilterChip(
                    selected = state.matchCase,
                    onClick = { state.matchCase = !state.matchCase },
                    label = { Text("Aa") },
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                TipIconButton("Previous (Shift+Enter, Shift+F3)", onClick = { state.findNext(forward = false) }, enabled = count > 0) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous match")
                }
                TipIconButton("Next (Enter, F3)", onClick = { state.findNext() }, enabled = count > 0) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next match")
                }
                TipIconButton("Close (Esc)", onClick = state::closeFind) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close find")
                }
            }
            if (state.replaceOpen && !state.doc.readOnly) ReplaceRow(state, count)
            HorizontalDivider()
        }
    }
}

/** Replace with: the current match (then on to the next), or all of them as one undo step. */
@Composable
private fun ReplaceRow(state: EditorState, count: Int) {
    Row(Modifier.height(44.dp).padding(start = 52.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = state.replacement,
            onValueChange = { state.replacement = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { state.replaceCurrent() }),
            decorationBox = { inner ->
                if (state.replacement.isEmpty()) Text("Replace with", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                inner()
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) state.replaceCurrent().let { true } else false
                },
        )
        TextButton(onClick = state::replaceCurrent, enabled = count > 0) { Text("Replace") }
        TextButton(onClick = state::replaceAll, enabled = count > 0) { Text("All") }
    }
}

/**
 * Rename from the editor (todo §10.6): the name is selected up to its extension; the same name
 * rules as a new file; never overwrites. A line under the field says what the new name means.
 */
@Composable
internal fun RenameDialog(state: EditorState) {
    val old = state.doc.name
    val dot = old.lastIndexOf('.').takeIf { it > 0 } ?: old.length
    var raw by remember { mutableStateOf(TextFieldValue(old, TextRange(0, dot))) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val name = TextFile.cleanName(raw.text)
    val problem = TextFile.nameProblem(name)
    val shown = error ?: problem
    val close = { state.renaming = false }

    fun submit() {
        if (problem != null || working) return
        if (name == old) return close()
        working = true
        state.rename(name) { why ->
            working = false
            error = why
        }
    }

    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: IllegalStateException) {
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(
                    value = raw,
                    onValueChange = {
                        raw = it
                        error = null
                    },
                    singleLine = true,
                    isError = shown != null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                val lang = Highlight.language(name)
                Text(
                    shown ?: if (lang != state.language) {
                        if (lang == Language.PLAIN) "Plain text, no colours" else "${lang.label} — syntax colours"
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (shown != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = problem == null && !working) { Text("Rename") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}
