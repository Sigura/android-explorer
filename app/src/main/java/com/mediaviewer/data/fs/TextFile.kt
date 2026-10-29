package com.mediaviewer.data.fs

import com.mediaviewer.data.thumbs.TextDecode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A text file open in the editor (todo §6): the text with `\n` line endings, plus what saving needs
 * to write it back the way it was (charset, BOM, CRLF) and to notice changes made by other apps.
 */
data class TextDoc(
    val path: String,
    val text: String,
    val charset: Charset,
    val bom: Boolean,
    val crlf: Boolean,
    val size: Long,
    val mtime: Long,
    val readOnly: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** Reading, saving and creating text files (todo §6.1, §6.2, §6.4). Blocking: call on IO, under a watchdog. */
object TextFile {
    /** Larger files open read-only. */
    const val EDIT_LIMIT = 1L shl 20

    /** Larger files open with another app. */
    const val READ_LIMIT = 10L shl 20

    /** Larger texts (in characters) are shown without syntax colours. */
    const val COLOUR_LIMIT = 200 * 1024

    sealed interface Load {
        class Ok(val doc: TextDoc) : Load
        data object NotText : Load
        data object TooLarge : Load
        class Failed(val reason: String) : Load
    }

    sealed interface Save {
        class Ok(val size: Long, val mtime: Long) : Save

        /** Size or modification time differ from when the file was opened: another app changed it. */
        data object Changed : Save

        /** Some characters don't exist in the file's charset. */
        data object Unencodable : Save
        class Failed(val reason: String) : Save
    }

    fun load(file: File, fallback: Charset, detector: TextDecode.Detector?): Load {
        if (!file.isFile) return Load.Failed("Not a file")
        val size = file.length()
        if (size > READ_LIMIT) return Load.TooLarge
        val mtime = file.lastModified()
        val bytes = try {
            // A binary file is found in its first 8 KB, before reading up to 10 MB of it.
            if (size > TextDecode.READ_BYTES && TextDecode.read(file, fallback, detector) == null) return Load.NotText
            file.readBytes()
        } catch (e: IOException) {
            return Load.Failed(e.message ?: "Read error")
        }
        val d = TextDecode.decodeFull(bytes, bytes.size, fallback, detector) ?: return Load.NotText
        val crlf = d.text.contains("\r\n")
        val text = if (crlf) d.text.replace("\r\n", "\n") else d.text
        return Load.Ok(TextDoc(file.path, text, d.charset, d.bom, crlf, size, mtime, readOnly = size > EDIT_LIMIT || !file.canWrite()))
    }

    /** A new, empty file: UTF-8 without a BOM, `\n` line endings. */
    fun newDoc(file: File): TextDoc = TextDoc(file.path, "", Charsets.UTF_8, false, false, 0L, file.lastModified(), readOnly = false)

    /**
     * Writes [text] as [doc] was read (or in [charset]): to a temporary file next to it, synced, then
     * renamed over the original, so a failed write leaves the original as it was. [force] skips the
     * check for changes by other apps.
     */
    fun save(doc: TextDoc, text: String, force: Boolean = false, charset: Charset = doc.charset): Save {
        val file = File(doc.path)
        if (!force && file.exists() && (file.length() != doc.size || file.lastModified() != doc.mtime)) return Save.Changed
        val bytes = encode(text, charset, doc.bom && charset == doc.charset, doc.crlf) ?: return Save.Unencodable
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        try {
            write(tmp, bytes)
            if (!tmp.renameTo(file)) {
                // Some file systems can't rename over an existing file: write in place instead.
                tmp.delete()
                write(file, bytes)
            }
        } catch (e: IOException) {
            tmp.delete()
            return Save.Failed(e.message ?: "Write error")
        }
        return Save.Ok(file.length(), file.lastModified())
    }

    private fun write(f: File, bytes: ByteArray) {
        FileOutputStream(f).use {
            it.write(bytes)
            it.fd.sync()
        }
    }

    /** The bytes to save, or null if a character can't be encoded in [charset]. */
    fun encode(text: String, charset: Charset, bom: Boolean, crlf: Boolean): ByteArray? {
        val t = if (crlf) text.replace("\n", "\r\n") else text
        val encoded = try {
            charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(t))
        } catch (e: CharacterCodingException) {
            return null
        }
        val head = if (bom) bomFor(charset) else ByteArray(0)
        val out = ByteArray(head.size + encoded.remaining())
        head.copyInto(out)
        encoded.get(out, head.size, encoded.remaining())
        return out
    }

    private fun bomFor(charset: Charset): ByteArray = when (charset) {
        Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        else -> ByteArray(0)
    }

    /** The name as it will be used: spaces around it and trailing dots removed, since FAT can't keep them. */
    fun cleanName(raw: String): String = raw.trim().trimEnd('.', ' ')

    /** Why [name] (already cleaned) can't be a file or folder name, or null if it can. */
    fun nameProblem(name: String): String? = when {
        name.isEmpty() -> "Type a name"
        '/' in name || '\u0000' in name -> "A name can't contain /"
        name.toByteArray(Charsets.UTF_8).size > 255 -> "The name is too long"
        else -> null
    }

    /** Creates an empty file; never overwrites. Null on success, else the reason. */
    fun create(file: File): String? = try {
        if (file.createNewFile()) null else "${file.name} already exists"
    } catch (e: IOException) {
        e.message ?: "Can't create ${file.name}"
    }

    /**
     * Renames [from] to [name] in the same folder (todo §10.6); never overwrites. A change of case
     * only goes through a temporary name, which FAT and exFAT need. Null on success, else the reason.
     */
    fun rename(from: File, name: String): String? {
        val to = File(from.parentFile, name)
        val caseOnly = to.path != from.path && to.path.equals(from.path, ignoreCase = true)
        return try {
            when {
                to.path == from.path -> null
                to.exists() && !caseOnly -> "$name already exists"
                !from.exists() -> "${from.name} is gone"
                caseOnly -> {
                    val tmp = File(from.parentFile, ".${from.name}.rename")
                    when {
                        !from.renameTo(tmp) -> "Can't rename ${from.name}"
                        tmp.renameTo(to) -> null
                        else -> "Can't rename ${from.name}".also { tmp.renameTo(from) }
                    }
                }
                from.renameTo(to) -> null
                else -> "Can't rename ${from.name}"
            }
        } catch (e: SecurityException) {
            e.message ?: "Can't rename ${from.name}"
        }
    }

    /** Creates a folder. Null on success, else the reason. */
    fun mkdir(dir: File): String? = when {
        dir.exists() -> "${dir.name} already exists"
        dir.mkdir() -> null
        else -> "Can't create ${dir.name}"
    }
}
