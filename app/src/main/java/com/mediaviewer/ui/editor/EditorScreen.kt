package com.mediaviewer.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import com.mediaviewer.domain.Brackets
import kotlinx.coroutines.flow.first
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
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
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
    val colours = rememberEditorColours(settings.editorTheme)
    var themeDialog by remember { mutableStateOf(false) }

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
                    ev.isCtrlPressed && ev.key == Key.H -> {
                        state.openReplace()
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
            EditorBar(state, compact, settings.editorTextSp, settings.editorWrap, settings.editorLineNumbers, settings.editorWhitespace, setTextSize, setWrap) { item ->
                when (item) {
                    ViewItem.THEME -> themeDialog = true
                    ViewItem.LINE_NUMBERS -> vm.settingsStore.update { it.copy(editorLineNumbers = !it.editorLineNumbers) }
                    ViewItem.WHITESPACE -> vm.settingsStore.update { it.copy(editorWhitespace = !it.editorWhitespace) }
                }
            }
            if (state.findOpen) FindBar(state, findFocus)
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .background(colours.background)
                    .textSizeGestures(::zoom),
            ) {
                val code = CodeFonts.family(settings.codeFontBundled)
                val view = EditorView(colours, settings.editorLineNumbers, settings.editorWhitespace)
                // A chosen theme's selection colours; *Default* keeps the app's.
                CompositionLocalProvider(LocalTextSelectionColors provides (colours.selection ?: LocalTextSelectionColors.current)) {
                    if (state.doc.readOnly) ReadOnlyText(state, settings.editorTextSp, settings.editorWrap, code, view) else EditText(state, settings.editorTextSp, settings.editorWrap, code, view)
                }
            }
        }
        Dialogs(state)
        if (state.renaming) RenameDialog(state)
        if (themeDialog) {
            EditorThemeDialog(
                settings.editorTheme,
                CodeFonts.family(settings.codeFontBundled),
                onSelect = { id -> vm.settingsStore.update { it.copy(editorTheme = id) } },
                onDismiss = { themeDialog = false },
            )
        }
    }
}

@Composable
private fun textStyle(state: EditorState, sp: Int, code: FontFamily, colour: Color): TextStyle {
    val name = state.doc.name
    val kind = FileTypes.byExtension(name)?.other ?: OtherKind.TEXT
    // The same rule as the previews (README §3.10): code, logs and tables in monospace.
    val mono = state.language !in setOf(Language.PLAIN, Language.MARKDOWN) || FileTypes.monospace(name, kind)
    return TextStyle(
        fontFamily = if (mono) code else FontFamily.Default,
        fontSize = sp.sp,
        lineHeight = 1.35.em,
        color = colour,
    )
}

/** What the text area shows besides the text (todo §10.8): theme, line numbers, whitespace marks. */
private class EditorView(val colours: EditorColours, val lineNumbers: Boolean, val whitespace: Boolean)

/** The view entries of the ⋮ menu. */
private enum class ViewItem { THEME, LINE_NUMBERS, WHITESPACE }

/** Text padding inside the editor area; line numbers and marks are drawn relative to it. */
private val PAD_X = 12.dp
private val PAD_Y = 8.dp

