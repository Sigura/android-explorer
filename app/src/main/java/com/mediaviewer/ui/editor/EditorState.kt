package com.mediaviewer.ui.editor

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import com.mediaviewer.data.UiMessage
import com.mediaviewer.data.abandonable
import com.mediaviewer.data.fs.TextFile
import com.mediaviewer.data.thumbs.TextDecode
import com.mediaviewer.domain.EditHistory
import com.mediaviewer.domain.Highlight
import com.mediaviewer.domain.Spans
import com.mediaviewer.domain.TextChange
import com.mediaviewer.domain.TextSearch
import com.mediaviewer.ui.AppViewModel
import com.mediaviewer.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

/** Questions the editor asks before it goes on (todo §6.3, §6.4). Not errors: those are snackbars. */
enum class EditorDialog { UNSAVED, CHANGED_ON_DISK, UNENCODABLE }

/** Text, undo history, colours and saving of the editor screen (todo §6). */
@Stable
class EditorState(private val vm: AppViewModel, screen: Screen.Editor) {
    var doc by mutableStateOf(screen.doc)
        private set
    private val isNew = screen.isNew
    /** From the file name; follows a rename. */
    var language by mutableStateOf(Highlight.language(screen.doc.name))
        private set

    var value by mutableStateOf(TextFieldValue(screen.draft ?: screen.doc.text))
        private set

    /** Colours for [value]; moved along with each edit until the next lexing pass. */
    var spans by mutableStateOf(Spans.EMPTY)

    private val history = EditHistory()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val dirty by derivedStateOf { !doc.readOnly && value.text != doc.text }

    var dialog by mutableStateOf<EditorDialog?>(null)

    /** The save that is waiting for an answer to [dialog]: its charset, whether it overwrites, and what follows it. */
    private var pendingCharset: Charset? = null
    private var pendingForce = false
    private var afterSave: (() -> Unit)? = null
    private var everSaved = false

    private var announced = false

    // ------------------------------------------------------------------ find (todo §10.6)

    var findOpen by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var matchCase by mutableStateOf(false)

    /** The replace row under the find field (`Ctrl` + H); never for read-only files. */
    var replaceOpen by mutableStateOf(false)
    var replacement by mutableStateOf("")

    /** Start offsets of the matches in the current text (computed off the main thread by the screen). */
    var matches by mutableStateOf(IntArray(0))
        private set

    /** The match shown, an index into [matches]; -1 = none. */
    var current by mutableIntStateOf(-1)
        private set

    /** Bumped on each jump, so the read-only list scrolls even to the same line again. */
    var jumps by mutableIntStateOf(0)
        private set

    fun openFind() {
        if (!findOpen) {
            // A selection on one line becomes the query, as in most editors.
            val sel = value.selection
            val picked = if (sel.collapsed) "" else value.text.substring(sel.min, sel.max)
            if (picked.isNotEmpty() && '\n' !in picked && picked.length <= 200) query = picked
        }
        findOpen = true
    }

    /** `Ctrl` + H: find with the replace row. */
    fun openReplace() {
        if (doc.readOnly) return openFind()
        openFind()
        replaceOpen = true
    }

    /**
     * Replaces the current match and moves on to the next one (the matches are found again once
     * the text settles). One undo step.
     */
    fun replaceCurrent() {
        if (doc.readOnly) return
        val start = matches.getOrNull(current) ?: return findNext()
        val end = start + query.length
        val text = value.text
        // The text may have changed since the matches were found: replace only a real match.
        if (end > text.length || !text.regionMatches(start, query, 0, query.length, ignoreCase = !matchCase)) return
        val caret = start + replacement.length
        onValueChange(TextFieldValue(text.substring(0, start) + replacement + text.substring(end), TextRange(caret)))
    }

    /** Replaces every match (up to [TextSearch.LIMIT]) as one undo step; reports how many. */
    fun replaceAll() {
        if (doc.readOnly || query.isEmpty()) return
        val text = value.text
        val found = TextSearch.find(text, query, matchCase)
        if (found.isEmpty()) return
        val (out, caret) = TextSearch.replaceAll(text, found, query.length, replacement)
        onValueChange(TextFieldValue(out, TextRange(caret)))
        vm.errors.notify("Replaced ${found.size}${if (found.size >= TextSearch.LIMIT) "+ (run again for more)" else ""}", log = false)
    }

