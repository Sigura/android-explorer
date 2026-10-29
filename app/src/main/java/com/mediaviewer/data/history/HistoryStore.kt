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
    /** The current file's place in the queue (0-based) and the queue length; -1 / 0 = not saved (v1 rows). */
    val index: Int = -1,
    val total: Int = 0,
    /** The current file's duration; 0 = not known. */
    val durationMs: Long = 0,
    /** Music mode (todo §3.1): shuffled with [seed], [first] pinned at the front of the cycle. */
    val shuffle: Boolean = false,
    val seed: Long = 0,
    val first: String? = null,
    /** Music mode's repeat: `Player.REPEAT_MODE_OFF` (0), `ONE` (1) or `ALL` (2). */
    val repeat: Int = 0,
    /** Lecture override (todo §3.6): [LECTURE_AUTO], [LECTURE_YES] or [LECTURE_NO]. */
    val lecture: Int = LECTURE_AUTO,
    /** Lyrics / subtitle lines shown on the player screen (todo §4.6). */
    val cc: Boolean = true,
)

const val LECTURE_AUTO = 0
const val LECTURE_YES = 1
const val LECTURE_NO = 2

/** One file's saved position. */
data class FileHistory(val path: String, val positionMs: Long, val finished: Boolean)

/** A file a book's history recorded: its name in the folder and its size then. */
data class FileRecord(val name: String, val size: Long)

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
                "finished INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0, " +
                "idx INTEGER NOT NULL DEFAULT -1, total INTEGER NOT NULL DEFAULT 0, duration INTEGER NOT NULL DEFAULT 0, first TEXT, " +
                "repeat INTEGER NOT NULL DEFAULT 0, lecture INTEGER NOT NULL DEFAULT 0, cc INTEGER NOT NULL DEFAULT 1)",
        )
        db.execSQL(
            "CREATE TABLE audio_file (path TEXT PRIMARY KEY, size INTEGER NOT NULL DEFAULT 0, " +
                "position INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX audio_folder_played ON audio_folder (played)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations go here, one `if (oldVersion < n)` step per version. Never drop the tables.
        if (oldVersion < 2) {
            // Where we are in the book, for the folder tiles (todo §10.1).
            db.execSQL("ALTER TABLE audio_folder ADD COLUMN idx INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE audio_folder ADD COLUMN total INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE audio_folder ADD COLUMN duration INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // The pinned first file of a shuffled cycle (todo §10.4), music mode's repeat and the CC
            // switch (§4.6), the lecture override (§3.6); `shuffle` and `seed` existed from v1.
            db.execSQL("ALTER TABLE audio_folder ADD COLUMN first TEXT")
            db.execSQL("ALTER TABLE audio_folder ADD COLUMN repeat INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE audio_folder ADD COLUMN lecture INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE audio_folder ADD COLUMN cc INTEGER NOT NULL DEFAULT 1")
        }
    }

    fun folder(path: String): FolderHistory? = query {
        readableDatabase.rawQuery(
            "SELECT $FOLDER_COLUMNS FROM audio_folder WHERE path = ?",
            arrayOf(path),
        ).use { c -> if (c.moveToFirst()) readFolder(c) else null }
    }

    /** The most recently played books, newest first (the root screen's "Continue listening"). */
    fun recentFolders(limit: Int): List<FolderHistory> = query {
        readableDatabase.rawQuery(
            "SELECT $FOLDER_COLUMNS FROM audio_folder WHERE played > 0 ORDER BY played DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(readFolder(c)) }
        }
    } ?: emptyList()

    /** The books directly inside [parent], by path (one query for a folder view's tiles, todo §10.1). */
    fun foldersIn(parent: String): Map<String, FolderHistory> = query {
        val prefix = parent.trimEnd('/') + "/"
        readableDatabase.rawQuery(
            "SELECT $FOLDER_COLUMNS FROM audio_folder WHERE path >= ? AND path < ?",
            arrayOf(prefix, prefix.dropLast(1) + "0"), // '0' sorts right after '/'
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val h = readFolder(c)
                    if (h.folder.indexOf('/', prefix.length) < 0) put(h.folder, h)
                }
            }
        }
    } ?: emptyMap()

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

    /**
     * Saves the current file and position of its book, in one transaction.
     * @param index the file's place in the queue (0-based) of [total] files; [durationMs] its length (0 = unknown).
     */
    fun save(
        folder: String, path: String, size: Long, positionMs: Long, fileFinished: Boolean, speed: Float,
        index: Int = -1, total: Int = 0, durationMs: Long = 0,
    ) {
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
                put("idx", index)
                put("total", total)
                put("duration", durationMs)
            }
            if (db.update("audio_folder", values, "path = ?", arrayOf(folder)) == 0) {
                values.put("path", folder)
                db.insert("audio_folder", null, values)
            }
        }
    }

    /**
     * Music mode on or off for [folder] (todo §3.1). The order is rebuilt from [seed] and [first];
     * a folder never played gets a row with no current file.
     */
    fun setShuffle(folder: String, on: Boolean, seed: Long, first: String?) = write { db ->
        val values = ContentValues().apply {
            put("shuffle", if (on) 1 else 0)
            put("seed", seed)
            put("first", first)
            // Books never repeat.
            if (!on) put("repeat", 0)
        }
        if (db.update("audio_folder", values, "path = ?", arrayOf(folder)) == 0) {
            values.put("path", folder)
            db.insert("audio_folder", null, values)
        }
    }

    /** Sets one column of [folder]'s row, creating a row (no current file: nothing heard) when there is none. */
    private fun setFolderValue(folder: String, column: String, value: Int) = write { db ->
        val values = ContentValues().apply { put(column, value) }
        if (db.update("audio_folder", values, "path = ?", arrayOf(folder)) == 0) {
            values.put("path", folder)
            db.insert("audio_folder", null, values)
        }
    }

    /** The lecture override of [folder] (todo §3.6): [LECTURE_AUTO], [LECTURE_YES] or [LECTURE_NO]. */
    fun setLecture(folder: String, mode: Int) = setFolderValue(folder, "lecture", mode)

    /** Lyrics / subtitle lines on the player screen for [folder] (todo §4.6). */
    fun setCc(folder: String, on: Boolean) = setFolderValue(folder, "cc", if (on) 1 else 0)

    /** One file's saved position, or null (the slideshow and the external player start there, todo §3.6). */
    fun file(path: String): FileHistory? = query {
        readableDatabase.rawQuery("SELECT path, position, finished FROM audio_file WHERE path = ?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) FileHistory(c.getString(0), c.getLong(1), c.getInt(2) != 0) else null
        }
    }

    /** Updates a file's position when it already has one (watched in the slideshow, todo §3.6); never adds files. */
    fun updateFilePosition(path: String, positionMs: Long, finished: Boolean) = write { db ->
        db.update(
            "audio_file",
            ContentValues().apply {
                put("position", if (finished) 0 else positionMs)
                put("finished", if (finished) 1 else 0)
                put("played", System.currentTimeMillis())
            },
            "path = ?",
            arrayOf(path),
        )
    }

    /** Books whose folder is gone, with the files their history recorded: candidates for a moved book (todo §3.4). */
    fun orphans(exists: (String) -> Boolean): List<Pair<FolderHistory, List<FileRecord>>> = query {
        val gone = readableDatabase.rawQuery("SELECT $FOLDER_COLUMNS FROM audio_folder WHERE current IS NOT NULL", null).use { c ->
            buildList { while (c.moveToNext()) add(readFolder(c)) }
        }.filterNot { exists(it.folder) }
        gone.map { h ->
            val prefix = h.folder.trimEnd('/') + "/"
            val files = readableDatabase.rawQuery(
                "SELECT path, size FROM audio_file WHERE path >= ? AND path < ?",
                arrayOf(prefix, prefix.dropLast(1) + "0"),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val p = c.getString(0)
                        if (p.indexOf('/', prefix.length) < 0) add(FileRecord(p.substring(prefix.length), c.getLong(1)))
                    }
                }
            }
            h to files
        }
    } ?: emptyList()

    /** A book's history moves from [from] to [to] (renamed or moved folder), files and all, in one transaction. */
    fun moveBook(from: String, to: String) = write { db ->
        val old = from.trimEnd('/') + "/"
        val new = to.trimEnd('/') + "/"
        // A row with only settings (CC, lecture override) may exist for the new path: the moved history wins.
        db.delete("audio_folder", "path = ? AND current IS NULL", arrayOf(to))
        db.execSQL(
            "UPDATE audio_file SET path = ? || substr(path, ?) WHERE path >= ? AND path < ?",
            arrayOf<Any>(new, old.length + 1, old, old.dropLast(1) + "0"),
        )
        db.execSQL(
            "UPDATE audio_folder SET path = ?, current = CASE WHEN current IS NULL THEN NULL ELSE ? || substr(current, ?) END, " +
                "first = CASE WHEN first IS NULL THEN NULL ELSE ? || substr(first, ?) END WHERE path = ?",
            arrayOf<Any>(to, new, old.length + 1, new, old.length + 1, from),
        )
    }

    /** Music mode's repeat for [folder] (`Player.REPEAT_MODE_*`). */
    fun setRepeat(folder: String, mode: Int) = write { db ->
        db.update("audio_folder", ContentValues().apply { put("repeat", mode) }, "path = ?", arrayOf(folder))
    }

    /** The last file of the book ended: the next Play asks to start over. */
    fun markBookFinished(folder: String, finished: Boolean) = write { db ->
        db.update("audio_folder", ContentValues().apply { put("finished", if (finished) 1 else 0) }, "path = ?", arrayOf(folder))
    }

    /** Start over: forget the book's position and every file's. Music mode and its order stay. */
    fun reset(folder: String) = write { db ->
        val prefix = folder.trimEnd('/') + "/"
        db.delete("audio_file", "path >= ? AND path < ?", arrayOf(prefix, prefix.dropLast(1) + "0"))
        db.update(
            "audio_folder",
            ContentValues().apply {
                putNull("current")
                put("position", 0)
                put("finished", 0)
                put("idx", -1)
                put("duration", 0)
            },
            "path = ?",
            arrayOf(folder),
        )
    }

    /** Marks one file heard to the end, or not heard (the player screen's list, todo §4.1). */
    fun setFileFinished(path: String, finished: Boolean) = write { db ->
        db.insertWithOnConflict(
            "audio_file",
            null,
            ContentValues().apply {
                put("path", path)
                put("size", java.io.File(path).length())
                put("position", 0)
                put("finished", if (finished) 1 else 0)
                put("played", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun readFolder(c: android.database.Cursor) =
        FolderHistory(
            c.getString(0), c.getString(1), c.getLong(2), c.getFloat(3), c.getInt(4) != 0, c.getLong(5),
            index = c.getInt(6), total = c.getInt(7), durationMs = c.getLong(8),
            shuffle = c.getInt(9) != 0, seed = c.getLong(10), first = c.getString(11), repeat = c.getInt(12),
            lecture = c.getInt(13), cc = c.getInt(14) != 0,
        )

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
        const val VERSION = 3
        const val FOLDER_COLUMNS = "path, current, position, speed, finished, played, idx, total, duration, shuffle, seed, first, repeat, lecture, cc"
        const val TAG = "MediaViewer"
    }
}
