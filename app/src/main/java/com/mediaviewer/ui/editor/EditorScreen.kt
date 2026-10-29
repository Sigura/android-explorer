package com.mediaviewer.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.fs.TextFile
import com.mediaviewer.domain.Highlight
import com.mediaviewer.domain.Language
import com.mediaviewer.domain.Role
import com.mediaviewer.domain.Spans
import com.mediaviewer.domain.TextSearch
import com.mediaviewer.domain.model.OtherKind
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.Screen
import com.mediaviewer.ui.common.CodeFonts
import com.mediaviewer.ui.common.StepSlider
import com.mediaviewer.ui.common.TipIconButton
import com.mediaviewer.ui.common.formatSize
import com.mediaviewer.ui.common.middleEllipsis
import com.mediaviewer.ui.common.stepIn
import com.mediaviewer.ui.common.topInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

/** Editor text sizes in sp (todo §6.6), for the slider, pinch, `Ctrl` + wheel and `Ctrl` + `+` / `-`. */
val EDITOR_TEXT_STEPS = intArrayOf(10, 12, 14, 16, 18, 20, 22, 24, 26, 28)

/** Text editor (todo §6): edit a text file, with syntax colours from its name. */
@OptIn(FlowPreview::class)
@Composable
fun EditorScreen(vm: AppViewModel, screen: Screen.Editor) {
    val settings by vm.settings.collectAsState()
    val busy by vm.busy.collectAsState()
    // Kept in the view model: unsaved text survives the activity being recreated (font size, language).
    val state = remember {
        vm.editorState?.takeIf { it.doc.path == screen.doc.path } ?: EditorState(vm, screen).also { vm.editorState = it }
    }
    val rootFocus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        state.announceDraft()
        // Keys (Ctrl+S, Esc) work at once; the on-screen keyboard waits for a tap in the text.
        try {
            rootFocus.requestFocus()
        } catch (e: IllegalStateException) {
        }
    }
    BackHandler { if (busy != null) vm.stopBusy() else state.close() }

    // Colours: lexed off the main thread once typing pauses; edits in between move the old ones.
    LaunchedEffect(Unit) {
        snapshotFlow { state.value.text to state.language }.debounce(150).collectLatest { (t, lang) ->
            if (lang == Language.PLAIN || t.length > TextFile.COLOUR_LIMIT) {
                state.spans = Spans.EMPTY
                return@collectLatest
            }
            val s = withContext(Dispatchers.Default) { Highlight.spans(t, lang) }
            if (state.value.text == t && state.language == lang) state.spans = s
        }
    }

    // Find (todo §10.6): matches for the text and query, off the main thread once typing pauses.
    LaunchedEffect(Unit) {
        snapshotFlow { if (state.findOpen) Triple(state.doc.text.takeIf { state.doc.readOnly } ?: state.value.text, state.query, state.matchCase) else null }
            .debounce(100)
            .collectLatest { f ->
                if (f == null) return@collectLatest
                val (t, q, case) = f
                val found = withContext(Dispatchers.Default) { TextSearch.find(t, q, case) }
                state.setMatches(found, t, q)
            }
    }

    // Unsaved text survives the app being killed in the background (todo §6.3).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) state.keepDraft() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun zoom(dir: Int) = vm.settingsStore.update { it.copy(editorTextSp = stepIn(EDITOR_TEXT_STEPS, it.editorTextSp, dir)) }
    val setTextSize: (Int) -> Unit = { sp -> vm.settingsStore.update { it.copy(editorTextSp = sp) } }
    val setWrap: (Boolean) -> Unit = { w -> vm.settingsStore.update { it.copy(editorWrap = w) } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    ev.isCtrlPressed && ev.key == Key.S -> state.save().let { true }
                    ev.isCtrlPressed && ev.key == Key.Z && ev.isShiftPressed -> state.redo().let { true }
                    ev.isCtrlPressed && ev.key == Key.Z -> state.undo().let { true }
                    ev.isCtrlPressed && ev.key == Key.Y -> state.redo().let { true }
                    ev.isCtrlPressed && (ev.key == Key.Equals || ev.key == Key.Plus || ev.key == Key.NumPadAdd) -> zoom(1).let { true }
                    ev.isCtrlPressed && (ev.key == Key.Minus || ev.key == Key.NumPadSubtract) -> zoom(-1).let { true }
                    ev.isCtrlPressed && ev.key == Key.F -> {
                        state.openFind()
                        try {
                            findFocus.requestFocus()
                        } catch (e: IllegalStateException) {
                        }
                        true
                    }
                    ev.key == Key.F3 || (ev.isCtrlPressed && ev.key == Key.G) -> state.findNext(forward = !ev.isShiftPressed).let { true }
                    ev.key == Key.F2 && busy == null && !state.doc.readOnly -> {
                        state.renaming = true
                        true
                    }
                    ev.key == Key.Escape && state.findOpen -> {
                        state.closeFind()
                        try {
                            rootFocus.requestFocus()
                        } catch (e: IllegalStateException) {
                        }
                        true
                    }
                    ev.key == Key.Escape && busy == null -> state.close().let { true }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        val compact = maxWidth < 600.dp
        Column(Modifier.fillMaxSize()) {
            EditorBar(state, compact, settings.editorTextSp, settings.editorWrap, setTextSize, setWrap)
            if (state.findOpen) FindBar(state, findFocus)
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .textSizeGestures(::zoom),
            ) {
                val code = CodeFonts.family(settings.codeFontBundled)
                if (state.doc.readOnly) ReadOnlyText(state, settings.editorTextSp, code) else EditText(state, settings.editorTextSp, settings.editorWrap, code)
            }
        }
        Dialogs(state)
        if (state.renaming) RenameDialog(state)
    }
}

