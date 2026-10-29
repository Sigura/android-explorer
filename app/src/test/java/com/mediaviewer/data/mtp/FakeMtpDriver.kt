package com.mediaviewer.data.mtp

import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An in-memory MTP device behind [MtpDriver] (todo §9.8 item 9): storages, folders, files with
 * their bytes and thumbnails, and device events. Calls can be made slow ([delayMs]), made to hang
 * until the device is closed ([hang]) or to fail ([fail]). Every call is logged in [calls].
 */
class FakeMtpDriver(override val canReadPartially: Boolean = true) : MtpDriver {
    class Node(var info: MtpDriver.ObjectInfo, var data: ByteArray, val thumb: ByteArray?)

    class Store(val description: String, var free: Long, val total: Long)

    val storages = LinkedHashMap<Int, Store>()
    val nodes = LinkedHashMap<Int, Node>()
    private var nextHandle = 1

    /** Call names (`objectInfo`, `readPartial`…) in order. */
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Per call name: ms to wait before answering. */
    val delayMs = HashMap<String, Long>()

    /** Call names that never answer until the device is closed. */
    val hang = HashSet<String>()

    /** Call name + handle → true: the call throws. */
    var fail: (String, Int) -> Boolean = { _, _ -> false }

    /** Whether a folder with content is deleted with it (phones) or refused (some cameras). */
    var deletesFullFolders = true

    @Volatile var closed = false
    private val closing = CountDownLatch(1)
    private val events = LinkedBlockingQueue<MtpDriver.Event>()

    fun storage(id: Int, description: String = "Internal storage", free: Long = 1L shl 30, total: Long = 1L shl 32) {
        storages[id] = Store(description, free, total)
    }

    fun folder(storage: Int, parent: Int, name: String): Int = add(storage, parent, name, MtpFormats.ASSOCIATION, ByteArray(0), null)

    fun file(storage: Int, parent: Int, name: String, data: ByteArray = ByteArray(10), thumb: ByteArray? = null, format: Int = MtpFormats.UNDEFINED): Int =
        add(storage, parent, name, format, data, thumb)

    @Synchronized
    private fun add(storage: Int, parent: Int, name: String?, format: Int, data: ByteArray, thumb: ByteArray?): Int {
        val h = nextHandle++
        nodes[h] = Node(MtpDriver.ObjectInfo(h, storage, parent, name, format, data.size.toLong(), 1_000L * h, (thumb?.size ?: 0).toLong()), data, thumb)
        return h
    }

    @Synchronized
    fun children(parent: Int, storage: Int? = null): List<Node> =
        nodes.values.filter { it.info.parent == parent && (storage == null || it.info.storageId == storage) }

    fun byName(parent: Int, name: String): Node? = children(parent).firstOrNull { it.info.name == name }

    /** The device sends an event, as a camera that took a picture or a phone whose file was deleted. */
    fun event(code: Int, param: Int) {
        events.add(MtpDriver.Event(code, param))
    }

    @Synchronized
    fun remove(handle: Int) {
        children(handle).forEach { remove(it.info.handle) }
        nodes.remove(handle)
    }

    private fun enter(name: String, handle: Int = 0) {
        calls.add(name)
        if (closed) throw IOException("closed")
        delayMs[name]?.let { Thread.sleep(it) }
        if (name in hang) {
            closing.await()
            throw IOException("closed")
        }
        if (fail(name, handle)) throw IOException("$name failed")
    }

    override fun storageIds(): IntArray {
        enter("storageIds")
        return storages.keys.toIntArray()
    }

    override fun storageInfo(id: Int): MtpDriver.StorageInfo? {
        enter("storageInfo", id)
        return storages[id]?.let { MtpDriver.StorageInfo(it.description, null, it.free, it.total) }
    }

    override fun objectHandles(storageId: Int, parent: Int): IntArray {
        enter("objectHandles", parent)
        return children(parent, storageId).map { it.info.handle }.toIntArray()
    }

    override fun objectInfo(handle: Int): MtpDriver.ObjectInfo? {
        enter("objectInfo", handle)
        return synchronized(this) { nodes[handle]?.info }
    }

    override fun thumbnail(handle: Int): ByteArray? {
        enter("thumbnail", handle)
        return nodes[handle]?.thumb
    }

    override fun readPartial(handle: Int, offset: Long, size: Long, buf: ByteArray): Long {
        enter("readPartial", handle)
        val data = nodes[handle]?.data ?: throw IOException("no object")
        val n = minOf(size, data.size - offset).toInt()
        System.arraycopy(data, offset.toInt(), buf, 0, n)
        return n.toLong()
    }

    override fun importFile(handle: Int, dest: String): Boolean {
        enter("importFile", handle)
        val data = nodes[handle]?.data ?: return false
        File(dest).writeBytes(data)
        return true
    }

    @Synchronized
    override fun delete(handle: Int): Boolean {
        enter("delete", handle)
        val n = nodes[handle] ?: return false
        if (n.info.format == MtpFormats.ASSOCIATION && children(handle).isNotEmpty() && !deletesFullFolders) return false
        remove(handle)
        return true
    }

    override fun send(info: MtpDriver.ObjectInfo, source: File?): MtpDriver.ObjectInfo? {
        enter("send", info.parent)
        val data = source?.readBytes() ?: ByteArray(0)
        val h = add(info.storageId, info.parent, info.name, info.format, data, null)
        return nodes[h]!!.info
    }

    override fun readEvent(): MtpDriver.Event? {
        while (!closed) {
            val e = events.poll(20, TimeUnit.MILLISECONDS)
            if (e != null) return e
        }
        return null
    }

    override fun cancelEvents() {}

    override fun close() {
        closed = true
        closing.countDown()
    }
}
