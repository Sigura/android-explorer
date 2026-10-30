package com.mediaviewer.domain

/**
 * What a drop does (todo §11.3): files from other apps are always copied (the other app owns
 * them); this app's own tiles, dragged within a window or between two of its windows (DeX, split
 * screen), are copied, or moved when `Shift` is held at the drop. A tile dropped on its own folder
 * does nothing; a folder dropped into itself is refused. Device folders take no drops.
 */
object DropRules {
    enum class Action { COPY, MOVE, NOTHING, REFUSE }

    /**
     * [paths]: the own files that take part (empty for other apps' files, which go by URI);
     * [refused]: own files left out, with the reason; [reason]: why the whole drop is refused.
     */
    data class Plan(
        val action: Action,
        val paths: List<String> = emptyList(),
        val refused: List<Pair<String, String>> = emptyList(),
        val reason: String? = null,
    )

    /**
     * @param ownPaths the local paths of the dropped files when every one is this app's own tile,
     *   else null (some or all come from another app).
     * @param target the folder dropped on.
     * @param targetIsDevice [target] is a folder on an MTP device.
     * @param shift `Shift` was held at the drop.
     */
    fun plan(ownPaths: List<String>?, target: String, targetIsDevice: Boolean, shift: Boolean): Plan {
        if (targetIsDevice) return Plan(Action.REFUSE, reason = "Files can't be dropped onto a device; use Copy to…")
        if (ownPaths == null) return Plan(Action.COPY)
        val dest = target.trimEnd('/')
        val refused = ArrayList<Pair<String, String>>()
        val kept = ArrayList<String>()
        for (raw in ownPaths) {
            val p = raw.trimEnd('/')
            when {
                dest == p || dest.startsWith("$p/") -> refused.add(raw to "can't put a folder inside itself")
                p.substringBeforeLast('/') == dest -> Unit // dropped back on its own folder
                else -> kept.add(raw)
            }
        }
        val action = when {
            kept.isEmpty() -> Action.NOTHING
            shift -> Action.MOVE
            else -> Action.COPY
        }
        return Plan(action, kept, refused)
    }
}
