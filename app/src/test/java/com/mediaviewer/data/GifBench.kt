package com.mediaviewer.data

import com.mediaviewer.data.thumbs.GifCodec
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in timing of scan vs. frame decode on real files (MV_GIF_REF, see [RealGifTest]). */
class GifBench {
    @Test fun bench() {
        val ref = System.getenv("MV_GIF_REF")?.let(::File)
        assumeTrue(ref != null && ref.exists() && System.getenv("MV_GIF_BENCH") != null)
        val items = JSONArray(ref!!.readText())
        val files = (0 until items.length()).map { items.getJSONObject(it) }
            .filter { !it.has("error") }.map { File(it.getString("path")) }
            .filter { it.length() in 5_000_000..30_000_000 }.take(20)
        repeat(2) { round ->
            var scanNs = 0L
            var decNs = 0L
            var firstNs = 0L
            var bytes = 0L
            for (f in files) {
                bytes += f.length()
                var t = System.nanoTime()
                val info = GifCodec.scan(f)
                scanNs += System.nanoTime() - t
                t = System.nanoTime()
                GifCodec.decodeFrames(f, intArrayOf(0), { false }) { _, _, _, _ -> }
                firstNs += System.nanoTime() - t
                val targets = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
                    .map { (it * info.frameCount).toInt().coerceIn(0, info.frameCount - 1) }.distinct().toIntArray()
                t = System.nanoTime()
                GifCodec.decodeFrames(f, targets, { false }) { _, _, _, _ -> }
                decNs += System.nanoTime() - t
            }
            println(
                "round $round: ${files.size} files, ${bytes / 1_000_000} MB; per file: scan ${scanNs / files.size / 1_000_000} ms, " +
                    "first frame ${firstNs / files.size / 1_000_000} ms, 5-frame pass ${decNs / files.size / 1_000_000} ms",
            )
        }
    }
}
