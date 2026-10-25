package com.mediaviewer.data.fs

import org.json.JSONObject
import java.io.File

/**
 * Unsaved editor text, kept in app-private storage when the app goes to the background (todo §6.3),
 * so a killed app loses nothing. One small JSON file per path. Blocking: call on IO.
 */
class Drafts(private val dir: File) {
    class Draft(val text: String, val time: Long)

    private fun fileFor(path: String) = File(dir, Integer.toHexString(path.hashCode()) + "_" + path.length + ".json")

    fun put(path: String, text: String) {
        dir.mkdirs()
        val f = fileFor(path)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(JSONObject().put("path", path).put("text", text).put("time", System.currentTimeMillis()).toString())
        tmp.renameTo(f)
    }

    fun get(path: String): Draft? = try {
        val f = fileFor(path)
        if (!f.isFile) {
            null
        } else {
            val o = JSONObject(f.readText())
            // Two paths can share a file name: the stored path decides.
            if (o.optString("path") == path) Draft(o.getString("text"), o.optLong("time")) else null
        }
    } catch (e: Exception) {
        null
    }

    fun remove(path: String) {
        val f = fileFor(path)
        if (get(path) != null) f.delete()
    }
}