@Composable
private fun textStyle(state: EditorState, sp: Int, code: FontFamily): TextStyle {
    val name = state.doc.name
    val kind = FileTypes.byExtension(name)?.other ?: OtherKind.TEXT
    // The same rule as the previews (README §3.10): code, logs and tables in monospace.
    val mono = state.language !in setOf(Language.PLAIN, Language.MARKDOWN) || FileTypes.monospace(name, kind)
    return TextStyle(
        fontFamily = if (mono) code else FontFamily.Default,
        fontSize = sp.sp,
        lineHeight = 1.35.em,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun EditText(state: EditorState, sp: Int, wrap: Boolean, code: FontFamily) {
    val styles = rememberStyles()
    val found = rememberFoundStyles()
    val marks = if (state.findOpen) state.matches else NO_MATCHES
    val transformation = remember(state.spans, styles, marks, state.current, state.query.length) {
        SpanTransformation(state.spans, styles, Marks(marks, state.query.length, state.current, found))
    }
    val prose = state.language == Language.PLAIN || state.language == Language.MARKDOWN
    val style = textStyle(state, sp, code)
    val field = @Composable { modifier: Modifier ->
        BasicTextField(
            value = state.value,
            onValueChange = state::onValueChange,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            // Prose gets the keyboard's help; code doesn't want "corrected" words.
            keyboardOptions = KeyboardOptions(
                capitalization = if (prose) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                autoCorrectEnabled = prose,
            ),
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    if (wrap) {
        field(Modifier.fillMaxSize())
    } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                field(Modifier.widthIn(min = width).fillMaxHeight())
            }
        }
    }
}

/** Files from 1 to 10 MB: shown line by line, which stays fast where a text field would not. */
@Composable
private fun ReadOnlyText(state: EditorState, sp: Int, code: FontFamily) {
    val style = textStyle(state, sp, code)
    val lines by produceState<List<String>>(emptyList(), state.doc) {
        value = withContext(Dispatchers.Default) { state.doc.text.split('\n') }
    }
    val starts = remember(lines) { TextSearch.lineStarts(state.doc.text) }
    val list = rememberLazyListState()
    // Find: the list scrolls to the current match's line, a few lines below the top.
    LaunchedEffect(state.jumps) {
        val m = state.matches.getOrNull(state.current) ?: return@LaunchedEffect
        if (lines.isNotEmpty()) list.scrollToItem((TextSearch.lineOf(starts, m) - 3).coerceAtLeast(0))
    }
    val found = rememberFoundStyles()
    val marks = Marks(if (state.findOpen) state.matches else NO_MATCHES, state.query.length, state.current, found)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        itemsIndexed(lines) { i, line ->
            if (marks.starts.isEmpty() || i >= starts.size) {
                Text(line, style = style)
            } else {
                val b = AnnotatedString.Builder(line)
                marks.addTo(b, starts[i], starts[i] + line.length)
                Text(b.toAnnotatedString(), style = style)
            }
        }
    }
}

