package com.mediaviewer.domain

import com.mediaviewer.domain.model.FileEntry
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shuffle in the book player (todo §3.1, §4.2): the slideshow's stable random order
 * ([SlideOrder.key] over the seed and the file name), with the file playing when shuffle was
 * turned on pinned first. The same seed and pinned file rebuild the same order after a restart,
 * and files added meanwhile fall into place. One cycle: the queue stops at its end. Pure Kotlin.
 */
object ShuffleOrder {
    fun of(queue: List<FileEntry>, seed: Long, first: String?): List<FileEntry> =
        queue.sortedWith(
            compareBy<FileEntry> { if (it.path == first) 0 else 1 }
                .thenBy { SlideOrder.key(seed, it.name) }
                .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) },
        )
}

/** Playback speed steps (todo §4.2): 0.5–3×, 0.05 apart. Pure Kotlin. */
object PlayerSpeed {
    const val MIN = 0.5f
    const val MAX = 3f
    const val STEP = 0.05f
    val PRESETS = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    /** [speed] rounded to the step grid, inside the range. */
    fun snap(speed: Float): Float = ((speed.coerceIn(MIN, MAX) / STEP).roundToInt() * STEP).let { (it * 100).roundToInt() / 100f }

    /** One step up (+1) or down (-1). */
    fun step(speed: Float, direction: Int): Float = snap(snap(speed) + direction * STEP)

    /** `1×`, `1.25×`, `0.8×`. */
    fun label(speed: Float): String = "%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.') + "×"

    fun isSame(a: Float, b: Float): Boolean = abs(a - b) < 0.01f
}

/**
 * Sleep timer rules (todo §4.2): the volume fades out over the last [FADE_MS], and a shake while
 * it fades adds [EXTEND_MS]. Pure Kotlin.
 */
object SleepTimer {
    const val FADE_MS = 10_000L
    const val EXTEND_MS = 5 * 60_000L
    val PRESET_MINUTES = intArrayOf(5, 15, 30, 45, 60, 90)
    const val MAX_MINUTES = 12 * 60

    /** Player volume with [msLeft] to go: full until the fade, then down to silence (squared: even to the ear). */
    fun volume(msLeft: Long): Float {
        if (msLeft >= FADE_MS) return 1f
        if (msLeft <= 0) return 0f
        val f = msLeft.toFloat() / FADE_MS
        return f * f
    }

    /** A shake: acceleration (m/s², gravity included) this far from 1 g. */
    private const val SHAKE_DELTA = 7f
    private const val GRAVITY = 9.81f

    fun isShake(x: Float, y: Float, z: Float): Boolean = abs(sqrt(x * x + y * y + z * z) - GRAVITY) > SHAKE_DELTA
}

/** Skip lengths (todo §3.1, §4.6): settings, 30 s for books and 10 s in music mode by default. Pure Kotlin. */
object Skip {
    const val BOOK_SEC = 30
    const val MUSIC_SEC = 10

    fun ms(music: Boolean, bookSec: Int = BOOK_SEC, musicSec: Int = MUSIC_SEC): Long = (if (music) musicSec else bookSec).coerceIn(1, 600) * 1000L
}

/**
 * A renamed or moved book (todo §3.4): a folder with no history takes over the history of a
 * folder that no longer exists when the files that history recorded (the ones played, so usually
 * only some of the book) are found in it. Files compare by name and size; a size of 0 (not
 * recorded) matches any size. Pure Kotlin.
 */
object BookMatch {
    /** Share of the recorded files that must be found in the folder. */
    const val MIN_SHARE = 0.8

    data class FileKey(val name: String, val size: Long)

    private fun same(a: FileKey, b: FileKey) = a.name == b.name && (a.size == 0L || b.size == 0L || a.size == b.size)

    /**
     * True when [recorded] (the old folder's played files, its current file among them) belongs to
     * [here]: the current file is here, and most recorded files are. One recorded file is a weak
     * sign, so then the folder must also keep its name (a move, not a rename).
     */
    fun matches(here: List<FileKey>, recorded: List<FileKey>, current: FileKey?, sameFolderName: Boolean): Boolean {
        if (recorded.isEmpty() || here.isEmpty()) return false
        if (current != null && here.none { same(it, current) }) return false
        if (recorded.size < 2 && !sameFolderName) return false
        val found = recorded.count { r -> here.any { same(it, r) } }
        return found >= recorded.size * MIN_SHARE
    }

    class Candidate(val folder: String, val recorded: List<FileKey>, val current: FileKey?)

    /** The one old folder that matches [here] (folder [name]); null when none or several do. */
    fun best(here: List<FileKey>, name: String, candidates: List<Candidate>): String? {
        val hits = candidates.filter { c -> matches(here, c.recorded, c.current, c.folder.trimEnd('/').substringAfterLast('/') == name) }
        return hits.singleOrNull()?.folder
    }
}
