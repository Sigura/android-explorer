package com.mediaviewer.data.mtp

/**
 * Paths of files on MTP / PTP devices (todo §9): `mtp://<device id>/<storage id>/<folder>/<name>`.
 * The device id is the USB device id of this connection, so these paths are only valid until the
 * device is unplugged; they are never stored in recent folders or favourites. Names are kept as
 * the device reports them, except that a `/` (never seen in practice) becomes `_`.
 */
object MtpPaths {
    const val SCHEME = "mtp://"

    data class Parts(val deviceId: Int, val storageId: Int, val segments: List<String>) {
        val isStorageRoot: Boolean get() = segments.isEmpty()
    }

    fun isMtp(path: String?): Boolean = path != null && path.startsWith(SCHEME)

    fun storageRoot(deviceId: Int, storageId: Int): String = "$SCHEME$deviceId/$storageId"

    fun child(dir: String, name: String): String = dir.trimEnd('/') + "/" + safeName(name)

    fun safeName(name: String): String = name.replace('/', '_').ifEmpty { "_" }

    fun parse(path: String): Parts? {
        if (!isMtp(path)) return null
        val parts = path.removePrefix(SCHEME).split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val device = parts[0].toIntOrNull() ?: return null
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
    fun rootOf(path: String): String? = parse(path)?.let { storageRoot(it.deviceId, it.storageId) }

    /** `mtp://3/65537/DCIM/a.jpg` → `3/65537/DCIM/a.jpg`, the file's place in the local mirror. */
    fun mirrorRelative(path: String): String? = parse(path)?.let { p ->
        (listOf(p.deviceId.toString(), p.storageId.toString()) + p.segments).joinToString("/")
    }

    /** The reverse of [mirrorRelative]. */
    fun fromMirrorRelative(rel: String): String? {
        val p = SCHEME + rel.trim('/')
        return if (parse(p) != null) p else null
    }
}
