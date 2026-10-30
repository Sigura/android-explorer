package com.mediaviewer.data.mtp

import android.hardware.usb.UsbDeviceConnection
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mediaviewer.data.fs.TAG
import java.io.File

/** [MtpDriver] over the platform's [MtpDevice] (USB host, API 24+ for chunks and events). */
class AndroidMtpDriver(private val device: MtpDevice, private val usb: UsbDeviceConnection) : MtpDriver {
    @Volatile private var events: CancellationSignal? = null

    override val canReadPartially: Boolean by lazy {
        try {
            device.deviceInfo?.isOperationSupported(MtpConstants.OPERATION_GET_PARTIAL_OBJECT) == true
        } catch (e: Exception) {
            false
        }
    }

    override val canReadPartially64: Boolean by lazy {
        try {
            device.deviceInfo?.isOperationSupported(MtpConstants.OPERATION_GET_PARTIAL_OBJECT_64) == true
        } catch (e: Exception) {
            false
        }
    }

    override fun storageIds(): IntArray? = device.storageIds

    override fun storageInfo(id: Int): MtpDriver.StorageInfo? = device.getStorageInfo(id)?.let {
        MtpDriver.StorageInfo(it.description, it.volumeIdentifier, it.freeSpace, it.maxCapacity)
    }

    override fun objectHandles(storageId: Int, parent: Int): IntArray? = device.getObjectHandles(storageId, 0, parent)

    override fun objectInfo(handle: Int): MtpDriver.ObjectInfo? = device.getObjectInfo(handle)?.let(::info)

    override fun thumbnail(handle: Int): ByteArray? = device.getThumbnail(handle)

    // GetPartialObject takes a 32-bit offset; GetPartialObject64 the rest (todo §11.4).
    override fun readPartial(handle: Int, offset: Long, size: Long, buf: ByteArray): Long =
        if (offset + size > MAX_32 && canReadPartially64) device.getPartialObject64(handle, offset, size, buf)
        else device.getPartialObject(handle, offset, size, buf)

    override fun importFile(handle: Int, dest: String): Boolean = device.importFile(handle, dest)

    override fun delete(handle: Int): Boolean = device.deleteObject(handle)

    override fun send(info: MtpDriver.ObjectInfo, source: File?): MtpDriver.ObjectInfo? {
        val b = MtpObjectInfo.Builder()
            .setName(info.name.orEmpty())
            .setFormat(info.format)
            .setParent(info.parent)
            .setStorageId(info.storageId)
            .setCompressedSize(info.size)
            .setDateModified(info.modified)
        if (source == null) b.setAssociationType(MtpFormats.GENERIC_FOLDER)
        val sent = device.sendObjectInfo(b.build()) ?: return null
        if (source != null) {
            val ok = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                device.sendObject(sent.objectHandle, source.length(), pfd)
            }
            if (!ok) {
                // An object without its data is left behind by some devices (**verify**).
                device.deleteObject(sent.objectHandle)
                return null
            }
        }
        return info(sent)
    }

    override fun readEvent(): MtpDriver.Event? {
        val signal = CancellationSignal()
        events = signal
        return try {
            val e = device.readEvent(signal)
            MtpDriver.Event(e.eventCode, e.parameter1)
        } catch (e: OperationCanceledException) {
            null
        } finally {
            events = null
        }
    }

    override fun cancelEvents() {
        events?.cancel()
    }

    override fun close() {
        cancelEvents()
        try {
            device.close()
        } catch (e: Exception) {
            Log.w(TAG, "MTP close failed", e)
        }
        try {
            usb.close()
        } catch (e: Exception) {
        }
    }

    private fun info(i: MtpObjectInfo) = MtpDriver.ObjectInfo(
        i.objectHandle, i.storageId, i.parent, i.name, i.format, i.compressedSizeLong, i.dateModified, i.thumbCompressedSizeLong,
    )

    private companion object {
        const val MAX_32 = 0xFFFF_FFFFL
    }
}
