package com.mediaviewer.domain

import com.mediaviewer.domain.model.FileEntry
import kotlin.random.Random

/**
 * Slideshow order over a folder's media files (README §6, "Random order").
 *
 * The random order is a permutation where every file gets a pseudo-random key derived from
 * `seed` and its name, optionally with one pinned file in front (the item random order was turned
 * on from, or the swapped-in first item of a new cycle). Because the key depends only on the seed
 * and the name, `seed` + `pinnedFirst` + the current file are enough to rebuild exactly the same
 * order after a restart, even if files were added or deleted meanwhile (README §7.3).
 *
 * @param media media files in folder order (sorted by [folderOrder]).
 */
class SlideOrder(
    media: List<FileEntry>,
    private val folderOrder: Comparator<FileEntry>,
    random: Boolean,
    seed: Long,
    pinnedFirst: String?,
    startPath: String?,
    private val rng: Random = Random.Default,
) {
    private val byName = media.toMutableList()
    private var order: MutableList<FileEntry> = mutableListOf()

    var random: Boolean = random
        private set
    var seed: Long = seed
        private set
    var pinnedFirst: String? = pinnedFirst
        private set

    /** Index of the current item in [order]. */
    var position: Int = 0
        private set

    init {
        rebuild()
        position = if (startPath == null) 0 else positionFor(startPath)
    }

    val size: Int get() = order.size
    val current: FileEntry? get() = order.getOrNull(position)
    val isEmpty: Boolean get() = order.isEmpty()

    /** Peek at the item [next] would move to, without moving. */
    fun peekNext(): FileEntry? = peek(1)

    /**
     * The item [offset] steps away (negative = back), without moving: null before the start, and
     * past the end in random order (a new cycle has a new seed, not known yet); name order wraps.
     */
    fun peek(offset: Int): FileEntry? {
        if (order.isEmpty()) return null
        val i = position + offset
        return when {
            i in order.indices -> order[i]
            offset < 0 || random -> null
            else -> order[i % order.size]
        }
    }

    /** Makes [path] the current item (a target found with [peek]); null if it is no longer in the order. */
    fun moveTo(path: String): FileEntry? {
        val i = indexOf(path)
        if (i < 0) return null
        position = i
        return current
    }

    /** Next item. After the last one, wraps around (a new random cycle in random order). */
    fun next(): FileEntry? {
        if (order.isEmpty()) return null
        if (position + 1 < order.size) {
            position++
        } else if (random) {
            val last = order[position]
            seed = rng.nextLong()
            pinnedFirst = null
            rebuild()
            if (order.size > 1 && order[0].path == last.path) {
                // Never show the same file twice in a row across cycles.
                pinnedFirst = order[1].path
                rebuild()
            }
            position = 0
        } else {
            position = 0
        }
        return current
    }

    /** Previous item, or null (and no move) at the start of the order or cycle. */
    fun previous(): FileEntry? {
        if (position <= 0) return null
        position--
        return current
    }

    /** On: a new cycle starting from the current item. Off: name order from the current item. */
    fun setRandom(on: Boolean) {
        if (on == random) return
        val cur = current
        random = on
        if (on) {
            seed = rng.nextLong()
            pinnedFirst = cur?.path
        }
        rebuild()
        position = if (cur == null) 0 else positionFor(cur.path)
    }

    /** A deleted or missing file is skipped silently; the item after it becomes current. */
    fun remove(path: String) {
        val idx = order.indexOfFirst { it.path == path }
        if (idx < 0) return
        order.removeAt(idx)
        byName.removeAll { it.path == path }
        if (idx < position) position--
        if (order.isEmpty()) {
            position = 0
        } else if (position >= order.size) {
            // The last item was removed: wrap (or start a new random cycle).
            position = order.size - 1
            next()
        }
    }

    /** New file: name order puts it in place; random order puts it somewhere in the not-yet-shown part. */
    fun add(entry: FileEntry) {
        if (byName.any { it.path == entry.path }) return
        insertSorted(byName, entry, folderOrder)
        if (random) {
            val from = (position + 1).coerceAtMost(order.size)
            order.add(rng.nextInt(from, order.size + 1), entry)
        } else {
            val idx = insertSorted(order, entry, folderOrder)
            if (idx <= position && order.size > 1) position++
        }
    }

    fun indexOf(path: String): Int = order.indexOfFirst { it.path == path }

    private fun randomOrder(): Comparator<FileEntry> {
        val pinned = pinnedFirst
        return compareBy<FileEntry> { if (it.path == pinned) 0 else 1 }
            .thenBy { key(seed, it.name) }
            .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    private fun rebuild() {
        order = if (random) byName.sortedWith(randomOrder()).toMutableList() else byName.toMutableList()
    }

    /** Position of [path], or of the item that follows where it would be if it is gone. */
    private fun positionFor(path: String): Int {
        val idx = indexOf(path)
        if (idx >= 0) return idx
        if (order.isEmpty()) return 0
        val name = path.substringAfterLast('/')
        val ghost = FileEntry(path, name, com.mediaviewer.domain.model.MediaType.IMAGE, 0, 0)
        val cmp = if (random) randomOrder() else folderOrder
        val at = order.binarySearch(ghost, cmp).let { if (it < 0) -it - 1 else it }
        return if (at >= order.size) 0 else at
    }

    companion object {
        /** Stable 64-bit key: SplitMix64 over seed and an FNV-1a hash of the name. */
        fun key(seed: Long, name: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in name) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            var z = seed xor h
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun <T> insertSorted(list: MutableList<T>, item: T, cmp: Comparator<in T>): Int {
            val at = list.binarySearch(item, cmp).let { if (it < 0) -it - 1 else it }
            list.add(at, item)
            return at
        }
    }
}