@Composable
private fun EditText(state: EditorState, sp: Int, wrap: Boolean, code: FontFamily, view: EditorView) {
    val colours = view.colours
    val marks = if (state.findOpen) state.matches else NO_MATCHES
    val value = state.value
    // Matching bracket (todo §10.8): also over the colour limit, where spans are empty.
    val markup = state.language == Language.HTML || state.language == Language.XML
    val bracket = remember(value.text, value.selection, state.spans, markup) {
        if (value.selection.collapsed) Brackets.match(value.text, value.selection.start, state.spans, markup) else null
    }
    val transformation = remember(state.spans, colours, marks, state.current, state.query.length, bracket) {
        SpanTransformation(state.spans, colours.styles, Marks(marks, state.query.length, state.current, colours.found), bracket, colours.bracket)
    }
    val prose = state.language == Language.PLAIN || state.language == Language.MARKDOWN
    val style = textStyle(state, sp, code, colours.text)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollV = rememberScrollState()
    val scrollH = rememberScrollState()
    val density = LocalDensity.current
    val padX = with(density) { PAD_X.toPx() }
    val padY = with(density) { PAD_Y.toPx() }
    // Line numbers are measured while drawing: a cache for a screenful of them.
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val gap = with(density) { GUTTER_GAP.toPx() }
    val markStyle = style.copy(color = colours.gutter, lineHeight = TextUnit.Unspecified)
    // Line starts for the gutter numbers; a linear pass per edit, only with numbers on (verify: 1 MB).
    val lineStarts = remember(value.text, view.lineNumbers) { if (view.lineNumbers) TextSearch.lineStarts(value.text) else NO_MATCHES }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = constraints.maxHeight
        val viewportW = constraints.maxWidth
        val width = maxWidth
        val minHeight = maxHeight

        // The field is as tall as its text and the outer column scrolls, so the current line,
        // numbers and marks can be drawn from the layout in the same coordinates. The cursor is
        // kept in view here (typing, arrows, find), since the field no longer scrolls itself.
        LaunchedEffect(value.selection, value.text, viewport) {
            val l = snapshotFlow { layout }.first { it != null && it.layoutInput.text.length == value.text.length }!!
            val r = l.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
            val top = (padY + r.top).toInt()
            val bottom = (padY + r.bottom + padY).toInt()
            if (top < scrollV.value) scrollV.scrollTo(top) else if (bottom > scrollV.value + viewport) scrollV.scrollTo(bottom - viewport)
            if (!wrap) {
                val gutter = if (view.lineNumbers) gutterWidth(measurer, markStyle, lineStarts.size, gap) else 0
                val left = r.left.toInt()
                val right = (padX + r.right + 2 * padX).toInt()
                val visible = viewportW - gutter
                if (left < scrollH.value) scrollH.scrollTo(left.coerceAtLeast(0)) else if (right > scrollH.value + visible) scrollH.scrollTo(right - visible)
            }
        }

        val gutterPx = if (view.lineNumbers) gutterWidth(measurer, markStyle, lineStarts.size, gap) else 0
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollV)
                .drawBehind {
                    if (!view.lineNumbers) return@drawBehind
                    val l = layout?.takeIf { it.layoutInput.text.length == value.text.length } ?: return@drawBehind
                    drawLineNumbers(l, value.text, lineStarts, scrollV.value, viewport, padY, gutterPx - gap / 2, measurer, markStyle, colours.text, value.selection.end)
                },
        ) {
            if (view.lineNumbers) Spacer(Modifier.width(with(density) { gutterPx.toDp() }))
            Box(if (wrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(scrollH)) {
                BasicTextField(
                    value = value,
                    onValueChange = state::onValueChange,
                    textStyle = style,
                    cursorBrush = SolidColor(colours.cursor),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    // Prose gets the keyboard's help; code doesn't want "corrected" words.
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (prose) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                        autoCorrectEnabled = prose,
                    ),
                    modifier = (if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = width - with(density) { gutterPx.toDp() }))
                        .heightIn(min = minHeight)
                        .drawBehind {
                            val l = layout?.takeIf { it.layoutInput.text.length == value.text.length } ?: return@drawBehind
                            if (value.selection.collapsed) drawCurrentLine(l, value.text, value.selection.end, padY, colours.currentLine)
                            if (view.whitespace) drawWhitespace(l, value.text, scrollV.value, viewport, padX, padY, measurer, markStyle)
                        }
                        .padding(horizontal = PAD_X, vertical = PAD_Y),
                )
            }
        }
    }
}

/** Gutter width in px for [lines] lines: the digits of the last number plus [gap] px. */
private fun gutterWidth(measurer: TextMeasurer, style: TextStyle, lines: Int, gap: Float): Int {
    val digits = maxOf(2, lines.toString().length)
    return measurer.measure("0".repeat(digits), style).size.width + gap.toInt()
}

/** Space around the line numbers: half before the text, half at the gutter's left. */
private val GUTTER_GAP = 16.dp

/** The background of the cursor's line (all its wrapped rows), across the field. */
private fun DrawScope.drawCurrentLine(l: TextLayoutResult, text: String, caret: Int, padY: Float, colour: Color) {
    val c = caret.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', c - 1) + 1
    val end = text.indexOf('\n', c).let { if (it < 0) text.length else it }
    val top = l.getLineTop(l.getLineForOffset(start))
    val bottom = l.getLineBottom(l.getLineForOffset(end))
    drawRect(colour, Offset(0f, padY + top), Size(size.width, bottom - top))
}

