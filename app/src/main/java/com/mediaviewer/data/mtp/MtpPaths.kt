package com.mediaviewer.data.mtp

/**
 * Paths of files on MTP / PTP devices (todo §9): `mtp://<device>/<storage id>/<folder>/<name>`.
 * `<device>` is the device's key (todo §11.4): `vvvv-pppp-<serial>` (USB vendor and product id in
 * hex, then the serial number) when the device has a serial number, so its paths are the same
 * every time it is plugged in, and recent folders, favourites, per-folder sort and listening
 * history can point into it. A device without one gets `usb<USB device id>`, valid only until it
 * is unplugged and never remembered. Names are kept as the device reports them, except that a `/`
 * (never seen in practice) becomes `_`.
 */
object MtpPaths {
    const val SCHEME = "mtp://"

    data class Parts(val device: String, val storageId: Int, val segments: List<String>) {
        val isStorageRoot: Boolean get() = segments.isEmpty()
    }

    fun isMtp(path: String?): Boolean = path != null && path.startsWith(SCHEME)

    fun storageRoot(device: String, storageId: Int): String = "$SCHEME$device/$storageId"

    fun storageRoot(deviceId: Int, storageId: Int): String = storageRoot(deviceId.toString(), storageId)

    /** Prefix of the keys of devices without a serial number: this connection only. */
    private const val SESSION = "usb"

    /**
     * The key of a device in paths: stable with a [serial] number (letters and digits kept, at most
     * 40), else `usb<usbId>` for this connection only.
     */
    fun deviceKey(vendorId: Int, productId: Int, serial: String?, usbId: Int): String {
        val clean = serial.orEmpty().filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(40)
        return if (clean.isEmpty()) "$SESSION$usbId" else "%04x-%04x-%s".format(vendorId and 0xFFFF, productId and 0xFFFF, clean)
    }

    /** `vvvv-pppp-` of a remembered key: a device plugged in with these ids may be that one. */
    fun modelOf(key: String): String? = if (isRemembered(key)) key.substring(0, 10) else null

    fun modelPrefix(vendorId: Int, productId: Int): String = "%04x-%04x-".format(vendorId and 0xFFFF, productId and 0xFFFF)

    /** The key stays the same after unplugging: paths on it may be stored. */
    fun isRemembered(key: String): Boolean = !key.startsWith(SESSION) && key.length > 10 && key[4] == '-' && key[9] == '-'

    /** [path] is on a device and stays valid after unplugging (recent folders, favourites, history). */
    fun isRememberedPath(path: String?): Boolean = path?.let { parse(it) }?.let { isRemembered(it.device) } == true

    /** The device key of [path], or null. */
    fun deviceOf(path: String?): String? = path?.let { parse(it) }?.device

    fun child(dir: String, name: String): String = dir.trimEnd('/') + "/" + safeName(name)

    fun safeName(name: String): String = name.replace('/', '_').ifEmpty { "_" }

    fun parse(path: String): Parts? {
        if (!isMtp(path)) return null
        val parts = path.removePrefix(SCHEME).split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val device = parts[0].takeIf { d -> d.all { it.isLetterOrDigit() || it == '-' } } ?: return null
        val storage = parts[1].toIntOrNull() ?: return null
        return Parts(device, storage, parts.drop(2))
    }

    /** The folder holding [path]; null for a storage root, which has no parent on the device. */
    fun parent(path: String): String? {
        val p = parse(path) ?: return null
        if (p.isStorageRoot) return null
        return path.trimEnd('/').substringBeforeLast('/')
    }

    /** The storage root [path] is on. */
    fun rootOf(path: String): String? = parse(path)?.let { storageRoot(it.device, it.storageId) }

    /** `mtp://3/65537/DCIM/a.jpg` → `3/65537/DCIM/a.jpg`, the file's place in the local mirror. */
    fun mirrorRelative(path: String): String? = parse(path)?.let { p ->
        (listOf(p.device, p.storageId.toString()) + p.segments).joinToString("/")
    }

    /** The reverse of [mirrorRelative]. */
    fun fromMirrorRelative(rel: String): String? {
        val p = SCHEME + rel.trim('/')
        return if (parse(p) != null) p else null
    }
}