    fun closeFind() {
        searched = null
        findOpen = false
        replaceOpen = false
        matches = IntArray(0)
        current = -1
    }

    /** New matches for the text or query: keeps the place, starting from the caret. */
    /** The query and case the last matches were found for: only a new search moves the selection. */
    private var searched: Pair<String, Boolean>? = null

    fun setMatches(found: IntArray, forText: String, forQuery: String) {
        if (forText != value.text || forQuery != query || !findOpen) return
        matches = found
        current = TextSearch.nearest(found, value.selection.min)
        val search = forQuery to matchCase
        // After an edit (same search) the selection stays where the user is typing.
        if (search != searched && current >= 0) show(jump = false)
        searched = search
    }

    fun findNext(forward: Boolean = true) {
        if (!findOpen) openFind()
        current = TextSearch.step(current, matches.size, forward)
        if (current >= 0) show(jump = true)
    }

    /** Selects the current match (which scrolls it into view); read-only files scroll their list. */
    private fun show(jump: Boolean) {
        val start = matches[current]
        // Matches lag the text and query by up to 100 ms: select only a range that still matches.
        val text = if (doc.readOnly) doc.text else value.text
        if (start + query.length > text.length || !text.regionMatches(start, query, 0, query.length, ignoreCase = !matchCase)) return
        if (!doc.readOnly) value = value.copy(selection = TextRange(start, start + query.length))
        if (jump || doc.readOnly) jumps++
    }

    // ------------------------------------------------------------------ rename (todo §10.6)

    var renaming by mutableStateOf(false)

    /**
     * Renames the file in place; unsaved text, undo history and the draft stay with it. [onError]
     * gets the reason when it fails, for the dialog.
     */
    fun rename(name: String, onError: (String) -> Unit) {
        val old = doc
        val from = File(old.path)
        val to = File(from.parentFile, name)
        vm.setBusy("Renaming ${old.name}…", onStop = {}) {
            // A rename that outlived the watchdog may still have happened: look before calling it failed.
            val why = abandonable(AppViewModel.TEXT_IO_TIMEOUT_MS) { TextFile.rename(from, name) ?: "" }
                ?: withContext(Dispatchers.IO) { if (!from.exists() && to.exists()) "" else null }
            when {
                why == null -> onError("Renaming took too long")
                why.isNotEmpty() -> onError(why)
                else -> {
                    doc = old.copy(path = to.path)
                    language = Highlight.language(name)
                    spans = Spans.EMPTY
                    renaming = false
                    vm.thumbnails.forget(old.path)
                    vm.editorRenamed(to.path)
                    // The draft is keyed by path: move it with the file.
                    vm.viewModelScope.launch(Dispatchers.IO) { vm.drafts.remove(old.path) }
                    if (dirty) keepDraft()
                }
            }
        }
    }

    /** The draft from an earlier visit was shown: the snackbar offers to discard it. Once only. */
    fun announceDraft() {
        if (announced || !dirty) return
        announced = true
        vm.errors.message(
            UiMessage("Unsaved changes restored", actionLabel = "Discard", durationMs = 6000, onAction = ::revert),
        )
    }

    fun onValueChange(new: TextFieldValue) {
        if (doc.readOnly) return
        val old = value
        if (new.text != old.text) {
            history.record(old.text, new.text, old.selection.min, old.selection.max, SystemClock.uptimeMillis())
                ?.let { spans = spans.shifted(it) }
            updateUndo()
        }
        value = new
    }

    fun undo() = history.undo(value.text)?.let { apply(it) }

    fun redo() = history.redo(value.text)?.let { apply(it) }

    private fun apply(step: EditHistory.Step) {
        TextChange.between(value.text, step.text)?.let { spans = spans.shifted(it) }
        value = TextFieldValue(step.text, TextRange(step.selStart, step.selEnd))
        updateUndo()
    }

    private fun updateUndo() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    /** Back to the file's saved text: the draft is dropped. */
    private fun revert() {
        value = TextFieldValue(doc.text)
        history.clear()
        updateUndo()
        spans = Spans.EMPTY
        dropDraft()
    }

