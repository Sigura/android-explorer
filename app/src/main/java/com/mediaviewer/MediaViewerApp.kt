package com.mediaviewer

import android.app.Application
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import com.mediaviewer.data.CrashHandler
import com.mediaviewer.data.ErrorReporter
import com.mediaviewer.data.fs.Trash
import com.mediaviewer.data.history.BookFolder
import com.mediaviewer.data.history.HistoryStore
import com.mediaviewer.data.meta.MetaStore
import com.mediaviewer.data.mtp.MtpDevices
import com.mediaviewer.data.settings.SettingsStore
import com.mediaviewer.data.thumbs.TextDecode
import com.mediaviewer.data.thumbs.Thumbnails
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.playback.BookPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** Manual dependency container; the app is small enough not to need Hilt. */
class MediaViewerApp : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val errors by lazy { ErrorReporter(this, appScope) }
    val settings by lazy { SettingsStore(this, appScope) }
    val meta by lazy { MetaStore(this) }
    val thumbnails by lazy {
        Thumbnails(meta, errors).also {
            it.remote = { entry -> mtp.thumbnail(entry) }
            it.remoteFolder = { path -> mtp.folderPreview(path) }
            it.packageManager = packageManager
            it.contentResolver = contentResolver
        }
    }

    /** Cameras and phones over USB (todo §9). */
    val mtp by lazy { MtpDevices(this, appScope, settings) }

    /** Listening history (todo §3.4): user data, unlike [meta]. */
    val history by lazy { HistoryStore(this) }
    val bookPlayer by lazy { BookPlayer(this) }

    /** Charset for playlists and text without a declared encoding (setting, else by device language). */
    fun legacyCharset(): java.nio.charset.Charset = try {
        java.nio.charset.Charset.forName(settings.settings.value.textCharset.ifEmpty { TextDecode.defaultLegacyCharset() })
    } catch (e: Exception) {
        Charsets.ISO_8859_1
    }

    /** Purges leftovers from disk on creation: first touch it off the main thread. */
    val trash by lazy { Trash(this) }

    override fun onCreate() {
        super.onCreate()
        // The crash screen's own process needs nothing else (README §3.5).
        if (CrashHandler.isCrashProcess(this)) return
        CrashHandler.install(this)
        // Books on a camera or phone over USB (todo §11.4): their folders come from the device.
        BookFolder.device = object : BookFolder.Device {
            override fun list(folder: String): List<FileEntry>? = try {
                runBlocking { mtp.connection(folder)?.list(folder, showHidden = false) }
            } catch (e: java.io.IOException) {
                null
            }

            override fun copyOf(entry: FileEntry): java.io.File? = try {
                runBlocking { mtp.mirror.fetch(entry) }
            } catch (e: java.io.IOException) {
                null
            }
        }
        if (BuildConfig.DEBUG) {
            // README §8: no main-thread disk I/O.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build(),
            )
        }
    }

    /** Coil is used by the slideshow for full-screen images and animated GIF/WebP playback. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache(null)
            .build()
}
