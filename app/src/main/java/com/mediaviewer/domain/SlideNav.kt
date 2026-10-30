package com.mediaviewer.domain

/**
 * Manual Next / Previous in the slideshow (todo §11.1): what to show and when.
 *
 * - **Where items were left:** the last [maxVisits] items remember their elapsed time, show time
 *   and video / GIF position, so going back *or forward* to one goes on from there; an item left
 *   with less than [minLeftMs] to go starts over instead of flashing by.
 * - **Waiting for the target:** a manual step keeps the item on screen until its target is ready.
 *   A second press the same way moves the target one step further; the opposite press cancels the
 *   wait; a target that fails to load is skipped (the target moves on the same way).
 *
 * Offsets are signed steps from the item on screen in the slideshow order (`+2` = two items ahead).
 */
class SlideNav(private val maxVisits: Int = 15, private val minLeftMs: Long = 2_000L) {
    class Visit(val elapsedMs: Long, val showMs: Long, val positionMs: Long)

    /** A step waiting for its target: [dir] is +1 (next) or -1 (previous), [steps] ≥ 1. */
    data class Wait(val dir: Int, val steps: Int) {
        val offset: Int get() = dir * steps
    }

    sealed interface Action {
        /** Wait for the item [offset] steps away (a new target replaces the old one). */
        data class Go(val offset: Int) : Action

        /** Stop waiting; the item on screen stays. */
        data object Cancel : Action

        /** Nothing changes: there is no item further that way. */
        data object Stay : Action
    }

    private val visits = object : LinkedHashMap<String, Visit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Visit>?) = size > maxVisits
    }

    var wait: Wait? = null
        private set

    /** The item [path] is left after [elapsedMs] of its [showMs], at [positionMs] in its video or GIF. */
    fun leave(path: String, elapsedMs: Long, showMs: Long, positionMs: Long) {
        if (elapsedMs <= 0) return
        visits[path] = Visit(elapsedMs, showMs, positionMs)
    }

    /** Where [path] goes on from, if it does, without forgetting it (for preparing it). */
    fun peekResume(path: String): Visit? = visits[path]?.takeIf { it.showMs - it.elapsedMs >= minLeftMs }

    /** Where [path] goes on from, if it does; it is shown now, so the visit is used up either way. */
    fun resume(path: String): Visit? = visits.remove(path)?.takeIf { it.showMs - it.elapsedMs >= minLeftMs }

    /**
     * Next (+1) or previous (-1) pressed. [reachable] says whether an offset has an item (not past
     * the start, not into a random cycle that doesn't exist yet, not round to the item on screen).
     */
    fun press(dir: Int, reachable: (Int) -> Boolean): Action {
        val w = wait
        return when {
            w == null -> if (reachable(dir)) go(Wait(dir, 1)) else Action.Stay
            w.dir != dir -> {
                wait = null
                Action.Cancel
            }
            reachable(w.offset + dir) -> go(Wait(dir, w.steps + 1))
            else -> Action.Stay
        }
    }

    /** The target failed to load: it is skipped, and the wait moves on the same way if it can. */
    fun failed(reachable: (Int) -> Boolean): Action {
        val w = wait ?: return Action.Cancel
        if (!reachable(w.offset + w.dir)) {
            wait = null
            return Action.Cancel
        }
        return go(Wait(w.dir, w.steps + 1))
    }

    /** The switch happened (the target is on screen), or something else replaced the item. */
    fun done() {
        wait = null
    }

    private fun go(w: Wait): Action {
        wait = w
        return Action.Go(w.offset)
    }
}
