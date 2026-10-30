package com.mediaviewer.data.mtp

import java.io.File
import java.io.IOException

/**
 * The calls of `android.mtp.MtpDevice` the app uses, with plain data classes, so [MtpConnection]
 * is pure Kotlin and runs against a fake device in tests (todo §9.8 item 9). The real one is
 * [AndroidMtpDriver]. Calls block; [MtpConnection] runs them on the device's own thread, one at a
 * time, except [readEvent], which runs on its own thread as the platform intends.
 */
interface MtpDriver {
    class StorageInfo(val description: String?, val volumeId: String?, val freeBytes: Long, val totalBytes: Long)

    /** An object's details (`MtpObjectInfo`): [parent] is [MtpFormats.ROOT] at the top of a storage. */
    data class ObjectInfo(
        val handle: Int,
        val storageId: Int,
        val parent: Int,
        val name: String?,
        val format: Int,
        val size: Long,
        val modified: Long,
        val thumbSize: Long,
    )

    /**
     * A device event (`MtpEvent`): [code] is one of the `EVENT_` codes in [MtpFormats]; [param] is
     * its first parameter, the object handle or the storage id.
     */
    class Event(val code: Int, val param: Int)

    /** GetPartialObject is supported: downloads go in chunks. */
    val canReadPartially: Boolean

    /**
     * GetPartialObject64 (Android's MTP extension) is supported too: partial reads past 4 GB, for
     * streaming large videos (todo §11.4). Cameras don't have it.
     */
    val canReadPartially64: Boolean get() = false

    fun storageIds(): IntArray?
    fun storageInfo(id: Int): StorageInfo?
    fun objectHandles(storageId: Int, parent: Int): IntArray?
    fun objectInfo(handle: Int): ObjectInfo?
    fun thumbnail(handle: Int): ByteArray?

    /** Reads up to [size] bytes at [offset] into [buf]; returns the count. Offsets past 4 GB need [canReadPartially64]. */
    @Throws(IOException::class)
    fun readPartial(handle: Int, offset: Long, size: Long, buf: ByteArray): Long

    /** Whole-file transfer into [dest]. */
    fun importFile(handle: Int, dest: String): Boolean

    fun delete(handle: Int): Boolean

    /**
     * Creates an object (SendObjectInfo, then SendObject with [source]'s bytes); [source] null
     * creates a folder. Returns the new object's details with its handle, or null.
     */
    fun send(info: ObjectInfo, source: File?): ObjectInfo?

    /** Blocks until the device sends an event; null or an exception after [cancelEvents]. */
    @Throws(IOException::class)
    fun readEvent(): Event?

    /** Ends a [readEvent] waiting on another thread. */
    fun cancelEvents()

    /** Closes the device; also ends a call stuck on another thread. */
    fun close()
}

/** PTP / MTP object formats and event codes (the values of `android.mtp.MtpConstants` / `MtpEvent`). */
object MtpFormats {
    /** The parent handle of objects at the top of a storage (0xFFFFFFFF). */
    const val ROOT = -1

    const val UNDEFINED = 0x3000
    const val ASSOCIATION = 0x3001
    const val TEXT = 0x3004
    const val WAV = 0x3008
    const val MP3 = 0x3009
    const val AVI = 0x300A
    const val MPEG = 0x300B
    const val ASF = 0x300C
    const val EXIF_JPEG = 0x3801
    const val TIFF_EP = 0x3802
    const val BMP = 0x3804
    const val GIF = 0x3807
    const val JFIF = 0x3808
    const val PNG = 0x380B
    const val TIFF = 0x380D
    const val DNG = 0x3811
    const val HEIF = 0x3812
    const val WMA = 0xB901
    const val OGG = 0xB902
    const val AAC = 0xB903
    const val FLAC = 0xB906
    const val WMV = 0xB981
    const val MP4 = 0xB982
    const val THREE_GP = 0xB984

    /** `ASSOCIATION_TYPE_GENERIC_FOLDER`: the association type of a new folder. */
    const val GENERIC_FOLDER = 1

    const val EVENT_OBJECT_ADDED = 0x4002
    const val EVENT_OBJECT_REMOVED = 0x4003
    const val EVENT_STORE_ADDED = 0x4004
    const val EVENT_STORE_REMOVED = 0x4005
    const val EVENT_OBJECT_INFO_CHANGED = 0x4007
    const val EVENT_STORAGE_INFO_CHANGED = 0x400C

    /**
     * The format a new object is sent with. Devices choose by extension anyway (**verify**: some
     * cameras refuse objects whose format they don't know); undefined is the safe default.
     */
    fun forName(name: String): Int = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> EXIF_JPEG
        "png" -> PNG
        "gif" -> GIF
        "bmp" -> BMP
        "tif", "tiff" -> TIFF
        "dng" -> DNG
        "heic", "heif" -> HEIF
        "mp3" -> MP3
        "wav" -> WAV
        "ogg", "oga", "opus" -> OGG
        "aac", "m4a" -> AAC
        "flac" -> FLAC
        "mp4", "m4v" -> MP4
        "3gp" -> THREE_GP
        "avi" -> AVI
        "mpg", "mpeg" -> MPEG
        "txt" -> TEXT
        else -> UNDEFINED
    }
}
