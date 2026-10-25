package com.mediaviewer.data

import com.mediaviewer.data.thumbs.GifCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Opt-in: dumps composited frames (ARGB) of real GIFs to MV_GIF_DUMP so they can be compared
 * pixel by pixel with a reference decoder.
 */
class GifDump {
    @Test fun dump() {
        val ref = System.getenv("MV_GIF_REF")?.let(::File)
        val dir = System.getenv("MV_GIF_DUMP")?.let(::File)
        assumeTrue(ref != null && ref.exists() && dir != null)
        dir!!.mkdirs()
        val items = JSONArray(ref!!.readText())
        val index = JSONArray()
        var n = 0
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (o.has("error") || n >= 25) continue
            val f = File(o.getString("path"))
            val frames = o.getInt("frames")
            val targets = intArrayOf(0, frames / 2, frames - 1).distinct().toIntArray()
            GifCodec.decodeFrames(f, targets, { false }) { fi, canvas, w, h ->
                val bb = ByteBuffer.allocate(canvas.size * 4)
                bb.asIntBuffer().put(canvas)
                val out = File(dir, "${n}_$fi.raw")
                out.writeBytes(bb.array())
                index.put(JSONObject().put("path", f.path).put("frame", fi).put("w", w).put("h", h).put("raw", out.path))
            }
            n++
        }
        File(dir, "index.json").writeText(index.toString())
    }
}
