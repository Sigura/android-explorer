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
import com.mediaviewer.data.fs.FolderScanner
import com.mediaviewer.data.fs.TAG
import com.mediaviewer.data.fs.Volume
import com.mediaviewer.domain.model.FileEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
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
import kotlin.coroutines.coroutineContext

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
    private val openLock = Mutex()
    private var started = false

    /** Local copies of device files: `cache/mtp/<device>/<storage>/<folders>/<name>`. */
    val mirrorRoot = File(context.cacheDir, "mtp")

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
        scope.launch(Dispatchers.IO) { mirrorRoot.deleteRecursively() }
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
                val c = MtpConnection(dev.deviceId, mtp, conn)
                connections[dev.deviceId] = c
                val storages = try {
                    c.storages()
                } catch (e: IOException) {
                    setState(dev.deviceId, State.FAILED, e.message)
                    return@withLock
                }
                val vols = storages.map { s ->
                    Volume(MtpPaths.storageRoot(dev.deviceId, s.id), s.label, removable = true, freeBytes = s.freeBytes, totalBytes = s.totalBytes, device = true)
                }
                _devices.update { list ->
                    list.map {
                        if (it.id != dev.deviceId) it
                        else it.copy(
                            state = State.READY, storages = vols,
                            message = if (vols.isEmpty()) "Unlock the device and choose “File transfer”, then tap here" else null,
                        )
                    }
                }
            }
        }
    }

    private fun setState(id: Int, state: State, message: String?) {
        _devices.update { list -> list.map { if (it.id == id) it.copy(state = state, message = message, storages = emptyList()) else it } }
    }

    private fun detached(id: Int) {
        connections.remove(id)?.close()
        _devices.update { list -> list.filter { it.id != id } }
        scope.launch(Dispatchers.IO) { File(mirrorRoot, id.toString()).deleteRecursively() }
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

    suspend fun thumbnail(path: String): ByteArray? = connection(path)?.thumbnail(path)

    // ---------------------------------------------------------------- local mirror

    fun mirrorOf(path: String): File? = MtpPaths.mirrorRelative(path)?.let { File(mirrorRoot, it) }

    fun isMirror(path: String): Boolean = path == mirrorRoot.path || path.startsWith(mirrorRoot.path + "/")

    /** The device path of a mirrored file or folder. */
    fun remoteOf(localPath: String): String? =
        if (!isMirror(localPath)) null else MtpPaths.fromMirrorRelative(localPath.removePrefix(mirrorRoot.path))

    /**
     * The local copy of [entry], downloaded now unless an earlier copy of the same size is there.
     * Returns null when the device is gone.
     */
    suspend fun fetch(entry: FileEntry, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File? {
        val c = connection(entry.path) ?: return null
        val dest = mirrorOf(entry.path) ?: return null
        if (dest.isFile && dest.length() == entry.size) return dest
        c.download(entry.path, dest, onProgress)
        return dest
    }

    /** Bytes in the mirror; [MIRROR_BUDGET] caps what a slideshow downloads ahead. */
    fun mirrorBytes(): Long = mirrorRoot.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Frees the mirror except [keep] (the folder being shown). */
    fun trimMirror(keep: File) {
        mirrorRoot.walkBottomUp().forEach { f ->
            if (f.isFile && !f.path.startsWith(keep.path + "/")) f.delete()
            if (f.isDirectory && f != mirrorRoot && f.list()?.isEmpty() == true) f.delete()
        }
    }

    /**
     * "Copy to…" from a device (todo §9.5): files and whole folders into [destDir]. Name clashes
     * get a " (1)" suffix, as local copies do. [onProgress] gets the file being copied.
     */
    suspend fun importTo(entries: List<FileEntry>, destDir: File, onProgress: (name: String) -> Unit): FileOps.Result {
        val failed = ArrayList<Pair<String, String>>()
        var done = 0
        suspend fun copy(e: FileEntry, into: File) {
            coroutineContext.ensureActive()
            val c = connection(e.path) ?: throw IOException("Device disconnected")
            val target = FileOps.freeName(into, e.name)
            if (e.isFolder) {
                if (!target.mkdirs()) throw IOException("can't create ${target.name}")
                val children = ArrayList<FileEntry>()
                c.scan(e.path, showHidden = true).collect { ev ->
                    when (ev) {
                        is FolderScanner.Event.Batch -> children.addAll(ev.entries)
                        is FolderScanner.Event.Failed -> throw IOException(ev.reason)
                    }
                }
                for (child in children) copy(child, target)
            } else {
                onProgress(e.name)
                c.download(e.path, target)
            }
        }
        for (e in entries) {
            try {
                copy(e, destDir)
                done++
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.w(TAG, "Import failed: ${e.path}", ex)
                failed.add(e.path to (ex.message ?: ex.javaClass.simpleName))
            }
        }
        return FileOps.Result(done, failed)
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

        /** A slideshow stops downloading ahead once the mirror holds this much. */
        const val MIRROR_BUDGET = 2L * 1024 * 1024 * 1024

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
