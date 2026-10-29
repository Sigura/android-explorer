package com.mediaviewer.data.thumbs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.content.ContentResolver
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.mediaviewer.data.ErrorReporter
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.data.meta.MediaMeta
import com.mediaviewer.data.meta.MetaStore
import com.mediaviewer.data.mtp.MtpPaths
import com.mediaviewer.data.tags.FileSource
import com.mediaviewer.domain.Companions
import com.mediaviewer.domain.CsvPreview
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import com.mediaviewer.domain.model.OtherKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Immutable
class Still(val image: ImageBitmap, val srcWidth: Int, val srcHeight: Int)

sealed interface Thumb {
    class Image(val still: Still) : Thumb

    /** 5 frames at 10/30/50/70/90 % (README §4.3). [complete] is false while only the first frame is known. */
    class Strip(
        val frames: List<ImageBitmap>,
        val srcWidth: Int,
        val srcHeight: Int,
        val durationMs: Long?,
        /** An animated image (GIF, animated WebP): the tile shows [badge] instead of a duration. */
        val gif: Boolean,
        val complete: Boolean,
        val badge: String = "GIF",
    ) : Thumb

    /**
     * First lines of a text file (README §3.10): glyph coverage only (`ALPHA_8`), tinted by the tile.
     * [textPx] is the font size it was drawn with; a new system font size draws it again.
     */
    class Text(val image: ImageBitmap, val textPx: Float, val mono: Typeface?) : Thumb

    /**
     * The picture of a document, book, audio file or app (todo §1.0) and a [badge] (pages,
     * duration, the app's name, the format). [fit]: show it whole (book covers, icons); otherwise
     * it fills the tile from the top (a PDF page, album art).
     */
    class Cover(val still: Still, val badge: String?, val fit: Boolean) : Thumb

    /**
     * Folder collage (README §4.1): candidates chosen cheap-first, shown 4 at a time. [audioCount]:
     * audio files directly inside (counted during the same scan), for the tile's ▶ button (todo §3.2).
     */
    class Folder(
        val candidates: List<FileEntry>,
        val itemCount: Int,
        val groups: ConcurrentHashMap<Int, List<Still>>,
        val audioCount: Int = 0,
    ) : Thumb {
        val groupCount: Int get() = (candidates.size + 3) / 4
    }
}

class DecodeTimeout : Exception("decode timed out")

/** The file a [Thumbnails.load] decodes and how much longer its watchdogs allow after earlier timeouts. */
private class DecodeAttempt(val path: String, val scale: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DecodeAttempt>
}

