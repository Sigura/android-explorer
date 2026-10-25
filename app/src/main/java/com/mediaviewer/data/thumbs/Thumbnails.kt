package com.mediaviewer.data.thumbs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mediaviewer.data.ErrorReporter
import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.data.meta.MediaMeta
import com.mediaviewer.data.meta.MetaStore
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
        val gif: Boolean,
        val complete: Boolean,
    ) : Thumb

    /**
     * First lines of a text file (README §3.10): glyph coverage only (`ALPHA_8`), tinted by the tile.
     * [textPx] is the font size it was drawn with; a new system font size draws it again.
     */
    class Text(val image: ImageBitmap, val textPx: Float) : Thumb

    /** Folder collage (README §4.1): candidates chosen cheap-first, shown 4 at a time. */
    class Folder(
        val candidates: List<FileEntry>,
        val itemCount: Int,
        val groups: ConcurrentHashMap<Int, List<Still>>,
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

    /** Text files without a detectable encoding (setting "Text files without encoding"). */
    @Volatile var legacyCharset: Charset = Charsets.ISO_8859_1

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
        if (hit is Thumb.Text) return hit.takeIf { it.textPx == textPx }
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
            when (entry.type) {
                MediaType.IMAGE -> emit(cachePut(entry.path, width, Thumb.Image(still(entry.path, width, height))))
                MediaType.ANIMATED -> gif(entry, width, height, full, firstOnly) { emit(it) }
                MediaType.VIDEO -> video(entry, width, height, firstOnly) { emit(it) }
                MediaType.FOLDER -> emit(cachePut(entry.path, width, folder(entry.path, width, height)))
                MediaType.OTHER -> text(entry, width, height)?.let { emit(cachePut(entry.path, width, it)) }
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

    // ---------------------------------------------------------------- video

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
                    for (f in FRACTIONS) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val bmp = videoFrame(r, (m.durationMs * f * 1000).toLong(), m, w, h) ?: continue
                        frames.add(bmp.asImageBitmap())
                        if (frames.size == 1) {
                            first.complete(Thumb.Strip(frames.toList(), m.width, m.height, m.durationMs, gif = false, complete = false))
                            if (firstOnly) break
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

    private fun videoFrame(r: MediaMetadataRetriever, us: Long, m: MediaMeta, w: Int, h: Int): Bitmap? {
        val sw = if (m.width > 0) m.width else w
        val sh = if (m.height > 0) m.height else h
        val s = coverScale(sw, sh, w, h)
        val dw = max(1, (sw * s).roundToInt())
        val dh = max(1, (sh * s).roundToInt())
        return if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dw, dh)
        } else {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                Bitmap.createScaledBitmap(full, dw, dh, true).also { if (it !== full) full.recycle() }
            }
        }
    }

    // ---------------------------------------------------------------- text (README §3.10)

    /** Text files, code and extensionless files that turn out to be text; null = keep the icon. */
    private suspend fun text(entry: FileEntry, w: Int, h: Int): Thumb.Text? {
        if (!(entry.other.hasTextPreview || entry.other == OtherKind.UNKNOWN) || entry.path in notText) return null
        val px = textPx
        val pad = textPadPx
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
                text,
                truncated = entry.size > TextDecode.READ_BYTES,
                width = w,
                height = h,
                textPx = px,
                padPx = pad,
                monospace = FileTypes.monospace(entry.name, entry.other),
                markdown = ext == "md" || ext == "markdown",
            )
        } ?: return null
        return Thumb.Text(bitmap.asImageBitmap(), px)
    }

    // ---------------------------------------------------------------- folders

    private suspend fun folder(path: String, w: Int, h: Int): Thumb.Folder {
        val (candidates, count) = watchdog(SCAN_MS) { folderCandidates(path) }
        val t = Thumb.Folder(candidates, count, ConcurrentHashMap())
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

    private suspend fun previewStill(e: FileEntry, w: Int, h: Int): Still = when (e.type) {
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
        else -> still(e.path, w, h)
    }

    /**
     * Cheap-first preview candidates (README §4.1): no decoding, at most 2 levels deep,
     * stops after [MAX_CANDIDATES]. Mid-size stills first, then GIFs, then videos.
     */
    private fun folderCandidates(path: String): Pair<List<FileEntry>, Int> {
        val found = ArrayList<FileEntry>()
        var count = 0
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
        val ranked = found.sortedBy { e ->
            when (e.type) {
                MediaType.IMAGE -> if (e.size in 50_000..3_000_000) 0 else 1
                MediaType.ANIMATED -> 2
                else -> 3
            }
        }
        return ranked to count
    }

    companion object {
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
    }
}
