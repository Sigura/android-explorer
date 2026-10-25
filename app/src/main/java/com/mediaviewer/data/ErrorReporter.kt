package com.mediaviewer.data

import android.content.Context
import android.util.Log
import com.mediaviewer.data.fs.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ErrorRecord(val time: Long, val path: String, val reason: String)

/** A non-blocking message at the bottom of the screen (README §2.1). Never a dialog. */
data class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val durationMs: Long = 4000,
    val onAction: (() -> Unit)? = null,
    val onDismiss: (() -> Unit)? = null,
    val opensErrorLog: Boolean = false,
)

/**
 * Ignore-and-notify error handling (README §2.1): every error is logged with the `MediaViewer`
 * tag and kept in a 200-entry log; read errors from one folder within 2 s become one message.
 */
class ErrorReporter(context: Context, private val scope: CoroutineScope) {
    private val logFile = File(context.filesDir, "error_log.txt")
    private val _log = MutableStateFlow<List<ErrorRecord>>(emptyList())
    val log: StateFlow<List<ErrorRecord>> = _log.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 32)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private val pending = HashMap<String, MutableList<ErrorRecord>>()
    private var saveJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            val restored = try {
                if (logFile.exists()) {
                    logFile.readLines().mapNotNull { line ->
                        val p = line.split('\t', limit = 3)
                        if (p.size == 3) ErrorRecord(p[0].toLongOrNull() ?: 0, p[1], p[2]) else null
                    }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
            _log.value = (restored + _log.value).takeLast(MAX_LOG)
        }
    }

    /** A file couldn't be read or decoded. Grouped per folder. */
    fun readError(path: String, reason: String, t: Throwable? = null) {
        Log.w(TAG, "Read error: $path: $reason", t)
        val rec = ErrorRecord(System.currentTimeMillis(), path, reason)
        record(rec)
        val folder = path.substringBeforeLast('/', "")
        synchronized(pending) {
            val list = pending[folder]
            if (list != null) {
                list.add(rec)
                return
            }
            pending[folder] = mutableListOf(rec)
        }
        scope.launch {
            delay(GROUP_WINDOW_MS)
            val group = synchronized(pending) { pending.remove(folder) } ?: return@launch
            val text = if (group.size == 1) {
                "Couldn't read ${group[0].path.substringAfterLast('/')}"
            } else {
                "${group.size} files couldn't be read"
            }
            _messages.tryEmit(UiMessage(text, actionLabel = "Details", opensErrorLog = true))
        }
    }

    /** A failed user operation, or a status message. */
    fun notify(text: String, path: String = "", log: Boolean = true) {
        if (log) {
            Log.w(TAG, "$text $path")
            record(ErrorRecord(System.currentTimeMillis(), path, text))
        }
        _messages.tryEmit(UiMessage(text))
    }

    fun message(msg: UiMessage) {
        _messages.tryEmit(msg)
    }

    fun clear() {
        _log.value = emptyList()
        scheduleSave()
    }

    private fun record(rec: ErrorRecord) {
        _log.value = (_log.value + rec).takeLast(MAX_LOG)
        scheduleSave()
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch(Dispatchers.IO) {
            delay(1000)
            try {
                logFile.writeText(
                    _log.value.joinToString("\n") { "${it.time}\t${it.path}\t${it.reason.replace('\n', ' ')}" },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Can't save error log", e)
            }
        }
    }

    companion object {
        const val MAX_LOG = 200
        const val GROUP_WINDOW_MS = 2000L
    }
}