/**
 * Thumbnail scheduler and cache (README §2, rules 3–6, and §2.1 watchdogs). The UI only asks
 * for visible tiles once scrolling has stopped, and cancels the request when a tile leaves the
 * screen; this class bounds parallelism, applies watchdog timeouts and caches the last 100
 * results. A watchdog counts only the time a decode runs, not the time it waits for a thread.
 * A broken file is not retried automatically during the session; a timed-out one is, once no
 * other thumbnail work is running ([awaitRetry]), with a longer watchdog each time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Thumbnails(
    private val meta: MetaStore,
    private val errors: ErrorReporter,
) {
    /** A thumbnail and the tile width it was decoded for. */
    private class Cached(val thumb: Thumb, val width: Int)

    private val cache = LruCache<String, Cached>(MIN_CACHE)
    private val failed = ConcurrentHashMap<String, Boolean>() // path -> timed out
    /** Timeouts so far per path; each one doubles the watchdogs of the next attempt, up to ×8 ([MAX_SCALE_SHIFT]). */
    private val timeouts = ConcurrentHashMap<String, Int>()
    /** Paths whose timed-out decode is still running in [abandonScope]; not retried until it ends. */
    private val abandoned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Thumbnail loads that are decoding right now; 0 = idle. */
    private val active = MutableStateFlow(0)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(max(2, Runtime.getRuntime().availableProcessors() / 2))
    /** Abandoned decodes keep running here after a timeout; their result is dropped. */
    private val abandonScope = CoroutineScope(SupervisorJob() + decodeDispatcher)

    @Volatile var largeGifBytes: Long = 40L * 1024 * 1024

    /** Text previews (README §3.10): font size and padding in px, set by the folder view. */
    @Volatile var textPx: Float = 30f
    @Volatile var textPadPx: Int = 18

    /** Font of monospace text previews (todo §10.9); a new one draws them again. */
    @Volatile var monoTypeface: Typeface = Typeface.MONOSPACE

    /** Text files without a detectable encoding (setting "Text files without encoding"). */
    @Volatile var legacyCharset: Charset = Charsets.ISO_8859_1

    /** MediaStore's own video thumbnails (todo §10.10); set by the app. */
    @Volatile var contentResolver: ContentResolver? = null

    /** APK icons and names (todo §1.0); set by the app. */
    @Volatile var packageManager: PackageManager? = null

    /** The device's own thumbnail of a file on an MTP device (todo §9.4, §9.8 item 8); null when it has none. */
    @Volatile var remote: (suspend (FileEntry) -> ByteArray?)? = null

    /** Collage candidates of a folder on an MTP device and its item count (todo §9.8 item 5). */
    @Volatile var remoteFolder: (suspend (String) -> Pair<List<FileEntry>, Int>)? = null

    /** Files whose content turned out not to be text: no preview, no retry this session. */
    private val notText: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Any cached thumbnail, also one decoded for a smaller tile; it is shown while a sharper one loads. */
    fun cached(path: String): Thumb? = cache.get(path)?.thumb

    /**
     * A cached thumbnail sharp enough for a tile [width] px wide (README §3.4). After the window
     * or the thumbnail size grows, smaller ones are decoded again instead of being upscaled.
     */
    fun cached(path: String, width: Int): Thumb? = cache.get(path)?.takeIf { it.width * UPSCALE_SLACK >= width }?.thumb

    fun failure(path: String): Boolean? = failed[path]

    /**
     * A cached thumbnail that is good enough for this request: a first-frame strip only satisfies
     * [firstOnly], or a large GIF that the user hasn't asked to animate yet ([full]).
     */
    fun usable(entry: FileEntry, width: Int, full: Boolean, firstOnly: Boolean): Thumb? {
        val hit = cached(entry.path, width) ?: return null
        if (hit is Thumb.Text) return hit.takeIf { it.textPx == textPx && (it.mono == null || it.mono === monoTypeface) }
        if (hit !is Thumb.Strip || hit.complete || firstOnly) return hit
        return hit.takeIf { it.gif && entry.size > largeGifBytes && !full }
    }

    /** Opening a file manually retries it (README §2.1). */
    fun clearFailure(path: String) {
        failed.remove(path)
    }

    /**
     * Suspends until a timed-out [path] may be tried again: no thumbnail work has run for
     * [IDLE_RETRY_MS] and its previous decode has ended. The caller then calls [load] again.
     */
    suspend fun awaitRetry(path: String) {
        while (true) {
            active.first { it == 0 }
            delay(IDLE_RETRY_MS)
            if (active.value == 0 && path !in abandoned) break
        }
        failed.remove(path, true)
    }

    /** A file saved in the editor (todo §6.4): its preview is drawn again from the new content. */
    fun forget(path: String) {
        cache.remove(path)
        notText.remove(path)
        failed.remove(path)
        timeouts.remove(path)
    }

    /** Grow the cache when more tiles than [MIN_CACHE] are on screen (README §2, rule 6). */
    fun ensureCapacity(visibleTiles: Int) {
        val want = max(MIN_CACHE, visibleTiles)
        if (want > cache.maxSize()) cache.resize(want)
    }

    fun clearMemory() = cache.evictAll()

    /**
     * Thumbnail for [entry], emitted progressively (first frame, then the full strip).
     * @param full for large GIFs: run the full frame pass (the user hovered or swiped the tile).
     * @param firstOnly GIFs and videos: stop after the first frame (the folder is still being listed).
     */
    fun load(entry: FileEntry, width: Int, height: Int, full: Boolean = false, firstOnly: Boolean = false): Flow<Thumb> = flow {
        usable(entry, width, full, firstOnly)?.let {
            emit(it)
            return@flow
        }
        if (failed.containsKey(entry.path)) throw DecodeFailed(failed[entry.path] == true)
        active.update { it + 1 }
        try {
            if (MtpPaths.isMtp(entry.path)) {
                // Device files: the thumbnail the device made; folders get a collage of those, other files keep their icon.
                if (entry.isMedia) remoteStill(entry, width, height)?.let { emit(cachePut(entry.path, width, Thumb.Image(it))) }
                else if (entry.isFolder) remoteFolder(entry.path, width, height)?.let { emit(cachePut(entry.path, width, it)) }
            } else when (entry.type) {
                MediaType.IMAGE ->
                    if (mayAnimate(entry)) animatedStill(entry, width, height, full, firstOnly) { emit(it) }
                    else emit(cachePut(entry.path, width, Thumb.Image(still(entry.path, width, height))))
                MediaType.ANIMATED -> gif(entry, width, height, full, firstOnly) { emit(it) }
                MediaType.VIDEO -> video(entry, width, height, firstOnly) { emit(it) }
                MediaType.FOLDER -> emit(cachePut(entry.path, width, folder(entry.path, width, height)))
                MediaType.OTHER -> other(entry, width, height)?.let { emit(cachePut(entry.path, width, it)) }
                else -> return@flow
            }
            timeouts.remove(entry.path)
        } catch (e: DecodeTimeout) {
            // Reported once; the retries after it stay quiet.
            val n = timeouts.merge(entry.path, 1, Int::plus) ?: 1
            failed[entry.path] = true
            if (n == 1) errors.readError(entry.path, "timed out", e)
            throw DecodeFailed(true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            fail(entry.path, false, "out of memory", null)
        } catch (e: Exception) {
            fail(entry.path, false, e.message ?: e.javaClass.simpleName, e)
        } finally {
            active.update { it - 1 }
        }
    }.flowOn(DecodeAttempt(entry.path, 1 shl min(timeouts[entry.path] ?: 0, MAX_SCALE_SHIFT)))

    class DecodeFailed(val timedOut: Boolean) : Exception()

    private fun fail(path: String, timedOut: Boolean, reason: String, e: Throwable?): Nothing {
        failed[path] = timedOut
        errors.readError(path, reason, e)
        throw DecodeFailed(timedOut)
    }

    private fun cachePut(path: String, width: Int, t: Thumb): Thumb {
        cache.put(path, Cached(t, width))
        return t
    }

    /**
     * Runs a blocking decode with a watchdog; on timeout the work is abandoned, not awaited. The
     * clock starts when the decode gets a thread: with a screen full of tiles, waiting in the
     * queue is not the file's fault.
     */
    private suspend fun <T> watchdog(ms: Long, block: () -> T): T {
        val started = CompletableDeferred<Unit>()
        val job: Deferred<T> = abandonScope.async {
            started.complete(Unit)
            runInterruptible { block() }
        }
        return try {
            started.await()
            withTimeout(ms * timeoutScale()) { job.await() }
        } catch (e: TimeoutCancellationException) {
            abandon(job)
            throw DecodeTimeout()
        } catch (e: kotlinx.coroutines.CancellationException) {
            job.cancel()
            throw e
        }
    }

    private suspend fun timeoutScale(): Int = coroutineContext[DecodeAttempt]?.scale ?: 1

    /** A timed-out decode may ignore the interrupt and keep its thread; its file waits until it ends. */
    private suspend fun abandon(job: Job) {
        val path = coroutineContext[DecodeAttempt]?.path
        if (path != null) {
            abandoned.add(path)
            job.invokeOnCompletion { abandoned.remove(path) }
        }
        job.cancel()
    }

    // ---------------------------------------------------------------- static images

    private suspend fun still(path: String, w: Int, h: Int): Still = watchdog(DECODE_MS) { decodeStill(path, w, h) }

    /** Scale that covers the tile, capped so panoramas don't decode huge. */
    private fun coverScale(srcW: Int, srcH: Int, w: Int, h: Int): Float {
        val s = max(w.toFloat() / srcW, h.toFloat() / srcH)
        val cap = 2f * max(w, h) / max(srcW, srcH)
        return min(min(s, cap), 1f)
    }

    private fun decodeStill(path: String, w: Int, h: Int): Still {
        if (Build.VERSION.SDK_INT >= 28) {
            var sw = 0
            var sh = 0
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { dec, info, _ ->
                sw = info.size.width
                sh = info.size.height
                val s = coverScale(sw, sh, w, h)
                dec.setTargetSize(max(1, (sw * s).roundToInt()), max(1, (sh * s).roundToInt()))
                dec.setOnPartialImageListener { true } // show what was decoded of a truncated file
            }
            return Still(bmp.asImageBitmap(), bmp.width, bmp.height)
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) throw IllegalStateException("unsupported image")
        val s = coverScale(o.outWidth, o.outHeight, w, h)
        var sample = 1
        while (o.outWidth / (sample * 2) >= o.outWidth * s && o.outHeight / (sample * 2) >= o.outHeight * s) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("decode failed")
        return Still(bmp.asImageBitmap(), bmp.width, bmp.height)
    }

    /** A device thumbnail (JPEG, usually 160 px): the device call has its own watchdog, the decode this one. */
    private suspend fun remoteStill(entry: FileEntry, w: Int, h: Int): Still? {
        val bytes = remote?.invoke(entry) ?: return null
        return watchdog(DECODE_MS) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0) throw IllegalStateException("unsupported thumbnail")
            val s = coverScale(o.outWidth, o.outHeight, w, h)
            var sample = 1
            while (o.outWidth / (sample * 2) >= o.outWidth * s && o.outHeight / (sample * 2) >= o.outHeight * s) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalStateException("decode failed")
            Still(bmp.asImageBitmap(), bmp.width, bmp.height)
        }
    }

    // ---------------------------------------------------------------- GIF

    /** GIF block scan, cached per path + size + mtime. */
    suspend fun gifMeta(entry: FileEntry): MediaMeta {
        meta.get(entry.path, entry.size, entry.mtime)?.let { return it }
        val info = watchdog(SCAN_MS * 4) { GifCodec.scan(File(entry.path)) }
        return MediaMeta(info.width, info.height, info.frameCount, info.durationMs).also {
            meta.put(entry.path, entry.size, entry.mtime, it)
        }
    }

    private suspend fun gif(entry: FileEntry, w: Int, h: Int, full: Boolean, firstOnly: Boolean, emit: suspend (Thumb) -> Unit) {
        val file = File(entry.path)
        // A GIF that isn't a GIF (wrong extension): decode it as a still image.
        if (FileTypes.sniff(file).type != MediaType.ANIMATED) {
            emit(cachePut(entry.path, w, Thumb.Image(still(entry.path, w, h))))
            return
        }
        val first = cached(entry.path, w) as? Thumb.Strip ?: run {
            val frames = watchdog(DECODE_MS) { gifFrames(file, intArrayOf(0), w, h) }
            val f = frames.first()
            Thumb.Strip(listOf(f.first), f.second, f.third, null, gif = true, complete = false)
        }
        emit(first)
        val large = entry.size > largeGifBytes
        if (firstOnly || (large && !full)) {
            cachePut(entry.path, w, first)
            return
        }
        val m = gifMeta(entry)
        if (m.frameCount <= 1) {
            emit(cachePut(entry.path, w, Thumb.Image(Still(first.frames[0], first.srcWidth, first.srcHeight))))
            return
        }
        val targets = FRACTIONS.map { (it * m.frameCount).toInt().coerceIn(0, m.frameCount - 1) }.distinct().toIntArray()
        // Every frame up to 90 % is decompressed (~30 ms/MB on a desktop JVM), so the limit grows with size.
        val limit = if (large) LARGE_GIF_MS else DECODE_MS + entry.size / (1024 * 1024) * 250
        val frames = watchdog(limit) { gifFrames(file, targets, w, h) }
        emit(cachePut(entry.path, w, Thumb.Strip(frames.map { it.first }, first.srcWidth, first.srcHeight, m.durationMs, gif = true, complete = true)))
    }

    private fun gifFrames(file: File, targets: IntArray, w: Int, h: Int): List<Triple<ImageBitmap, Int, Int>> {
        val out = ArrayList<Triple<ImageBitmap, Int, Int>>()
        var full: Bitmap? = null
        try {
            GifCodec.decodeFrames(file, targets, { Thread.currentThread().isInterrupted }) { _, canvas, cw, ch ->
                val b = full ?: Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888).also { full = it }
                b.setPixels(canvas, 0, cw, 0, 0, cw, ch)
                val s = coverScale(cw, ch, w, h)
                val scaled = Bitmap.createScaledBitmap(b, max(1, (cw * s).roundToInt()), max(1, (ch * s).roundToInt()), true)
                out.add(Triple((if (scaled === b) b.copy(Bitmap.Config.ARGB_8888, false) else scaled).asImageBitmap(), cw, ch))
            }
        } finally {
            full?.recycle()
        }
        if (out.isEmpty()) throw GifFormatException("no frames decoded")
        return out
    }

    // ---------------------------------------------------------------- animated WebP (todo §10.2)

    /** WebP container scan, cached per path + size + mtime; a still WebP is cached as 1 frame. */
    /** Frame count and duration of an animated WebP or APNG (1 frame = a still), cached in `meta.db`. */
    private suspend fun animMeta(entry: FileEntry): MediaMeta {
        meta.get(entry.path, entry.size, entry.mtime)?.let { return it }
        val m = watchdog(SCAN_MS) {
            if (isWebp(entry)) {
                WebpAnim.scan(File(entry.path))?.let { MediaMeta(it.width, it.height, it.frames.size, it.durationMs) }
            } else {
                FileSource(File(entry.path)).use { src ->
                    val head = ByteArray(minOf(4096L, src.size).toInt())
                    src.read(0, head)
                    // Almost every PNG is a still: one small read, nothing written to meta.db.
                    if (!ApngAnim.looksAnimated(head)) return@watchdog STILL
                    ApngAnim.scan(src)?.let { MediaMeta(it.width, it.height, it.frames.size, it.durationMs) }
                }
            }
        }
        if (m === STILL) return STILL
        return (m ?: MediaMeta(0, 0, 1, 0)).also { meta.put(entry.path, entry.size, entry.mtime, it) }
    }

    /**
     * Animated WebP and APNG as a strip, like a GIF: the first frame from the still decoder at
     * once, then 5 frames composited by [webpFrames] / [apngFrames]. A still one stays an image.
     */
    private suspend fun animatedStill(entry: FileEntry, w: Int, h: Int, full: Boolean, firstOnly: Boolean, emit: suspend (Thumb) -> Unit) {
        val badge = if (isWebp(entry)) "WEBP" else "APNG"
        // First visit: the still (frame 1) goes up at once; the container scan comes after it.
        var shown: Still? = null
        if (meta.get(entry.path, entry.size, entry.mtime) == null) {
            shown = still(entry.path, w, h)
            emit(Thumb.Image(shown))
        }
        val m = animMeta(entry)
        if (m.frameCount <= 1) {
            emit(cachePut(entry.path, w, Thumb.Image(shown ?: still(entry.path, w, h))))
            return
        }
        val first = cached(entry.path, w) as? Thumb.Strip ?: run {
            val s = shown ?: still(entry.path, w, h)
            Thumb.Strip(listOf(s.image), m.width, m.height, null, gif = true, complete = false, badge = badge)
        }
        emit(first)
        val large = entry.size > largeGifBytes
        if (firstOnly || (large && !full)) {
            cachePut(entry.path, w, first)
            return
        }
        val targets = FRACTIONS.map { (it * m.frameCount).toInt().coerceIn(0, m.frameCount - 1) }.distinct().toIntArray()
        val limit = if (large) LARGE_GIF_MS else DECODE_MS + entry.size / (1024 * 1024) * 250
        val frames = watchdog(limit) { if (isWebp(entry)) webpFrames(File(entry.path), targets, w, h) else apngFrames(File(entry.path), targets, w, h) }
        emit(cachePut(entry.path, w, Thumb.Strip(frames, m.width, m.height, m.durationMs, gif = true, complete = true, badge = badge)))
    }

    /**
     * Composites the [targets] frames on one full-size canvas, starting at key frames
     * ([WebpAnim.plan]); each frame is rebuilt as a still WebP for the platform decoder.
     */
    private fun webpFrames(file: File, targets: IntArray, w: Int, h: Int): List<ImageBitmap> = RandomAccessFile(file, "r").use { raf ->
        val info = WebpAnim.scan(raf) ?: throw IOException("not an animated WebP")
        val canvas = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val out = ArrayList<ImageBitmap>()
        var shown: WebpFrame? = null
        try {
            for (step in WebpAnim.plan(WebpAnim.keyFrames(info), targets)) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val fr = info.frames[step.index]
                val prev = shown
                if (step.reset) {
                    canvas.eraseColor(android.graphics.Color.TRANSPARENT)
                } else if (prev != null && prev.disposeToBackground) {
                    c.drawRect(prev.x.toFloat(), prev.y.toFloat(), (prev.x + prev.width).toFloat(), (prev.y + prev.height).toFloat(), clear)
                }
                val bytes = WebpAnim.standalone(raf, fr)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("frame ${step.index} can't be decoded")
                c.drawBitmap(bmp, fr.x.toFloat(), fr.y.toFloat(), if (fr.blend) null else replace)
                bmp.recycle()
                shown = fr
                if (step.emit) {
                    val s = coverScale(info.width, info.height, w, h)
                    val scaled = Bitmap.createScaledBitmap(canvas, max(1, (info.width * s).roundToInt()), max(1, (info.height * s).roundToInt()), true)
                    out.add((if (scaled === canvas) canvas.copy(Bitmap.Config.ARGB_8888, false) else scaled).asImageBitmap())
                }
            }
        } finally {
            canvas.recycle()
        }
        if (out.isEmpty()) throw IOException("no frames decoded")
        out
    }

    /**
     * APNG: [ApngAnim.render] decides the order (key frames, blend, dispose); this draws it on one
     * full-size canvas, each frame decoded from a standalone PNG.
     */
    private fun apngFrames(file: File, targets: IntArray, w: Int, h: Int): List<ImageBitmap> = FileSource(file).use { src ->
        val info = ApngAnim.scan(src) ?: throw IOException("not an APNG")
        val canvas = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val out = ArrayList<ImageBitmap>()
        var backup: Bitmap? = null
        try {
            ApngAnim.render(info, targets, object : FrameOps {
                override fun reset() = canvas.eraseColor(android.graphics.Color.TRANSPARENT)

                override fun clear(x: Int, y: Int, w: Int, h: Int) =
                    c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), clear)

                override fun save() {
                    backup?.recycle()
                    backup = canvas.copy(Bitmap.Config.ARGB_8888, false)
                }

                override fun restore() {
                    backup?.let { c.drawBitmap(it, 0f, 0f, replace) }
                }

                override fun draw(index: Int, frame: ApngFrame, replace: Boolean) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val bytes = ApngAnim.standalone(src, info, frame)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("frame $index can't be decoded")
                    c.drawBitmap(bmp, frame.x.toFloat(), frame.y.toFloat(), if (replace) this@Thumbnails.srcPaint else null)
                    bmp.recycle()
                }

                override fun emit(index: Int) {
                    val s = coverScale(info.width, info.height, w, h)
                    val scaled = Bitmap.createScaledBitmap(canvas, max(1, (info.width * s).roundToInt()), max(1, (info.height * s).roundToInt()), true)
                    out.add((if (scaled === canvas) canvas.copy(Bitmap.Config.ARGB_8888, false) else scaled).asImageBitmap())
                }
            })
        } finally {
            backup?.recycle()
            canvas.recycle()
        }
        if (out.isEmpty()) throw IOException("no frames decoded")
        out
    }

    /** Draws a frame's pixels in place of the canvas's (blend source). */
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    // ---------------------------------------------------------------- video

    /** Also an audio file's duration, for the player's book total (todo §4.3). */
    suspend fun videoMeta(entry: FileEntry): MediaMeta {
        meta.get(entry.path, entry.size, entry.mtime)?.let { return it }
        return watchdog(SCAN_MS) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(entry.path)
                readVideoMeta(r)
            } finally {
                r.release()
            }
        }.also { meta.put(entry.path, entry.size, entry.mtime, it) }
    }

    private fun readVideoMeta(r: MediaMetadataRetriever): MediaMeta {
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        var vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) vw = vh.also { vh = vw }
        val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        return MediaMeta(vw, vh, frames, d)
    }

    /**
     * The whole video decode, including `release()`, runs as one block on one thread: a
     * retriever is not thread-safe, and after a timeout the abandoned decode must still be the
     * only user of it. The first frame is handed over early so the tile shows it at once.
     */
    private suspend fun video(entry: FileEntry, w: Int, h: Int, firstOnly: Boolean, emit: suspend (Thumb) -> Unit) {
        // MediaStore's thumbnail first (usually made by the gallery already): on screen at once.
        mediaStoreFrame(entry, w, h)?.let { ms ->
            val known = meta.get(entry.path, entry.size, entry.mtime)
            val strip = Thumb.Strip(listOf(ms.image), known?.width?.takeIf { it > 0 } ?: ms.srcWidth, known?.height?.takeIf { it > 0 } ?: ms.srcHeight, known?.durationMs, gif = false, complete = false)
            // Still listing: that frame is all the tile wants for now; no video decoder at all.
            if (firstOnly) {
                emit(cachePut(entry.path, w, strip))
                return
            }
            emit(strip)
        }
        val first = CompletableDeferred<Thumb.Strip>()
        val started = CompletableDeferred<Unit>()
        val job = abandonScope.async {
            started.complete(Unit)
            runInterruptible {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(entry.path)
                    val m = readVideoMeta(r)
                    meta.put(entry.path, entry.size, entry.mtime, m)
                    val frames = ArrayList<ImageBitmap>()
                    val todo = ArrayDeque(FRACTIONS.toList())
                    val t0 = SystemClock.uptimeMillis()
                    var last: Bitmap? = null
                    while (todo.isNotEmpty()) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val us = (m.durationMs * todo.removeFirst() * 1000).toLong()
                        val bmp = distinctFrame(r, us, m, w, h, last) ?: continue
                        frames.add(bmp.asImageBitmap())
                        last = bmp
                        if (frames.size == 1) {
                            first.complete(Thumb.Strip(frames.toList(), m.width, m.height, m.durationMs, gif = false, complete = false))
                            if (firstOnly) break
                            // A slow file (software codec, no seek index): 3 frames instead of 5 (todo §10.3).
                            if (SystemClock.uptimeMillis() - t0 > SLOW_FIRST_FRAME_MS) todo.retainAll { it in SLOW_FRACTIONS }
                        }
                    }
                    if (frames.isEmpty()) throw IllegalStateException("no video frames")
                    Thumb.Strip(frames, m.width, m.height, m.durationMs, gif = false, complete = !firstOnly)
                } finally {
                    r.release()
                }
            }
        }
        try {
            started.await()
            val scale = timeoutScale()
            val early = withTimeout((SCAN_MS + DECODE_MS) * scale) {
                select<Thumb.Strip> {
                    first.onAwait { it }
                    job.onAwait { it }
                }
            }
            emit(early)
            if (!early.complete && !firstOnly) emit(cachePut(entry.path, w, withTimeout(DECODE_MS * 2 * scale) { job.await() }))
            else cachePut(entry.path, w, early)
        } catch (e: TimeoutCancellationException) {
            abandon(job)
            throw DecodeTimeout()
        } catch (e: kotlinx.coroutines.CancellationException) {
            job.cancel()
            throw e
        }
    }

    /**
     * The frame at [us], unless it is the same picture as [last]: with keyframes far apart, nearby
     * fractions land on the same keyframe, so the next keyframe after [us] is tried instead, and
     * the fraction is skipped if that is the same picture too (todo §10.3).
     */
    private fun distinctFrame(r: MediaMetadataRetriever, us: Long, m: MediaMeta, w: Int, h: Int, last: Bitmap?): Bitmap? {
        val bmp = videoFrame(r, us, m, w, h) ?: return null
        if (last == null || !bmp.sameAs(last)) return bmp
        bmp.recycle()
        val next = videoFrame(r, us, m, w, h, MediaMetadataRetriever.OPTION_NEXT_SYNC) ?: return null
        if (!next.sameAs(last)) return next
        next.recycle()
        return null
    }

    private fun videoFrame(r: MediaMetadataRetriever, us: Long, m: MediaMeta, w: Int, h: Int, option: Int = MediaMetadataRetriever.OPTION_CLOSEST_SYNC): Bitmap? {
        val sw = if (m.width > 0) m.width else w
        val sh = if (m.height > 0) m.height else h
        val s = coverScale(sw, sh, w, h)
        val dw = max(1, (sw * s).roundToInt())
        val dh = max(1, (sh * s).roundToInt())
        return if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(us, option, dw, dh)
        } else {
            r.getFrameAtTime(us, option)?.let { full ->
                Bitmap.createScaledBitmap(full, dw, dh, true).also { if (it !== full) full.recycle() }
            }
        }
    }

    /** Folder → its videos' MediaStore ids by path, one query per folder (todo §10.10). */
    private val mediaIds = LruCache<String, Map<String, Long>>(32)

    /**
     * MediaStore's thumbnail of a video (API 29+), or null: files in `.nomedia` folders, on
     * storage MediaStore doesn't index, not scanned yet, or any error. Its own short watchdog.
     */
    private suspend fun mediaStoreFrame(entry: FileEntry, w: Int, h: Int): Still? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = contentResolver ?: return null
        return try {
            watchdog(MEDIASTORE_MS) {
                val dir = File(entry.path).parent ?: return@watchdog null
                val ids = mediaIds.get(dir) ?: videoIds(resolver, dir).also { mediaIds.put(dir, it) }
                val id = ids[entry.path] ?: return@watchdog null
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
                val bmp = resolver.loadThumbnail(uri, android.util.Size(max(1, w), max(1, h)), null)
                Still(bmp.asImageBitmap(), bmp.width, bmp.height)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // DecodeTimeout too: the decoder still gets its turn.
            null
        }
    }

    /** The videos directly in [dir] that MediaStore knows, by path. */
    @androidx.annotation.RequiresApi(29)
    private fun videoIds(resolver: ContentResolver, dir: String): Map<String, Long> {
        val uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        @Suppress("DEPRECATION") // `_data` is readable again from Android 11 and still answers on 10.
        val data = MediaStore.MediaColumns.DATA
        val prefix = dir.trimEnd('/') + "/"
        val out = HashMap<String, Long>()
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, data), "$data LIKE ? AND $data NOT LIKE ?", arrayOf("$prefix%", "$prefix%/%"), null)?.use { c ->
            while (c.moveToNext()) c.getString(1)?.let { out[it] = c.getLong(0) }
        }
        return out
    }

    // ---------------------------------------------------------------- text (README §3.10)

    /** Text files, code and extensionless files that turn out to be text; null = keep the icon. */
    private suspend fun text(entry: FileEntry, w: Int, h: Int): Thumb.Text? {
        if (!(entry.other.hasTextPreview || entry.other == OtherKind.UNKNOWN) || entry.path in notText) return null
        val px = textPx
        val pad = textPadPx
        val mono = if (FileTypes.monospace(entry.name, entry.other)) monoTypeface else null
        val bitmap = watchdog(DECODE_MS) {
            val text = try {
                TextDecode.read(File(entry.path), legacyCharset, TextDecode.cyrillicDetector)
            } catch (e: IOException) {
                null
            }
            if (text == null) {
                notText.add(entry.path)
                return@watchdog null
            }
            val ext = FileTypes.extension(entry.name)
            TextPreview.render(
                if (ext == "csv" || ext == "tsv") CsvPreview.align(text) else text,
                truncated = entry.size > TextDecode.READ_BYTES,
                width = w,
                height = h,
                textPx = px,
                padPx = pad,
                mono = mono,
                markdown = ext == "md" || ext == "markdown",
            )
        } ?: return null
        return Thumb.Text(bitmap.asImageBitmap(), px, mono)
    }

    // ---------------------------------------------------------------- documents, books, audio, apps (todo §1.0)

    /** Other files: a cover or page for documents, books, audio and apps; the first lines of text; null = the icon. */
    private suspend fun other(entry: FileEntry, w: Int, h: Int): Thumb? = when (entry.other) {
        OtherKind.PDF -> pdf(entry.path, w, h)
        OtherKind.AUDIO -> audioCover(entry.path, w, h)
        OtherKind.BOOK, OtherKind.DOCUMENT -> docCover(entry, w, h)
        OtherKind.APK -> apk(entry.path, w, h)
        OtherKind.VECTOR -> svg(entry, w, h)
        else -> text(entry, w, h)
    }

    /** PDF: page 1 on white, filling the tile's width from the top; the page count as the badge. */
    private suspend fun pdf(path: String, w: Int, h: Int): Thumb.Cover = watchdog(DECODE_MS) {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (r.pageCount == 0) throw IllegalStateException("no pages")
                r.openPage(0).use { page ->
                    val scale = w.toFloat() / page.width
                    val bw = max(1, w)
                    val bh = max(1, min(h, (page.height * scale).roundToInt()))
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, android.graphics.Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    Thumb.Cover(Still(bmp.asImageBitmap(), page.width, page.height), "${r.pageCount} p.", fit = false)
                }
            }
        }
    }

    /** Folder → its cover image's name ("" = none), so a folder of 500 songs is listed once. */
    private val folderCovers = LruCache<String, String>(64)

    /** Audio: the picture inside the file, else the folder's cover image; the duration as the badge. Null = the icon. */
    private suspend fun audioCover(path: String, w: Int, h: Int): Thumb.Cover? = watchdog(DECODE_MS) {
        val r = MediaMetadataRetriever()
        val (art, durationMs) = try {
            r.setDataSource(path)
            r.embeddedPicture to (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
        } finally {
            r.release()
        }
        val still = art?.let { decodeBytes(it, w, h) } ?: run {
            val dir = File(path).parent ?: return@run null
            val name = folderCovers.get(dir) ?: (Companions.coverFor(File(dir).list()?.toList().orEmpty()) ?: "").also { folderCovers.put(dir, it) }
            if (name.isEmpty()) null else decodeStill(File(dir, name).path, w, h)
        } ?: return@watchdog null
        Thumb.Cover(still, durationMs.takeIf { it > 0 }?.let(::durationLabel), fit = false)
    }

    /**
     * EPUB, CBZ, FB2, ODF and OOXML: the cover or the thumbnail the app saved inside; a DOCX
     * without one shows its text. The format as the badge.
     */
    private suspend fun docCover(entry: FileEntry, w: Int, h: Int): Thumb? {
        val name = entry.name.lowercase()
        val badge = if (name.endsWith(".fb2.zip")) "FB2" else FileTypes.extension(entry.name)?.uppercase()
        if (name.endsWith(".fb2")) {
            val still = watchdog(DECODE_MS) {
                if (entry.size > ZipIndex.MAX_ENTRY) return@watchdog null
                DocCovers.fb2Cover(File(entry.path).readBytes())?.let { decodeBytes(it, w, h) }
            } ?: return null
            return Thumb.Cover(still, badge, fit = true)
        }
        if (!(name.endsWith(".epub") || name.endsWith(".cbz") || name.endsWith(".fb2.zip") || FileTypes.extension(name) in OFFICE_ZIPS)) return null
        var docxText: String? = null
        val still = watchdog(DECODE_MS) {
            FileSource(File(entry.path)).use { src ->
                val zip = ZipIndex.open(src) ?: return@watchdog null
                val bytes = if (name.endsWith(".fb2.zip")) {
                    zip.entries.firstOrNull { it.name.endsWith(".fb2", ignoreCase = true) }?.let { zip.read(it) }?.let(DocCovers::fb2Cover)
                } else {
                    DocCovers.coverEntry(entry.name, zip)?.let { zip.find(it) }?.let { zip.read(it) }
                }
                if (bytes == null && name.endsWith(".docx")) docxText = zip.readText("word/document.xml")?.let { DocCovers.docxText(it) }
                bytes?.let { decodeBytes(it, w, h) }
            }
        }
        if (still != null) return Thumb.Cover(still, badge, fit = true)
        val text = docxText?.takeIf { it.isNotBlank() } ?: return null
        val px = textPx
        val bitmap = watchdog(DECODE_MS) { TextPreview.render(text, truncated = true, width = w, height = h, textPx = px, padPx = textPadPx, mono = null, markdown = false) } ?: return null
        return Thumb.Text(bitmap.asImageBitmap(), px, null)
    }

    /** APK: the app's icon on the tile, its name as the badge. */
    private suspend fun apk(path: String, w: Int, h: Int): Thumb.Cover? {
        val pm = packageManager ?: return null
        return watchdog(DECODE_MS) {
            val info = pm.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return@watchdog null
            // The archive isn't installed: point the resources at it so the icon and label load.
            info.sourceDir = path
            info.publicSourceDir = path
            val icon = info.loadIcon(pm)
            val size = max(1, min(w, h) / 2)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bmp))
            Thumb.Cover(Still(bmp.asImageBitmap(), size, size), info.loadLabel(pm).toString(), fit = true)
        }
    }

    /**
     * SVG / SVGZ: the drawing scaled into the tile (AndroidSVG, todo §10.10). No external
     * resources are fetched (no file resolver is set); large files keep the icon.
     */
    private suspend fun svg(entry: FileEntry, w: Int, h: Int): Thumb.Cover? = watchdog(DECODE_MS) {
        val f = File(entry.path)
        if (f.length() > MAX_SVG_BYTES) return@watchdog null
        val svg = f.inputStream().buffered().use { raw ->
            val input = if (entry.name.endsWith(".svgz", ignoreCase = true)) java.util.zip.GZIPInputStream(raw) else raw
            SVG.getFromInputStream(input)
        }
        // The size it asks for, else its view box; without either, a square.
        val box = svg.documentViewBox
        val sw = svg.documentWidth.takeIf { it > 0 } ?: box?.width() ?: 512f
        val sh = svg.documentHeight.takeIf { it > 0 } ?: box?.height() ?: 512f
        if (box == null) svg.setDocumentViewBox(0f, 0f, sw, sh)
        val scale = min(w / sw, h / sh)
        val bw = max(1, (sw * scale).roundToInt())
        val bh = max(1, (sh * scale).roundToInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp), RenderOptions().viewPort(0f, 0f, bw.toFloat(), bh.toFloat()))
        Thumb.Cover(Still(bmp.asImageBitmap(), sw.roundToInt(), sh.roundToInt()), "SVG", fit = true)
    }

    /** A picture from bytes (a cover inside a file), sampled down to about the tile. */
    private fun decodeBytes(bytes: ByteArray, w: Int, h: Int): Still? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        val s = coverScale(o.outWidth, o.outHeight, w, h)
        var sample = 1
        while (o.outWidth / (sample * 2) >= o.outWidth * s && o.outHeight / (sample * 2) >= o.outHeight * s) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return Still(bmp.asImageBitmap(), o.outWidth, o.outHeight)
    }

    private fun durationLabel(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------------------------------------------------------- folders

    /** A device folder's collage from its files' device thumbnails; null (the icon) when none has one. */
    private suspend fun remoteFolder(path: String, w: Int, h: Int): Thumb.Folder? {
        val (candidates, count) = remoteFolder?.invoke(path) ?: return null
        if (candidates.isEmpty()) return null
        val t = Thumb.Folder(candidates, count, ConcurrentHashMap())
        return t.takeIf { loadFolderGroup(it, 0, w, h).isNotEmpty() }
    }

    private suspend fun folder(path: String, w: Int, h: Int): Thumb.Folder {
        val scan = watchdog(SCAN_MS) { folderCandidates(path) }
        val t = Thumb.Folder(scan.candidates, scan.count, ConcurrentHashMap(), scan.audio)
        loadFolderGroup(t, 0, w, h)
        return t
    }

    /** Decodes the previews of one group of 4 (scrubbing moves through the groups). */
    suspend fun loadFolderGroup(t: Thumb.Folder, group: Int, w: Int, h: Int): List<Still> {
        t.groups[group]?.let { return it }
        val pw = max(1, w / 2)
        val ph = max(1, h / 2)
        val out = ArrayList<Still>()
        for (c in t.candidates.drop(group * 4).take(4)) {
            if (failed.containsKey(c.path)) continue
            try {
                out.add(previewStill(c, pw, ph))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed[c.path] = e is DecodeTimeout
                Log.w(TAG, "Folder preview failed: ${c.path}", e)
            }
        }
        t.groups[group] = out
        return out
    }

    private suspend fun previewStill(e: FileEntry, w: Int, h: Int): Still = if (MtpPaths.isMtp(e.path)) {
        remoteStill(e, w, h) ?: throw IllegalStateException("no device thumbnail")
    } else when (e.type) {
        MediaType.ANIMATED -> watchdog(DECODE_MS) {
            val f = gifFrames(File(e.path), intArrayOf(0), w, h).first()
            Still(f.first, f.second, f.third)
        }
        MediaType.VIDEO -> watchdog(DECODE_MS) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(e.path)
                val m = readVideoMeta(r)
                val b = videoFrame(r, m.durationMs * 100, m, w, h) ?: throw IllegalStateException("no frame")
                Still(b.asImageBitmap(), m.width, m.height)
            } finally {
                r.release()
            }
        }
        MediaType.OTHER -> otherStill(e, w, h) ?: throw IllegalStateException("no preview")
        else -> still(e.path, w, h)
    }

    /** A document, book, audio file or text file as a collage picture; text as dark ink on a light page. */
    private suspend fun otherStill(e: FileEntry, w: Int, h: Int): Still? = when (val t = other(e, w, h)) {
        is Thumb.Cover -> t.still
        is Thumb.Text -> watchdog(DECODE_MS) {
            val alpha = t.image.asAndroidBitmap()
            val page = Bitmap.createBitmap(alpha.width, alpha.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(page)
            c.drawColor(PAPER)
            // An ALPHA_8 bitmap is drawn in the paint's colour.
            c.drawBitmap(alpha, 0f, 0f, Paint().apply { color = INK })
            Still(page.asImageBitmap(), page.width, page.height)
        }
        else -> null
    }

    /**
     * Cheap-first preview candidates (README §4.1): no decoding, at most 2 levels deep,
     * stops after [MAX_CANDIDATES]. Mid-size stills first, then GIFs, then videos.
     */
    private class FolderScan(val candidates: List<FileEntry>, val count: Int, val audio: Int)

    private fun folderCandidates(path: String): FolderScan {
        val found = ArrayList<FileEntry>()
        var count = 0
        var audio = 0
        // Folders without media (todo §1a): documents, books, one audio file's cover, text.
        val secondary = ArrayList<FileEntry>()
        val subdirs = ArrayList<String>()
        // Level 1 is the folder itself, level 2 its subfolders.
        fun visit(dir: String, level: Int) {
            try {
                Files.newDirectoryStream(Paths.get(dir)).use { ds ->
                    for (p in ds) {
                        // The item count is only shown for folders without media, so stop early.
                        if (found.size >= MAX_CANDIDATES || Thread.currentThread().isInterrupted) return
                        val name = p.fileName.toString()
                        if (FolderScanner.isHidden(name)) continue
                        if (level == 1) count++
                        val kind = FileTypes.byExtension(name)
                        if (kind == null) {
                            if (level < 2 && File(p.toString()).isDirectory) subdirs.add(p.toString())
                            continue
                        }
                        if (level == 1 && kind.type == MediaType.OTHER && kind.other == OtherKind.AUDIO) audio++
                        if (kind.type == MediaType.OTHER && kind.other in SECONDARY && secondary.size < MAX_CANDIDATES) {
                            secondary.add(FileEntry(p.toString(), name, MediaType.OTHER, 0, 0, kind.other))
                        }
                        if (kind.type == MediaType.IMAGE || kind.type == MediaType.ANIMATED || kind.type == MediaType.VIDEO) {
                            val size = if (kind.type == MediaType.IMAGE) File(p.toString()).length() else 0L
                            found.add(FileEntry(p.toString(), name, kind.type, size, 0))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Can't preview $dir", e)
            }
        }
        visit(path, 1)
        for (d in subdirs) {
            if (found.size >= MAX_CANDIDATES) break
            visit(d, 2)
        }
        if (found.isEmpty()) {
            // Covers first; one audio file only (an album's files share one picture), text last.
            val firstAudio = secondary.firstOrNull { it.other == OtherKind.AUDIO }
            val others = secondary.filter { it.other != OtherKind.AUDIO }.sortedBy { SECONDARY.indexOf(it.other) }
            return FolderScan(listOfNotNull(firstAudio) + others, count, audio)
        }
        val ranked = found.sortedBy { e ->
            when (e.type) {
                MediaType.IMAGE -> if (e.size in 50_000..3_000_000) 0 else 1
                MediaType.ANIMATED -> 2
                else -> 3
            }
        }
        return FolderScan(ranked, count, audio)
    }

    companion object {
        /** Previews for folders without media, in collage order (audio: one file). */
        private val SECONDARY = listOf(OtherKind.BOOK, OtherKind.PDF, OtherKind.VECTOR, OtherKind.DOCUMENT, OtherKind.AUDIO, OtherKind.TEXT, OtherKind.CODE)
        private const val PAPER = 0xFFF6F2E9.toInt()
        private const val INK = 0xFF2B2B2B.toInt()

        /** MediaStore answers fast or not at all (an uncached thumbnail is made synchronously). */
        private const val MEDIASTORE_MS = 1_500L

        /** A still PNG's answer, never cached. */
        private val STILL = MediaMeta(0, 0, 1, 0)

        /** Office formats that are ZIPs with a thumbnail inside. */
        private val OFFICE_ZIPS = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "odg")
        /** SVGs larger than this are maps or exports, slow to draw: they keep the icon. */
        private const val MAX_SVG_BYTES = 8L * 1024 * 1024
        const val MIN_CACHE = 100
        /** A thumbnail up to 25 % smaller than its tile is still sharp enough. */
        const val UPSCALE_SLACK = 1.25f
        const val DECODE_MS = 5_000L
        const val SCAN_MS = 3_000L
        const val LARGE_GIF_MS = 60_000L
        const val MAX_CANDIDATES = 20
        /** Thumbnail work must have been idle this long before timed-out files are tried again. */
        const val IDLE_RETRY_MS = 1_500L
        /** Watchdogs grow ×2 per timeout of a file, at most ×8 (a 5 s decode gets up to 40 s). */
        const val MAX_SCALE_SHIFT = 3
        val FRACTIONS = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
        /** Slow files (first frame over [SLOW_FIRST_FRAME_MS]) keep only these after the first. */
        private val SLOW_FRACTIONS = setOf(0.5f, 0.9f)
        const val SLOW_FIRST_FRAME_MS = 800L

        /** WebP files may be animated: their tiles take the strip path, which shows a still WebP as an image (todo §10.2). */
        /** WebP and PNG files may be animated (todo §10.2, §10.10): the tile takes the strip path and finds out. */
        fun mayAnimate(entry: FileEntry) = entry.type == MediaType.IMAGE && FileTypes.extension(entry.name).let { it == "webp" || it == "png" || it == "apng" }

        private fun isWebp(entry: FileEntry) = entry.name.endsWith(".webp", ignoreCase = true)
    }
}
