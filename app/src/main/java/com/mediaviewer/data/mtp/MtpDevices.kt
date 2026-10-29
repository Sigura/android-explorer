package com.mediaviewer.data.mtp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.mediaviewer.data.fs.FileOps
import com.mediaviewer.data.fs.FolderListing
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.data.fs.Volume
import com.mediaviewer.domain.model.FileEntry
import com.mediaviewer.domain.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Cameras, phones and players connected over USB as MTP or PTP devices (todo §9): found through
 * [UsbManager] (USB host / OTG), opened with [MtpDevice] after the system permission prompt, and
 * shown on the home screen with their storages. Files on them have [MtpPaths]; media are copied
 * into a mirror in the app's cache to be shown, since the slideshow, players and decoders all
 * read local files.
 */
class MtpDevices(private val context: Context, private val scope: CoroutineScope) {
    enum class State { NO_ACCESS, OPENING, READY, FAILED }

    data class Device(
        val id: Int,
        val name: String,
        val state: State,
        val storages: List<Volume> = emptyList(),
        /** Why the device can't be browsed, or a hint ("Unlock the phone…"). */
        val message: String? = null,
    )

    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val connections = ConcurrentHashMap<Int, MtpConnection>()
    private val storageWatch = ConcurrentHashMap<Int, Job>()
    private val openLock = Mutex()
    private var started = false

    /** Local copies of device files: `cache/mtp/<device>/<storage>/<folders>/<name>`. */
    val mirror = MtpMirror(File(context.cacheDir, "mtp"), ::connection)

