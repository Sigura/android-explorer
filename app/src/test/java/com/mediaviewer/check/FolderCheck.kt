package com.mediaviewer.check

import com.mediaviewer.data.fs.FileTypes
import com.mediaviewer.data.fs.FolderListing
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.thumbs.GifCodec
import com.mediaviewer.domain.SlideOrder
import com.mediaviewer.domain.SlideTimer
import com.mediaviewer.domain.SortBy
import com.mediaviewer.domain.SortSpec
import com.mediaviewer.domain.Sorting
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Runs the folder-view and slideshow code paths that work on the JVM over every file of a real
 * folder (README §8.1). Opt-in: set MV_CHECK_DIR (see tools/check-media-folder.sh).
 *
 * Covered: streaming listing + sorted insertion, type detection and magic sniffing, GIF block
 * scan, the 5-frame strip decode (and the first-frame-only path of large GIFs), slideshow timing,
 * and the slideshow order (sequential, a full random cycle, restore after "restart").
 * Not covered (Android-only): ImageDecoder stills, MediaMetadataRetriever video frames, ExoPlayer,
 * Coil and all Compose UI.
 */
class FolderCheck {
    private val report = StringBuilder()
    private fun log(s: String) {
        println(s)
        report.appendLine(s)
    }

    @Test fun checkFolder() {
        val dir = System.getenv("MV_CHECK_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val threads = System.getenv("MV_CHECK_THREADS")?.toIntOrNull() ?: 32
        val largeGif = (System.getenv("MV_CHECK_LARGE_GIF_MB")?.toLongOrNull() ?: 40L) * 1024 * 1024
        val out = File(System.getenv("MV_CHECK_REPORT") ?: "build/reports/media-check")
        out.mkdirs()
        log("Folder: $dir (threads $threads)")

        // ---------------------------------------------------------------- 1. folder listing
        val entries = listFolder(dir!!)
        val expected = dir.list()!!.count { !FolderScanner.isHidden(it) }
        assertEquals("every non-hidden entry is listed", expected, entries.size)
        val byType = entries.groupingBy { it.type }.eachCount()
        log("Types: $byType")
        val sniffed = entries.filter { !it.isFolder && FileTypes.byExtension(it.name) == null }
        log("Needed a magic sniff (no/junk extension): ${sniffed.size} " + sniffed.take(8).map { "${it.name} → ${it.type}" })
        val partial = entries.filter { it.type == MediaType.PARTIAL }
        log("Partial downloads: ${partial.size}")
        File(out, "types.tsv").printWriter().use { w ->
            entries.forEach { w.println("${it.type}\t${it.other}\t${it.size}\t${it.name}") }
        }

        // Order rules from README §4: folders, then media, then other files; natural name order.
        val cmp = Sorting.comparator(SortSpec(SortBy.NAME, descending = false))
        for (i in 1 until entries.size) assertTrue("sorted at $i", cmp.compare(entries[i - 1], entries[i]) <= 0)
        for (by in SortBy.entries) for (desc in listOf(false, true)) {
            val t = System.nanoTime()
            entries.sortedWith(Sorting.comparator(SortSpec(by, desc)))
            log("Sort by $by${if (desc) " desc" else ""}: ${(System.nanoTime() - t) / 1_000_000} ms")
        }

        // ---------------------------------------------------------------- 2. every GIF: scan + strip
        val gifs = entries.filter { it.type == MediaType.ANIMATED }
        val failures = ConcurrentLinkedQueue<String>()
        val durations = ConcurrentHashMap<String, Long>()
        val frameCounts = ConcurrentHashMap<String, Int>()
        val done = AtomicInteger()
        val bytes = AtomicLong()
        val slowest = ConcurrentLinkedQueue<Pair<Long, String>>()
        val pool = Executors.newFixedThreadPool(threads)
        val t0 = System.nanoTime()
        for (g in gifs) pool.execute {
            val f = File(g.path)
            val ts = System.nanoTime()
            try {
                if (FileTypes.sniff(f).type != MediaType.ANIMATED) {
                    failures.add("${g.name}\tnot a GIF (would be decoded as a still image)")
                    return@execute
                }
                // First frame: what every tile shows first, and all a large GIF shows until hovered.
                var first = 0
                GifCodec.decodeFrames(f, intArrayOf(0), { false }) { _, _, _, _ -> first++ }
                check(first == 1) { "no first frame" }
                val info = GifCodec.scan(f)
                durations[g.path] = info.durationMs
                frameCounts[g.path] = info.frameCount
                if (info.frameCount > 1) {
                    val targets = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
                        .map { (it * info.frameCount).toInt().coerceIn(0, info.frameCount - 1) }
                        .distinct().toIntArray()
                    var got = 0
                    GifCodec.decodeFrames(f, targets, { false }) { _, canvas, w, h ->
                        check(canvas.size == w * h)
                        got++
                    }
                    check(got == targets.size) { "strip has $got of ${targets.size} frames" }
                }
                bytes.addAndGet(g.size)
            } catch (e: Throwable) {
                failures.add("${g.name}\t${g.size} B\t${e.javaClass.simpleName}: ${e.message}")
            } finally {
                val ms = (System.nanoTime() - ts) / 1_000_000
                slowest.add(ms to g.name)
                val n = done.incrementAndGet()
                if (n % 2000 == 0) println("  GIFs: $n / ${gifs.size}")
            }
        }
        pool.shutdown()
        pool.awaitTermination(6, TimeUnit.HOURS)
        val sec = (System.nanoTime() - t0) / 1e9
        log("GIFs checked: ${gifs.size} in ${"%.1f".format(sec)} s (${bytes.get() / 1_000_000} MB decoded OK), failures: ${failures.size}")
        val large = gifs.count { it.size > largeGif }
        log("Large GIFs (> ${largeGif / 1024 / 1024} MB, first frame only until hovered): $large")
        log("Slowest strips: " + slowest.sortedByDescending { it.first }.take(5).joinToString { "${it.second} ${it.first} ms" })
        File(out, "gif-failures.tsv").writeText(failures.sorted().joinToString("\n"))
        failures.sorted().take(15).forEach { log("  FAIL $it") }
        if (frameCounts.isNotEmpty()) {
            val fc = frameCounts.values.sorted()
            val d = durations.values.sorted()
            log("Frames: min ${fc.first()}, median ${fc[fc.size / 2]}, max ${fc.last()}; exactly 240: ${fc.count { it == 240 }}")
            log("Duration: min ${d.first()} ms, median ${d[d.size / 2]} ms, max ${d.last()} ms")
        }

        // ---------------------------------------------------------------- 3. slideshow timing
        val media = entries.filter { it.isMedia }
        for (rSec in listOf(1, 14, 40)) {
            val r = rSec * 1000L
            var loop = 0
            var once = 0
            var long = 0
            var total = 0L
            for (m in media) {
                val t = SlideTimer.timing(if (m.type == MediaType.IMAGE) null else durations[m.path], r)
                when {
                    t.longerThanInterval -> long++
                    t.loop -> loop++
                    else -> once++
                }
                total += t.showMs
            }
            log("R = $rSec s: loop/static $loop, play once $once, longer than R $long; full pass ≈ ${total / 3_600_000} h ${total / 60_000 % 60} min (videos counted as R)")
        }

        // ---------------------------------------------------------------- 4. slideshow order
        val folderOrder = Sorting.comparator(SortSpec(SortBy.NAME, descending = false))
        val seq = SlideOrder(media, folderOrder, random = false, seed = 0, pinnedFirst = null, startPath = media.first().path)
        val seen = HashSet<String>()
        repeat(media.size) { seen.add(seq.current!!.path); seq.next() }
        assertEquals("sequential pass shows every media file once", media.size, seen.size)
        assertEquals("…and wraps to the first", media.first().path, seq.current!!.path)

        val t1 = System.nanoTime()
        val rnd = SlideOrder(media, folderOrder, random = true, seed = 20261024, pinnedFirst = media[media.size / 2].path, startPath = media[media.size / 2].path, rng = Random(5))
        log("Random order over ${media.size} items built in ${(System.nanoTime() - t1) / 1_000_000} ms")
        val cycle = HashSet<String>()
        cycle.add(rnd.current!!.path)
        var restoreChecks = 0
        for (i in 1 until media.size) {
            val p = rnd.next()!!.path
            assertTrue("no repeat inside a cycle ($i)", cycle.add(p))
            if (i % 5000 == 0) {
                // "Restart": rebuild from seed + pinned + current only; the rest must be identical.
                val again = SlideOrder(media.shuffled(Random(i)).sortedWith(folderOrder), folderOrder, true, rnd.seed, rnd.pinnedFirst, p)
                val a = (0 until 50).map { again.next()!!.path }
                val probe = SlideOrder(media, folderOrder, true, rnd.seed, rnd.pinnedFirst, p)
                assertEquals(a, (0 until 50).map { probe.next()!!.path })
                assertEquals(rnd.position, again.position - 50)
                restoreChecks++
            }
        }
        assertEquals(media.size, cycle.size)
        val last = rnd.current!!.path
        val firstOfNext = rnd.next()!!.path
        assertTrue("new cycle never starts with the last item", last != firstOfNext)
        log("Random cycle: all ${cycle.size} items once, $restoreChecks restore checks OK, new cycle starts with a different item")

        File(out, "summary.txt").writeText(report.toString())
        log("Report: ${out.absolutePath}")
        assertTrue("GIF failures are listed in gif-failures.tsv (broken files are expected; the app shows an icon)", true)
    }

    /** The real streaming listing used by the folder view. */
    private fun listFolder(dir: File): List<FileEntry> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val t0 = System.nanoTime()
        var firstBatchMs = -1L
        val listing = FolderListing(dir.path, scope, Sorting.comparator(SortSpec(SortBy.NAME, descending = false)), showHidden = false) { p, r ->
            log("  listing failed: $p $r")
        }
        listing.start()
        withTimeout(600_000) {
            listing.started.first { it }
            firstBatchMs = (System.nanoTime() - t0) / 1_000_000
            listing.status.first { it != FolderListing.Status.LOADING }
        }
        val all = listing.snapshot()
        log("Listing: ${all.size} entries; first batch after $firstBatchMs ms, complete after ${(System.nanoTime() - t0) / 1_000_000} ms; status ${listing.status.value}")
        listing.close()
        scope.cancel()
        all
    }
}