    // ------------------------------------------------------------------ saving

    /**
     * Saves on IO under a watchdog. A file changed by another app, or characters the charset can't
     * hold, ask first ([dialog]); [then] runs after a successful save.
     */
    fun save(charset: Charset = doc.charset, force: Boolean = false, then: (() -> Unit)? = null) {
        if (doc.readOnly) return
        val d = doc
        val text = value.text
        vm.setBusy("Saving ${d.name}…", onStop = {}) {
            when (val r = abandonable(AppViewModel.TEXT_IO_TIMEOUT_MS) { TextFile.save(d, text, force, charset) }) {
                is TextFile.Save.Ok -> {
                    doc = d.copy(text = text, charset = charset, bom = d.bom && charset == d.charset, size = r.size, mtime = r.mtime)
                    everSaved = true
                    vm.thumbnails.forget(d.path)
                    dropDraft()
                    then?.invoke()
                }
                TextFile.Save.Changed -> ask(EditorDialog.CHANGED_ON_DISK, charset, force, then)
                TextFile.Save.Unencodable -> ask(EditorDialog.UNENCODABLE, charset, force, then)
                is TextFile.Save.Failed -> vm.errors.notify("Couldn't save ${d.name}: ${r.reason}", d.path)
                null -> vm.errors.notify("Saving ${d.name} took too long", d.path)
            }
        }
    }

    private fun ask(d: EditorDialog, charset: Charset, force: Boolean, then: (() -> Unit)?) {
        pendingCharset = charset
        pendingForce = force
        afterSave = then
        dialog = d
    }

    /** "Changed on disk" → Overwrite. */
    fun overwrite() {
        dialog = null
        save(charset = pendingCharset ?: doc.charset, force = true, then = afterSave)
    }

    /** "Can't be saved in Windows-1251" → Save as UTF-8. Also in the ⋮ menu (then nothing is pending). */
    fun saveAsUtf8() {
        val waiting = dialog == EditorDialog.UNENCODABLE
        dialog = null
        save(charset = Charsets.UTF_8, force = waiting && pendingForce, then = if (waiting) afterSave else null)
    }

    /** Cancel in any of the questions. */
    fun dismissDialog() {
        dialog = null
        afterSave = null
    }

    /** "Changed on disk" → Reload: the other app's version replaces the text here. */
    fun reload() {
        dialog = null
        val path = doc.path
        vm.setBusy("Reading ${doc.name}…", onStop = {}) {
            val r = abandonable(AppViewModel.TEXT_IO_TIMEOUT_MS) {
                TextFile.load(File(path), vm.thumbnails.legacyCharset, TextDecode.cyrillicDetector)
            }
            if (r is TextFile.Load.Ok) {
                doc = r.doc
                revert()
                closeFind()
            } else {
                vm.errors.notify("Couldn't read ${doc.name} again", path)
            }
        }
    }

    // ------------------------------------------------------------------ leaving

    /** Back, Esc, ←: asks first when there are unsaved changes. */
    fun close() {
        if (dirty) dialog = EditorDialog.UNSAVED else finish()
    }

    fun saveAndClose() {
        dialog = null
        save(then = ::finish)
    }

    fun discardAndClose() {
        dialog = null
        dropDraft()
        finish()
    }

    private fun finish() {
        // A new file closed without ever being saved: a mistap leaves nothing behind (todo §6.1).
        if (isNew && !everSaved) {
            val f = File(doc.path)
            vm.viewModelScope.launch(Dispatchers.IO) { if (f.length() == 0L) f.delete() }
        }
        vm.closeEditor()
    }

    /** The app goes to the background: unsaved text is kept, in case Android kills it. */
    fun keepDraft() {
        if (!dirty) return
        val path = doc.path
        val text = value.text
        vm.viewModelScope.launch(Dispatchers.IO) {
            try {
                vm.drafts.put(path, text)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { vm.errors.notify("Couldn't keep unsaved changes", path) }
            }
        }
    }

    private fun dropDraft() {
        val path = doc.path
        vm.viewModelScope.launch(Dispatchers.IO) { vm.drafts.remove(path) }
    }
}
