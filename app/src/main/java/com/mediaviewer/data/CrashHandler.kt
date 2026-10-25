package com.mediaviewer.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.mediaviewer.BuildConfig
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.ui.CrashActivity
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.system.exitProcess

/**
 * Last-resort fallback for uncaught exceptions (README §3.5). The report is written to a file
 * first, then the crash screen opens in its own `:crash` process and this one ends. If Android
 * doesn't allow the screen to open (the app was in the background), the next launch shows it.
 */
object CrashHandler {
    private const val PENDING = "crash_pending.txt"
    private const val LAST = "last_crash.txt"
    /** Settings, favourites, recent folders and the slideshow session (SettingsStore). */
    private const val SETTINGS_FILE = "datastore/settings.preferences_pb"
    private const val META_DB = "meta.db"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                Log.e(TAG, "Uncaught exception in ${thread.name}", e)
                File(app.filesDir, PENDING).writeText(report(thread, e))
                app.startActivity(
                    Intent(app, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            } catch (t: Throwable) {
                // Couldn't show our screen: let the system handle it as usual.
                previous?.uncaughtException(thread, e)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /** True for the separate process the crash screen runs in; it gets no handler of its own. */
    fun isCrashProcess(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            android.app.Application.getProcessName()
        } else {
            try {
                File("/proc/self/cmdline").readText().trimEnd('\u0000')
            } catch (e: Exception) {
                ""
            }
        }
        return name.endsWith(":crash")
    }

    /** A crash whose screen hasn't been seen yet. */
    fun hasPending(context: Context): Boolean = File(context.filesDir, PENDING).exists()

    /** The report to show; marks it as seen, so a normal launch doesn't open the crash screen again. */
    fun takeReport(context: Context): String? {
        val pending = File(context.filesDir, PENDING)
        val last = File(context.filesDir, LAST)
        return try {
            if (pending.exists()) {
                last.delete()
                if (!pending.renameTo(last)) pending.copyTo(last, overwrite = true).also { pending.delete() }
            }
            if (last.exists()) last.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    /** The metadata cache (GIF block scans, video info) and anything in the cache folder. */
    fun clearCache(context: Context) {
        context.deleteDatabase(META_DB)
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Back to default settings; favourites, recent folders and the slideshow session go too. */
    fun resetSettings(context: Context) {
        File(context.filesDir, SETTINGS_FILE).delete()
    }

    private fun report(thread: Thread, e: Throwable): String = buildString {
        appendLine("Media Viewer ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Time: ${DateFormat.getDateTimeInstance().format(Date())}")
        appendLine("Thread: ${thread.name}")
        appendLine()
        append(e.stackTraceToString())
    }
}