@Composable
private fun EditorBar(
    state: EditorState,
    compact: Boolean,
    textSp: Int,
    wrap: Boolean,
    onTextSize: (Int) -> Unit,
    onWrap: (Boolean) -> Unit,
) {
    val doc = state.doc
    val readOnly = doc.readOnly
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(top = topInset()).height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TipIconButton("Back (Esc)", onClick = state::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !readOnly, onClickLabel = "Rename") { state.renaming = true }
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    middleEllipsis(doc.name, 48) + if (state.dirty) " •" else "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    info(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (readOnly) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "Read-only",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).size(20.dp),
                )
            } else {
                TipIconButton("Undo (Ctrl+Z)", onClick = { state.undo() }, enabled = state.canUndo) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo")
                }
                if (!compact) {
                    TipIconButton("Redo (Ctrl+Y)", onClick = { state.redo() }, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo")
                    }
                }
            }
            TipIconButton("Find (Ctrl+F)", onClick = { if (state.findOpen) state.closeFind() else state.openFind() }) {
                Icon(Icons.Rounded.Search, contentDescription = "Find")
            }
            if (!compact) StepSlider(EDITOR_TEXT_STEPS, textSp, Icons.Rounded.FormatSize, "Text size", onChange = onTextSize)
            if (!readOnly) {
                TipIconButton("Save (Ctrl+S)", onClick = { state.save() }, enabled = state.dirty) {
                    Icon(Icons.Rounded.Save, contentDescription = "Save", tint = if (state.dirty) MaterialTheme.colorScheme.primary else Color.Unspecified)
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                TipIconButton("More", onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (compact && !readOnly) {
                        DropdownMenuItem(
                            text = { Text("Redo") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = null) },
                            enabled = state.canRedo,
                            onClick = { state.redo() },
                        )
                    }
                    if (compact) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StepSlider(EDITOR_TEXT_STEPS, textSp, Icons.Rounded.FormatSize, "Text size", width = 200.dp, onChange = onTextSize)
                        }
                    }
                    if (!readOnly) {
                        DropdownMenuItem(
                            text = { Text("Wrap lines") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.WrapText, contentDescription = null) },
                            trailingIcon = { Checkbox(checked = wrap, onCheckedChange = null) },
                            onClick = { onWrap(!wrap) },
                        )
                    }
                    if (!readOnly) {
                        DropdownMenuItem(
                            text = { Text("Rename…") },
                            leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
                            onClick = {
                                menu = false
                                state.renaming = true
                            },
                        )
                    }
                    if (!readOnly && doc.charset != Charsets.UTF_8) {
                        DropdownMenuItem(
                            text = { Text("Save as UTF-8") },
                            onClick = {
                                menu = false
                                state.saveAsUtf8()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** `Kotlin · UTF-8 · CRLF`, plus why the file can't be edited. */
private fun info(state: EditorState): String {
    val d = state.doc
    val parts = ArrayList<String>()
    if (d.readOnly) parts.add(if (d.size > TextFile.EDIT_LIMIT) "Read-only, ${formatSize(d.size)}" else "Read-only")
    parts.add(state.language.label)
    parts.add(d.charset.name().uppercase().replace("WINDOWS", "Windows") + if (d.bom) " BOM" else "")
    if (d.crlf) parts.add("CRLF")
    return parts.joinToString(" · ")
}

@Composable
private fun Dialogs(state: EditorState) {
    val name = state.doc.name
    when (state.dialog) {
        EditorDialog.UNSAVED -> AlertDialog(
            onDismissRequest = state::dismissDialog,
            title = { Text("Save changes to $name?") },
            confirmButton = { TextButton(onClick = state::saveAndClose) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = state::discardAndClose) { Text("Discard") }
                    TextButton(onClick = state::dismissDialog) { Text("Cancel") }
                }
            },
        )
        EditorDialog.CHANGED_ON_DISK -> AlertDialog(
            onDismissRequest = state::dismissDialog,
            title = { Text("$name changed on disk") },
            text = { Text("Another app changed the file after you opened it.") },
            confirmButton = { TextButton(onClick = state::overwrite) { Text("Overwrite") } },
            dismissButton = {
                Row {
                    TextButton(onClick = state::reload) { Text("Reload") }
                    TextButton(onClick = state::dismissDialog) { Text("Cancel") }
                }
            },
        )
        EditorDialog.UNENCODABLE -> AlertDialog(
            onDismissRequest = state::dismissDialog,
            title = { Text("Can't save in ${state.doc.charset.name()}") },
            text = { Text("Some characters don't exist in this encoding. Save the file as UTF-8 instead?") },
            confirmButton = { TextButton(onClick = state::saveAsUtf8) { Text("Save as UTF-8") } },
            dismissButton = { TextButton(onClick = state::dismissDialog) { Text("Cancel") } },
        )
        null -> Unit
    }
}

// ---------------------------------------------------------------------- colours

/** Applies the lexer's spans as styles; the text itself is unchanged, so offsets map 1:1. */
private class SpanTransformation(
    private val spans: Spans,
    private val styles: Array<SpanStyle>,
    private val marks: Marks,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.size == 0 && marks.starts.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val n = text.length
        val b = AnnotatedString.Builder(text)
        for (i in 0 until spans.size) {
            val s = spans.start(i)
            val e = minOf(spans.end(i), n)
            if (s < e) b.addStyle(styles[spans.role(i).ordinal], s, e)
        }
        marks.addTo(b, 0, n)
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

private val NO_MATCHES = IntArray(0)

/** Find matches (todo §10.6) as background colours; the current one stronger. */
private class Marks(val starts: IntArray, val length: Int, val current: Int, val styles: Pair<SpanStyle, SpanStyle>) {
    /** Marks the matches inside [from, to) of the text, shifted so that [from] is offset 0. */
    fun addTo(b: AnnotatedString.Builder, from: Int, to: Int) {
        if (length == 0 || starts.isEmpty()) return
        var i = starts.binarySearch(from - length + 1).let { if (it >= 0) it else -it - 1 }
        while (i < starts.size && starts[i] < to) {
            val s = maxOf(starts[i], from)
            val e = minOf(starts[i] + length, to)
            if (s < e) b.addStyle(if (i == current) styles.second else styles.first, s - from, e - from)
            i++
        }
    }
}

@Composable
private fun rememberFoundStyles(): Pair<SpanStyle, SpanStyle> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) {
        if (dark) SpanStyle(background = Color(0x664D6A2A)) to SpanStyle(background = Color(0xFF8A6D00), color = Color.White)
        else SpanStyle(background = Color(0x66FFE082)) to SpanStyle(background = Color(0xFFFFB300), color = Color.Black)
    }
}

