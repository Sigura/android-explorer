package com.mediaviewer.data

import com.mediaviewer.data.thumbs.GifCodec
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in check against real files (README §8.1). Set MV_GIF_REF to a JSON list of
 * {path, w, h, frames, dur} made with a reference decoder (Pillow); skipped otherwise.
 */
class RealGifTest {
    @Test fun matchesReferenceDecoder() {
        val ref = System.getenv("MV_GIF_REF")?.let(::File)
        assumeTrue(ref != null && ref.exists())
        val items = JSONArray(ref!!.readText())
        var checked = 0
        var failed = 0
        var mismatches = 0
        val t0 = System.nanoTime()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val f = File(o.getString("path"))
            if (o.has("error")) {
                try {
                    GifCodec.scan(f)
                } catch (e: Exception) {
                    failed++
                }
                continue
            }
            val info = GifCodec.scan(f)
            checked++
            if (info.frameCount != o.getInt("frames") || info.durationMs != o.getLong("dur") ||
                info.width != o.getInt("w") || info.height != o.getInt("h")
            ) {
                mismatches++
                println("MISMATCH ${f.name}: ours $info, ref $o")
            }
            // Decode the 5 strip frames; must not throw on any real file.
            val targets = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
                .map { (it * info.frameCount).toInt().coerceIn(0, info.frameCount - 1) }.distinct().toIntArray()
            var got = 0
            GifCodec.decodeFrames(f, targets, { false }) { _, _, _, _ -> got++ }
            assertEquals(f.name, targets.size, got)
        }
        println("checked $checked, rejected broken $failed, mismatches $mismatches, ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(0, mismatches)
    }
}
