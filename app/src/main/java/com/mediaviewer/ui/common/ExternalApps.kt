package com.mediaviewer.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.domain.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ExternalApps"

/**
 * Opening files in other apps (README §3.11). Package queries run on [Dispatchers.IO]; only
 * `startActivity` runs on the main thread.
 */
object ExternalApps {
    private val _hasVideoPlayer = MutableStateFlow(false)

    /** Another app can play video, so long videos open there. Refreshed on every resume. */
    val hasVideoPlayer: StateFlow<Boolean> = _hasVideoPlayer.asStateFlow()

    /** MX Player reads `"position"` as an `Int`; VLC and most others as a `Long`. */
    private val INT_POSITION_PACKAGES = listOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro")

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        _hasVideoPlayer.value = withContext(Dispatchers.IO) {
            try {
                val probe = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse("content://${app.packageName}.files/probe.mp4"), "video/*")
                app.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                    .any { it.activityInfo.packageName != app.packageName }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Can't query video players", e)
                false
            }
        }
    }

    /**
     * Opens [e] in another app. A plain `ACTION_VIEW` keeps the system's "Always" choice;
     * [chooser] always asks. For video, [positionMs] is passed so the player resumes there.
     * Returns false when no app could open it (the caller shows a snackbar).
     */
    suspend fun open(context: Context, e: FileEntry, chooser: Boolean = false, positionMs: Long = 0): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, context.packageName + ".files", File(e.path))
        } catch (ex: IllegalArgumentException) {
            Log.w(TAG, "No content URI for ${e.path}", ex)
            return false
        }
        val (mime, target) = withContext(Dispatchers.IO) {
            val m = mimeFor(e)
            val probe = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m)
            // Null when the system would show its resolver (no default app yet).
            val pkg = if (chooser) null else try {
                context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName?.takeIf { it != "android" }
            } catch (ex: RuntimeException) {
                null
            }
            m to pkg
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtras(videoExtras(e, positionMs, intPosition = target in INT_POSITION_PACKAGES))
        val intent = if (chooser) {
            Intent.createChooser(view, null).apply {
                if (positionMs > 0) {
                    // Per-package extras: the chooser can't know the target in advance.
                    val replacements = Bundle()
                    INT_POSITION_PACKAGES.forEach { replacements.putBundle(it, videoExtras(e, positionMs, intPosition = true)) }
                    putExtra(Intent.EXTRA_REPLACEMENT_EXTRAS, replacements)
                }
            }
        } else {
            view
        }
        if (start(context, intent)) return true
        // No app for this type: let the user pick any app.
        if (mime != "*/*") {
            val any = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (start(context, Intent.createChooser(any, null))) return true
        }
        return false
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Can't start $intent", e)
        false
    }

    private fun videoExtras(e: FileEntry, positionMs: Long, intPosition: Boolean) = Bundle().apply {
        putString("title", e.name)
        if (positionMs > 0) {
            if (intPosition) putInt("position", positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else putLong("position", positionMs)
        }
    }

    private fun mimeFor(e: FileEntry): String {
        val m = FileTypes.mimeType(e.name, e.type)
        if (m != "*/*") return m
        val ext = FileTypes.extension(e.name) ?: return m
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: m
    }
}
