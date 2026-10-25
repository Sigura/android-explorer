package com.mediaviewer.data.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/** Where a book stopped (todo §3.4). */
data class FolderHistory(
    val folder: String,
    val currentPath: String?,
    val positionMs: Long,
    val speed: Float,
    val finished: Boolean,
    val playedAt: Long,
)

/** One file's saved position. */
data class FileHistory(val path: String, val positionMs: Long, val finished: Boolean)

/**
 * Listening history (todo §3.4) in its own database, `history.db`. Unlike `MetaStore`, which is a
 * disposable cache, this is user data: never pruned, and schema changes migrate instead of dropping.
 * Every call blocks on disk: call from `Dispatchers.IO`, never the main thread.
 */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, "history.db", null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE audio_folder (path TEXT PRIMARY KEY, current TEXT, position INTEGER NOT NULL DEFAULT 0, " +
                "speed REAL NOT NULL DEFAULT 1, shuffle INTEGER NOT NULL DEFAULT 0, seed INTEGER NOT NULL DEFAULT 0, " +
                "finished INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE audio_file (path TEXT PRIMARY KEY, size INTEGER NOT NULL DEFAULT 0, " +
                "position INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX audio_folder_played ON audio_folder (played)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations go here, one `if (oldVersion < n)` step per version. Never drop the tables.
    }

    fun folder(path: String): FolderHistory? = query {
        readableDatabase.rawQuery(
            "SELECT path, current, position, speed, finished, played FROM audio_folder WHERE path = ?",
            arrayOf(path),
        ).use { c -> if (c.moveToFirst()) readFolder(c) else null }
    }

    /** The most recently played books, newest first (the root screen's "Continue listening"). */
    fun recentFolders(limit: Int): List<FolderHistory> = query {
        readableDatabase.rawQuery(
            "SELECT path, current, position, speed, finished, played FROM audio_folder WHERE played > 0 ORDER BY played DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(readFolder(c)) }
        }
    } ?: emptyList()

    /** Saved positions of the files in [folder] (one query for the whole queue). */
    fun files(folder: String): Map<String, FileHistory> = query {
        val prefix = folder.trimEnd('/') + "/"
        readableDatabase.rawQuery(
            "SELECT path, position, finished FROM audio_file WHERE path >= ? AND path < ?",
            arrayOf(prefix, prefix.dropLast(1) + "0"), // '0' sorts right after '/'
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val p = c.getString(0)
                    if (p.indexOf('/', prefix.length) < 0) put(p, FileHistory(p, c.getLong(1), c.getInt(2) != 0))
                }
            }
        }
    } ?: emptyMap()

    /** Saves the current file and position of its book, in one transaction. */
    fun save(folder: String, path: String, size: Long, positionMs: Long, fileFinished: Boolean, speed: Float) {
        val now = System.currentTimeMillis()
        write { db ->
            db.insertWithOnConflict(
                "audio_file",
                null,
                ContentValues().apply {
                    put("path", path)
                    put("size", size)
                    put("position", if (fileFinished) 0 else positionMs)
                    put("finished", if (fileFinished) 1 else 0)
                    put("played", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            val values = ContentValues().apply {
                put("current", path)
                put("position", positionMs)
                put("speed", speed)
                put("finished", 0)
                put("played", now)
            }
            if (db.update("audio_folder", values, "path = ?", arrayOf(folder)) == 0) {
                values.put("path", folder)
                db.insert("audio_folder", null, values)
            }
        }
    }

    /** The last file of the book ended: the next Play asks to start over. */
    fun markBookFinished(folder: String, finished: Boolean) = write { db ->
        db.update("audio_folder", ContentValues().apply { put("finished", if (finished) 1 else 0) }, "path = ?", arrayOf(folder))
    }

    /** Start over: forget the book's position and every file's. */
    fun reset(folder: String) = write { db ->
        val prefix = folder.trimEnd('/') + "/"
        db.delete("audio_file", "path >= ? AND path < ?", arrayOf(prefix, prefix.dropLast(1) + "0"))
        db.delete("audio_folder", "path = ?", arrayOf(folder))
    }

    private fun readFolder(c: android.database.Cursor) =
        FolderHistory(c.getString(0), c.getString(1), c.getLong(2), c.getFloat(3), c.getInt(4) != 0, c.getLong(5))

    private inline fun <T> query(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "History read failed", e)
        null
    }

    private inline fun write(block: (SQLiteDatabase) -> Unit) {
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                block(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(TAG, "History write failed", e)
        }
    }

    private companion object {
        const val VERSION = 1
        const val TAG = "MediaViewer"
    }
}
