package com.mediaviewer.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mediaviewer.data.fs.TextFile
import com.mediaviewer.domain.Highlight
import com.mediaviewer.domain.Language
import com.mediaviewer.domain.model.FileEntry

enum class NewItem { FILE, FOLDER }

/**
 * Name of a new text file or folder (todo §6.1). The name is used as typed: no extension is added.
 * A line under the field says what the name means, or why it can't be used.
 */
@Composable
fun NewItemDialog(
    kind: NewItem,
    entries: List<FileEntry>,
    onCreate: (name: String, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var raw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val name = TextFile.cleanName(raw)
    // Exact match only: on FAT volumes a name differing in case is refused when it is created.
    val problem = TextFile.nameProblem(name) ?: if (entries.any { it.name == name }) "$name already exists" else null
    val shownProblem = error ?: problem?.takeIf { raw.isNotEmpty() }

    fun submit() {
        if (problem != null || creating) return
        creating = true
        onCreate(name) { why ->
            creating = false
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
        onDismissRequest = onDismiss,
        title = { Text(if (kind == NewItem.FILE) "New text file" else "New folder") },
        text = {
            Column {
                OutlinedTextField(
                    value = raw,
                    onValueChange = {
                        raw = it
                        error = null
                    },
                    singleLine = true,
                    placeholder = { Text(if (kind == NewItem.FILE) "notes.txt" else "Folder name") },
                    isError = shownProblem != null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(
                    shownProblem ?: if (kind == NewItem.FILE) describe(name) else hiddenNote(name),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (shownProblem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = problem == null && !creating) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What the typed name will give: `Kotlin — syntax colours`, `No extension — plain text`. */
private fun describe(name: String): String {
    if (name.isEmpty()) return "Type the extension too, e.g. notes.txt or script.py"
    val lang = Highlight.language(name)
    val what = when {
        lang != Language.PLAIN -> "${lang.label} — syntax colours"
        name.lastIndexOf('.') <= 0 -> "No extension — plain text"
        else -> "Plain text"
    }
    return what + hiddenNote(name).let { if (it.isEmpty()) "" else ". $it" }
}

private fun hiddenNote(name: String): String =
    if (name.startsWith('.')) "Hidden — shown only with \"Show hidden files\"" else ""
