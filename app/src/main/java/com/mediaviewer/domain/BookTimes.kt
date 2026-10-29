package com.mediaviewer.domain

/** Where the player is in the whole book: [elapsedMs] of [totalMs]; not [exact] while some durations are unknown. */
data class BookProgress(val elapsedMs: Long, val totalMs: Long, val exact: Boolean) {
    /** Time left at [speed]: what the listener actually waits. */
    fun leftMs(speed: Float): Long = ((totalMs - elapsedMs).coerceAtLeast(0) / speed.coerceAtLeast(0.1f)).toLong()
}

/**
 * Durations of the files in a book's queue (todo §4.3), read in the background; null for a file
 * not read yet or unreadable. The file playing uses the player's own duration, known first.
 * Pure Kotlin.
 */
data class BookTimes(val paths: List<String>, val durationsMs: List<Long?>) {
    private fun duration(i: Int, current: Int, currentMs: Long): Long? =
        if (i == current && currentMs > 0) currentMs else durationsMs.getOrNull(i)

    fun progress(current: Int, positionMs: Long, currentMs: Long): BookProgress {
        var elapsed = positionMs
        var total = 0L
        var exact = true
        for (i in paths.indices) {
            val d = duration(i, current, currentMs)
            if (d == null) exact = false
            total += d ?: 0
            if (i < current) elapsed += d ?: 0
        }
        return BookProgress(elapsed, total.coerceAtLeast(elapsed), exact)
    }

    /** Relative widths for the segmented book bar; a file not read yet counts as an average one. */
    fun weights(current: Int, currentMs: Long): List<Float> {
        val known = paths.indices.mapNotNull { duration(it, current, currentMs) }
        val avg = if (known.isEmpty()) 1f else known.average().toFloat().coerceAtLeast(1f)
        return paths.indices.map { duration(it, current, currentMs)?.toFloat()?.coerceAtLeast(1f) ?: avg }
    }
}
