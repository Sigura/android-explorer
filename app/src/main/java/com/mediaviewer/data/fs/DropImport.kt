package com.mediaviewer.data.fs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.coroutineContext

/**
 * Files and folders dropped into the folder view from another app (todo §10.5): My Files in DeX,
 * a browser's download bar, a mail app in split screen. Always a copy: the other app owns its files.
 * Our own tiles come back as our FileProvider URIs and are copied by path. Blocking: call on IO.
 */
object DropImport {
    /** The local path behind [uri], when it is a `file://` URI or one of our own FileProvider URIs. */
    fun localPath(uri: Uri, ownAuthority: String): String? = when {
        uri.scheme == ContentResolver.SCHEME_FILE -> uri.path
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == ownAuthority -> localPathOf(uri.encodedPath)
        else -> null
    }

    /** `/root/storage/emulated/0/a%20b.jpg` (our `root-path name="root"`) → `/storage/emulated/0/a b.jpg`. */
    fun localPathOf(encodedPath: String?): String? {
        val p = Uri.decode(encodedPath ?: return null)
        return if (p.startsWith("/root/")) p.removePrefix("/root") else null
    }

    /**
     * Copies [uris] into [destDir]. Names that exist get " (1)"; nothing is overwritten. Folders
     * are copied with their contents when the drag allows listing them, else reported.
     */
    suspend fun copyIn(
        context: Context,
        uris: List<Uri>,
        destDir: String,
        onProgress: (index: Int, total: Int, name: String) -> Unit,
    ): FileOps.Result {
        val resolver = context.contentResolver
        val own = context.packageName + ".files"
        val dest = File(destDir)
        val failed = ArrayList<Pair<String, String>>()
        val local = ArrayList<String>()
        var done = 0
        for ((i, uri) in uris.withIndex()) {
            coroutineContext.ensureActive()
            val path = localPath(uri, own)
            if (path != null) {
                when {
                    // Only shared storage: a crafted drop must not make us copy our own private files out.
                    !isShared(context, path) -> failed.add(path to "not a shared file")
                    // Dropped back where it came from: nothing to do, and no error either.
                    File(path).parentFile?.canonicalPath != dest.canonicalPath -> local.add(path)
                }
                continue
            }
            val name = displayName(resolver, uri)
            onProgress(i, uris.size, name)
            try {
                if (resolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val target = FileOps.freeName(dest, name)
                    try {
                        copyTree(resolver, uri, target)
                    } catch (e: Throwable) {
                        target.deleteRecursively() // no half-copied folder left behind
                        throw e
                    }
                } else {
                    copyStream(resolver, uri, FileOps.freeName(dest, name), lastModified(resolver, uri))
                }
                done++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Drop failed: $uri", e)
                failed.add(name to (e.message ?: e.javaClass.simpleName))
            }
        }
        if (local.isNotEmpty()) {
            val r = FileOps.transfer(local, destDir, move = false) { i, _, name -> onProgress(uris.size - local.size + i, uris.size, name) }
            done += r.done
            failed.addAll(r.failed)
        }
        return FileOps.Result(done, failed)
    }

    /** Not inside app-private storage (ours or another app's): `/data/…`, or our folders under `Android/`. */
    private fun isShared(context: Context, path: String): Boolean {
        val c = try {
            File(path).canonicalPath
        } catch (e: java.io.IOException) {
            return false
        }
        if (c.startsWith("/data/")) return false
        val own = (context.getExternalFilesDirs(null) + context.externalCacheDirs).mapNotNull { it?.parentFile?.canonicalPath }
        return own.none { c == it || c.startsWith("$it/") }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        val raw = query(resolver, uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "dropped"
        // A name from another app is untrusted: no path separators, nothing FAT can't keep.
        return TextFile.cleanName(raw.replace('/', '_').replace('\u0000', '_')).ifEmpty { "dropped" }
    }

    private fun lastModified(resolver: ContentResolver, uri: Uri): Long =
        query(resolver, uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED) { if (it.isNull(0)) 0L else it.getLong(0) } ?: 0L

    private fun <T> query(resolver: ContentResolver, uri: Uri, column: String, read: (android.database.Cursor) -> T): T? = try {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { c -> if (c.moveToFirst()) read(c) else null }
    } catch (e: Exception) {
        null // the provider doesn't know the column, or refuses queries
    }

    private suspend fun copyStream(resolver: ContentResolver, uri: Uri, target: File, mtime: Long) {
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("can't open")
        input.use { FileOps.copyStream(it, target) }
        if (mtime > 0) target.setLastModified(mtime)
    }

    /** A dropped folder (a document URI): its children through the provider (**verify** that drags grant this). */
    private suspend fun copyTree(resolver: ContentResolver, uri: Uri, target: File) {
        val docId = DocumentsContract.getDocumentId(uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val rows: List<List<String>> = try {
            resolver.query(children, cols, null, null, null)?.use { c ->
                buildList<List<String>> {
                    while (c.moveToNext()) add(listOf(c.getString(0) ?: "", c.getString(1) ?: "file", c.getString(2) ?: "", if (c.isNull(3)) "0" else c.getLong(3).toString()))
                }
            }
        } catch (e: Exception) {
            null // not a tree URI, or no permission for the children
        } ?: throw IllegalStateException("the app it came from doesn't share folders; drop the files instead")
        if (!target.mkdirs()) throw IllegalStateException("can't create ${target.name}")
        for ((id, rawName, mime, mtime) in rows) {
            coroutineContext.ensureActive()
            val child = DocumentsContract.buildDocumentUriUsingTree(uri, id)
            val name = TextFile.cleanName(rawName.replace('/', '_')).ifEmpty { "file" }
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) copyTree(resolver, child, FileOps.freeName(target, name))
            else copyStream(resolver, child, FileOps.freeName(target, name), mtime.toLong())
        }
    }
}
