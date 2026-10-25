package com.mediaviewer.data.meta

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

/** Duration and size of an animated image or video. */
data class MediaMeta(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val durationMs: Long,
)

/**
 * Small SQLite cache of GIF block scans and video metadata (README §4.3 and §7.4),
 * keyed by path + size + mtime. A few dozen bytes per file; entries unused for 90 days are pruned.
 */
class MetaStore(context: Context) : SQLiteOpenHelper(context, "meta.db", null, 1) {
    private val memory = ConcurrentHashMap<String, MediaMeta>()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE meta (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, " +
                "w INTEGER, h INTEGER, frames INTEGER, duration INTEGER, used INTEGER)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
            db.delete("meta", "used < ?", arrayOf(cutoff.toString()))
        }
    }

    private fun memKey(path: String, size: Long, mtime: Long) = "$path|$size|$mtime"

    fun get(path: String, size: Long, mtime: Long): MediaMeta? {
        memory[memKey(path, size, mtime)]?.let { return it }
        return try {
            readableDatabase.rawQuery(
                "SELECT w, h, frames, duration FROM meta WHERE path = ? AND size = ? AND mtime = ?",
                arrayOf(path, size.toString(), mtime.toString()),
            ).use { c ->
                if (!c.moveToFirst()) return null
                MediaMeta(c.getInt(0), c.getInt(1), c.getInt(2), c.getLong(3)).also {
                    memory[memKey(path, size, mtime)] = it
                    touch(path)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun put(path: String, size: Long, mtime: Long, meta: MediaMeta) {
        memory[memKey(path, size, mtime)] = meta
        try {
            writableDatabase.insertWithOnConflict(
                "meta",
                null,
                ContentValues().apply {
                    put("path", path)
                    put("size", size)
                    put("mtime", mtime)
                    put("w", meta.width)
                    put("h", meta.height)
                    put("frames", meta.frameCount)
                    put("duration", meta.durationMs)
                    put("used", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } catch (e: Exception) {
            // The cache is an optimisation only.
        }
    }

    private fun touch(path: String) {
        try {
            writableDatabase.execSQL("UPDATE meta SET used = ? WHERE path = ?", arrayOf<Any>(System.currentTimeMillis(), path))
        } catch (e: Exception) {
        }
    }
}
