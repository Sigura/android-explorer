package com.mediaviewer.domain

/** How long a slideshow item stays on screen (README §5.1). */
data class SlideTiming(
    val showMs: Long,
    /** Loop the animation/video while it is shown. */
    val loop: Boolean,
    /** The item is longer than R: show the thin progress bar. */
    val longerThanInterval: Boolean,
)

object SlideTimer {
    /**
     * @param durationMs duration of an animated image or video, or null for a static image
     *   (or an animation whose duration can't be read, which is then looped for R).
     * @param revisit the item is shown again, going on from where it was left: it gets its full
     *   time and loops instead of ending (and moving on) when its video reaches the end.
     */
    fun timing(durationMs: Long?, intervalMs: Long, revisit: Boolean = false): SlideTiming {
        if (durationMs == null || durationMs <= 0) return SlideTiming(intervalMs, loop = true, longerThanInterval = false)
        val t = when {
            // Nothing is on screen for less than ⅔ R: shorter animations loop until R has passed.
            durationMs * 3 < intervalMs * 2 -> SlideTiming(intervalMs, loop = true, longerThanInterval = false)
            durationMs * 2 <= intervalMs * 3 -> SlideTiming(durationMs, loop = false, longerThanInterval = false)
            else -> SlideTiming(durationMs, loop = false, longerThanInterval = true)
        }
        return if (revisit) t.copy(loop = true) else t
    }
}