    private val transfers = MtpTransfer(::connection, File(context.cacheDir, "mtp-transfer"))

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val dev = device(intent)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refresh()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (dev != null) detached(dev.deviceId)
                    refresh()
                }
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (dev != null && granted) open(dev) else refresh()
                }
            }
        }
    }

    /** Registers for plug events and lists what is connected. Copies left from the last run are removed. */
    fun start() {
        if (started || usb == null) return
        started = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch(Dispatchers.IO) {
            mirror.clear()
            File(context.cacheDir, "mtp-transfer").deleteRecursively()
        }
        refresh()
    }

    /** Re-reads the connected devices; ones that were already opened keep their state. */
    fun refresh() {
        val m = usb ?: return
        scope.launch(Dispatchers.IO) {
            val found = try {
                m.deviceList.values.filter(::isMtp)
            } catch (e: Exception) {
                Log.w(TAG, "USB device list failed", e)
                emptyList()
            }
            val ids = found.mapTo(HashSet()) { it.deviceId }
            connections.keys.filter { it !in ids }.forEach(::detached)
            val before = _devices.value.associateBy { it.id }
            _devices.update { old ->
                found.map { d ->
                    old.firstOrNull { it.id == d.deviceId }
                        ?: Device(d.deviceId, nameOf(d), if (m.hasPermission(d)) State.OPENING else State.NO_ACCESS)
                }
            }
            // New devices, and ones allowed since (e.g. through the "Open with" prompt of the system).
            found.filter { m.hasPermission(it) && before[it.deviceId].let { b -> b == null || b.state == State.NO_ACCESS } }.forEach(::open)
        }
    }

    /** Tapping a device: the system permission prompt, or a new attempt after a failure. */
    fun requestAccess(id: Int) {
        val m = usb ?: return
        scope.launch(Dispatchers.IO) {
            val dev = m.deviceList.values.firstOrNull { it.deviceId == id } ?: return@launch refresh()
            if (m.hasPermission(dev)) return@launch open(dev)
            // Android 12+ requires a mutable PendingIntent here: the system adds the device and the answer.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, id, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
            m.requestPermission(dev, pi)
        }
    }

    private fun open(dev: UsbDevice) {
        scope.launch(Dispatchers.IO) {
            openLock.withLock {
                // Storages appear only after the phone is unlocked and "File transfer" is chosen:
                // opening again re-reads them.
                connections.remove(dev.deviceId)?.close()
                setState(dev.deviceId, State.OPENING, null)
                val conn = usb?.openDevice(dev)
                val mtp = MtpDevice(dev)
                if (conn == null || !mtp.open(conn)) {
                    conn?.close()
                    setState(dev.deviceId, State.FAILED, "In use by another app — close it, or unplug the device and plug it in again")
                    return@withLock
                }
                val c = MtpConnection(dev.deviceId, AndroidMtpDriver(mtp, conn))
                connections[dev.deviceId] = c
                if (!readStorages(c)) return@withLock
                c.startEvents()
                storageWatch.put(dev.deviceId, scope.launch {
                    c.changes.filter { it == MtpConnection.Change.Storages }.collect { readStorages(c) }
                })?.cancel()
            }
        }
    }

    /** The device's storages as volumes; false (and the device FAILED) when they can't be read. */
    private suspend fun readStorages(c: MtpConnection): Boolean {
        val storages = try {
            c.storages()
        } catch (e: IOException) {
            if (connections[c.deviceId] === c) setState(c.deviceId, State.FAILED, e.message)
            return false
        }
        val vols = storages.map { s ->
            Volume(MtpPaths.storageRoot(c.deviceId, s.id), s.label, removable = true, freeBytes = s.freeBytes, totalBytes = s.totalBytes, device = true)
        }
        _devices.update { list ->
            list.map {
                if (it.id != c.deviceId) it
                else it.copy(
                    state = State.READY, storages = vols,
                    message = if (vols.isEmpty()) "Unlock the device and choose “File transfer”, then tap here" else null,
                )
            }
        }
        return true
    }

    private fun setState(id: Int, state: State, message: String?) {
        _devices.update { list -> list.map { if (it.id == id) it.copy(state = state, message = message, storages = emptyList()) else it } }
    }

    private fun detached(id: Int) {
        storageWatch.remove(id)?.cancel()
        connections.remove(id)?.close()
        _devices.update { list -> list.filter { it.id != id } }
        scope.launch(Dispatchers.IO) { mirror.clear(id) }
    }

    fun connection(path: String): MtpConnection? = MtpPaths.parse(path)?.let { connections[it.deviceId] }

    /** The storage [path] is on, as a volume for breadcrumbs and the top bar. */
    fun volumeOf(path: String): Volume? {
        val root = MtpPaths.rootOf(path) ?: return null
        return _devices.value.firstNotNullOfOrNull { d -> d.storages.firstOrNull { it.path == root } }
    }

    fun isConnected(path: String): Boolean = connection(path)?.let { !it.hung } == true

    fun scan(dir: String, showHidden: Boolean): Flow<FolderScanner.Event> {
        val c = connection(dir) ?: return kotlinx.coroutines.flow.flowOf(FolderScanner.Event.Failed("removed"))
        return c.scan(dir, showHidden).flowOn(Dispatchers.IO)
    }

    /**
     * Live updates of the open device folder [dir] (todo §9.8 item 3), from the device's events.
     * Hidden files are left out unless [showHidden].
     */
    fun watch(dir: String, showHidden: Boolean): Flow<FolderListing.Change> {
        val c = connection(dir) ?: return emptyFlow()
        return c.changes.mapNotNull { ch ->
            when (ch) {
                is MtpConnection.Change.Added ->
                    ch.entry.takeIf { ch.dir == dir && (showHidden || !FolderScanner.isHidden(it.name)) }?.let { FolderListing.Change.Added(it) }
                is MtpConnection.Change.Removed -> {
                    mirror.forget(ch.path)
                    if (MtpPaths.parent(ch.path) == dir) FolderListing.Change.Removed(ch.path) else null
                }
                MtpConnection.Change.Storages -> null
            }
        }
    }

    /**
     * A device file's thumbnail: the one the device made, or, for small images it has none for
     * (todo §9.8 item 8: some cameras for RAW + JPEG pairs, many players), the file itself,
     * downloaded into the mirror.
     */
    suspend fun thumbnail(entry: FileEntry): ByteArray? {
        val c = connection(entry.path) ?: return null
        c.thumbnail(entry.path)?.let { return it }
        if (c.hasThumbnail(entry.path) != false || !downloadsForThumbnail(entry)) return null
        return mirror.fetch(entry)?.readBytes()
    }

    /** Folder collage candidates on a device (todo §9.8 item 5) and the folder's item count. */
    suspend fun folderPreview(dir: String): Pair<List<FileEntry>, Int> = connection(dir)?.folderPreview(dir) ?: (emptyList<FileEntry>() to 0)

    /** Subfolders of a device folder, for the folder picker. */
    suspend fun subfolders(dir: String): List<FileEntry> = connection(dir)?.list(dir, showHidden = false)?.filter { it.isFolder } ?: throw DeviceGone()

    /** *New folder* on a device (todo §9.8 item 2); returns its path. */
    suspend fun mkdir(dir: String, name: String): String = (connection(dir) ?: throw DeviceGone()).mkdir(dir, name)

    /** Deletes a device file or folder for good (todo §9.8 item 1), and its copy in the mirror. */
    suspend fun delete(path: String) {
        (connection(path) ?: throw DeviceGone()).delete(path)
        mirror.forget(path)
    }

    // ---------------------------------------------------------------- local mirror

    fun isMirror(path: String): Boolean = mirror.isMirror(path)

    /** The device path of a mirrored file or folder. */
    fun remoteOf(localPath: String): String? = mirror.remoteOf(localPath)

    /**
     * *Copy to…* / *Move to…* with a device on either side (todo §9.5, §9.8 item 2): files and
     * whole folders, `(1)` names for clashes, progress per file and Stop.
     */
    suspend fun transfer(
        entries: List<FileEntry>,
        destDir: String,
        move: Boolean,
        onProgress: (index: Int, total: Int, name: String) -> Unit,
        onMoved: (String) -> Unit,
    ): FileOps.Result = transfers.run(entries, destDir, move, onProgress) { path ->
        if (MtpPaths.isMtp(path)) mirror.forget(path)
        onMoved(path)
    }

    private fun nameOf(d: UsbDevice): String {
        val product = d.productName?.trim().orEmpty()
        val maker = d.manufacturerName?.trim().orEmpty()
        return when {
            product.isEmpty() && maker.isEmpty() -> "USB device"
            product.isEmpty() -> maker
            maker.isEmpty() || product.startsWith(maker, ignoreCase = true) -> product
            else -> "$maker $product"
        }
    }

    @Suppress("DEPRECATION")
    private fun device(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val ACTION_PERMISSION = "com.mediaviewer.USB_PERMISSION"

        /** Images up to this size are downloaded for a thumbnail when the device has none (todo §9.8 item 8). */
        const val THUMB_DOWNLOAD_MAX = 8L * 1024 * 1024

        /** Images the platform decodes; RAW files without a device thumbnail keep their icon. */
        private val DECODABLE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "dng")

        fun downloadsForThumbnail(e: FileEntry): Boolean =
            (e.type == MediaType.IMAGE || e.type == MediaType.ANIMATED) && e.size in 1..THUMB_DOWNLOAD_MAX &&
                e.name.substringAfterLast('.', "").lowercase() in DECODABLE

        /**
         * PTP cameras use the still-image class (6); phones and players in MTP mode often use a
         * vendor-specific interface named "MTP".
         */
        fun isMtp(d: UsbDevice): Boolean = (0 until d.interfaceCount).any { i ->
            val itf = d.getInterface(i)
            itf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && itf.name == "MTP")
        }
    }
}