/** Six colours plus bold and italic, in a light and a dark variant (todo §6.5). */
@Composable
private fun rememberStyles(): Array<SpanStyle> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) {
        fun c(light: Long, night: Long) = Color(if (dark) night else light)
        val keyword = c(0xFF8E24AA, 0xFFC792EA)
        val type = c(0xFF1565C0, 0xFF82AAFF)
        Role.entries.map { role ->
            when (role) {
                Role.KEYWORD -> SpanStyle(color = keyword)
                Role.STRING -> SpanStyle(color = c(0xFF2E7D32, 0xFFC3E88D))
                Role.COMMENT -> SpanStyle(color = c(0xFF757575, 0xFF8A94A6), fontStyle = FontStyle.Italic)
                Role.NUMBER -> SpanStyle(color = c(0xFFC2410C, 0xFFF78C6C))
                Role.TYPE -> SpanStyle(color = type)
                Role.META -> SpanStyle(color = c(0xFF9C6A00, 0xFFFFCB6B))
                Role.HEADING -> SpanStyle(color = type, fontWeight = FontWeight.Bold)
                Role.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
                Role.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
                Role.ERROR -> SpanStyle(color = c(0xFFC62828, 0xFFFF6E6E))
                Role.WARN -> SpanStyle(color = c(0xFFB26A00, 0xFFFFCB6B))
                Role.ADDED -> SpanStyle(color = c(0xFF2E7D32, 0xFFA5D6A7))
            }
        }.toTypedArray()
    }
}

// ---------------------------------------------------------------------- gestures

/**
 * Pinch and `Ctrl` + wheel change the text size, like the thumbnail size in the grid. One finger
 * is left alone for scrolling, selecting and placing the cursor.
 */
private fun Modifier.textSizeGestures(onZoom: (Int) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var acc = 1f
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.changes.count { it.pressed } >= 2) {
                    acc *= ev.calculateZoom()
                    if (acc > 1.25f) {
                        onZoom(1)
                        acc = 1f
                    } else if (acc < 0.8f) {
                        onZoom(-1)
                        acc = 1f
                    }
                    // Two fingers zoom; the text field must not scroll or select meanwhile.
                    ev.changes.forEach { it.consume() }
                }
            } while (ev.changes.any { it.pressed })
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
