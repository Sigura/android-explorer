package com.mediaviewer.domain

/** Where a book stands, for its folder tile (todo §10.1). */
data class ListenStatus(
    /** The current file, 0-based; -1 = not known (saved before history v2). */
    val index: Int,
    /** Files in the book; 0 = not known. */
    val total: Int,
    val positionMs: Long,
    /** The current file's duration; 0 = not known. */
    val durationMs: Long,
    val finished: Boolean,
    /** Playing right now (not paused). */
    val playing: Boolean = false,
)

/** The folder tile's label: short, medium and long variants; the tile shows the longest that fits. Pure Kotlin. */
object ListenLabel {
    /**
     * Shortest first. A small tile shows `12/30`, a wider one `12/30 · 4:05`, a wide one
     * `File 12 of 30 · 4:05 / 25:10`. Missing parts are left out; never empty for a finished book
     * except the short variant, where the ✓ icon says it alone.
     */
    fun variants(s: ListenStatus): List<String> {
        if (s.finished) {
            return listOfNotNull("", "Finished", if (s.total > 0) "Finished · ${files(s.total)}" else null)
        }
        val known = s.index >= 0 && s.total > 0
        val count = if (known) "${s.index + 1}/${s.total}" else null
        val pos = time(s.positionMs)
        val short = count ?: pos
        val medium = listOfNotNull(count, pos).joinToString(" · ")
        val long = listOfNotNull(
            if (known) "File ${s.index + 1} of ${s.total}" else null,
            if (s.durationMs > 0) "$pos / ${time(s.durationMs)}" else pos,
        ).joinToString(" · ")
        return listOf(short, medium, long).distinct()
    }

    /** How much of the book was heard, 0..1, counting each file as an equal share; null = not known. */
    fun progress(s: ListenStatus): Float? {
        if (s.finished) return 1f
        if (s.index < 0 || s.total <= 0) return null
        val inFile = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
        return ((s.index + inFile) / s.total).coerceIn(0f, 1f)
    }

    /** `4:05`, `1:02:05`. */
    fun time(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600
        val m = t / 60 % 60
        val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun files(n: Int) = if (n == 1) "1 file" else "$n files"
}