/** The rows of [l] that are on screen, by the outer scroll position. */
private fun visibleRows(l: TextLayoutResult, scrollY: Int, viewport: Int, padY: Float): IntRange {
    val first = l.getLineForVerticalPosition((scrollY - padY).coerceAtLeast(0f))
    val last = l.getLineForVerticalPosition(scrollY + viewport - padY)
    return first..last
}

/** Numbers at the first row of each line on screen, right-aligned; the cursor's line in the text colour. */
private fun DrawScope.drawLineNumbers(
    l: TextLayoutResult,
    text: String,
    starts: IntArray,
    scrollY: Int,
    viewport: Int,
    padY: Float,
    right: Float,
    measurer: TextMeasurer,
    style: TextStyle,
    currentColour: Color,
    caret: Int,
) {
    if (starts.isEmpty()) return
    val current = TextSearch.lineOf(starts, caret.coerceIn(0, text.length))
    for (row in visibleRows(l, scrollY, viewport, padY)) {
        val s = l.getLineStart(row)
        if (s != 0 && text.getOrNull(s - 1) != '\n') continue
        val line = TextSearch.lineOf(starts, s)
        val number = measurer.measure((line + 1).toString(), if (line == current) style.copy(color = currentColour) else style)
        val y = padY + l.getLineBaseline(row) - number.firstBaseline
        drawText(number, topLeft = Offset(right - number.size.width, y))
    }
}

/** Tabs as `→` and trailing spaces as `·`, on the rows on screen (todo §10.8). */
private fun DrawScope.drawWhitespace(
    l: TextLayoutResult,
    text: String,
    scrollY: Int,
    viewport: Int,
    padX: Float,
    padY: Float,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val rows = visibleRows(l, scrollY, viewport, padY)
    val from = l.getLineStart(rows.first)
    val to = l.getLineEnd(rows.last)
    val arrow = measurer.measure("→", style)
    val dot = measurer.measure("·", style)
    fun mark(at: Int, m: TextLayoutResult) {
        val box = l.getBoundingBox(at)
        drawText(m, topLeft = Offset(padX + box.center.x - m.size.width / 2f, padY + box.center.y - m.size.height / 2f))
    }
    var i = from
    while (i < to) {
        when (text[i]) {
            '\t' -> mark(i, arrow)
            ' ' -> {
                var j = i
                while (j < text.length && text[j] == ' ') j++
                if (j == text.length || text[j] == '\n' || text[j] == '\r') for (k in i until minOf(j, to)) mark(k, dot)
                i = j
                continue
            }
        }
        i++
    }
}

/** The lines of a read-only file, where each starts, and the index of the longest (-1: none). */
private data class ReadOnlyLines(val lines: List<String>, val starts: IntArray, val longest: Int)

/** Without wrap, read-only lines are measured and shown up to this many characters (todo §11.2). */
private const val MAX_LINE_CHARS = 2_000

/**
 * Files from 1 to 10 MB: shown line by line, which stays fast where a text field would not. With
 * wrap off (todo §11.2) the whole list scrolls sideways together, as wide as its longest line.
 */
@Composable
private fun ReadOnlyText(state: EditorState, sp: Int, wrap: Boolean, code: FontFamily, view: EditorView) {
    val colours = view.colours
    val style = textStyle(state, sp, code, colours.text)
    // Lines, where each starts (for find) and the longest one, all off the main thread: up to 10 MB.
    val split by produceState(ReadOnlyLines(emptyList(), IntArray(0), -1), state.doc) {
        value = withContext(Dispatchers.Default) {
            val lines = state.doc.text.split('\n')
            var longest = -1
            for (i in lines.indices) if (longest < 0 || lines[i].length > lines[longest].length) longest = i
            ReadOnlyLines(lines, TextSearch.lineStarts(state.doc.text), longest)
        }
    }
    val (lines, starts) = split
    val list = rememberLazyListState()
    // Find: the list scrolls to the current match's line, a few lines below the top.
    LaunchedEffect(state.jumps) {
        val m = state.matches.getOrNull(state.current) ?: return@LaunchedEffect
        if (lines.isNotEmpty()) list.scrollToItem((TextSearch.lineOf(starts, m) - 3).coerceAtLeast(0))
    }
    val marks = Marks(if (state.findOpen) state.matches else NO_MATCHES, state.query.length, state.current, colours.found)
    val numberStyle = style.copy(color = colours.gutter, textAlign = TextAlign.End)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutter = with(density) { gutterWidth(measurer, numberStyle, lines.size, GUTTER_GAP.toPx()).toDp() }
    // Without wrap: the longest line by characters, measured once (cut at MAX_LINE_CHARS; a
    // proportional font gets some slack, since another line may have wider letters).
    val mono = style.fontFamily == code
    val widest = remember(split, style, wrap) {
        if (wrap || split.longest < 0) 0
        else measurer.measure(lines[split.longest].take(MAX_LINE_CHARS), style, softWrap = false, maxLines = 1).size.width.let { if (mono) it else it * 5 / 4 }
    }
    val scrollH = rememberScrollState()
    val listModifier = if (wrap) {
        Modifier.fillMaxSize()
    } else {
        val content = with(density) { widest.toDp() } + (if (view.lineNumbers) gutter else 0.dp) + 24.dp
        Modifier.fillMaxSize().horizontalScroll(scrollH).widthIn(min = content)
    }
    LazyColumn(listModifier, state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        itemsIndexed(lines) { i, full ->
            // Without wrap a line is cut where the sideways scroll ends, so a minified 1 MB line isn't laid out whole.
            val line = if (wrap) full else full.take(MAX_LINE_CHARS)
            val text = if (marks.starts.isEmpty() || i >= starts.size) {
                AnnotatedString(line)
            } else {
                val b = AnnotatedString.Builder(line)
                marks.addTo(b, starts[i], starts[i] + line.length)
                b.toAnnotatedString()
            }
            if (view.lineNumbers) {
                Row {
                    Text("${i + 1}", style = numberStyle, modifier = Modifier.width(gutter).padding(end = GUTTER_GAP / 2))
                    Text(text, style = style, softWrap = wrap, maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Clip)
                }
            } else {
                Text(text, style = style, softWrap = wrap, maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Clip)
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
    lineNumbers: Boolean,
    whitespace: Boolean,
    onTextSize: (Int) -> Unit,
    onWrap: (Boolean) -> Unit,
    onView: (ViewItem) -> Unit,
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
                    DropdownMenuItem(
                        text = { Text("Wrap lines") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.WrapText, contentDescription = null) },
                        trailingIcon = { Checkbox(checked = wrap, onCheckedChange = null) },
                        onClick = { onWrap(!wrap) },
                    )
                    DropdownMenuItem(
                        text = { Text("Line numbers") },
                        leadingIcon = { Icon(Icons.Rounded.FormatListNumbered, contentDescription = null) },
                        trailingIcon = { Checkbox(checked = lineNumbers, onCheckedChange = null) },
                        onClick = { onView(ViewItem.LINE_NUMBERS) },
                    )
                    if (!readOnly) {
                        DropdownMenuItem(
                            text = { Text("Show tabs and trailing spaces") },
                            leadingIcon = { Icon(Icons.Rounded.SpaceBar, contentDescription = null) },
                            trailingIcon = { Checkbox(checked = whitespace, onCheckedChange = null) },
                            onClick = { onView(ViewItem.WHITESPACE) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Theme…") },
                        leadingIcon = { Icon(Icons.Rounded.Palette, contentDescription = null) },
                        onClick = {
                            menu = false
                            onView(ViewItem.THEME)
                        },
                    )
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
    private val bracket: Brackets.Match?,
    private val bracketStyle: SpanStyle,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.size == 0 && marks.starts.isEmpty() && bracket == null) return TransformedText(text, OffsetMapping.Identity)
        val n = text.length
        val b = AnnotatedString.Builder(text)
        for (i in 0 until spans.size) {
            val s = spans.start(i)
            val e = minOf(spans.end(i), n)
            if (s < e) b.addStyle(styles[spans.role(i).ordinal], s, e)
        }
        if (bracket != null && bracket.closeEnd <= n) {
            b.addStyle(bracketStyle, bracket.openStart, bracket.openEnd)
            b.addStyle(bracketStyle, bracket.closeStart, bracket.closeEnd)
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
