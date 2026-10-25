package com.mediaviewer.domain

/** One change between two texts: [removed] characters at [pos] replaced by [inserted] characters. */
data class TextChange(val pos: Int, val removed: Int, val inserted: Int) {
    companion object {
        /** Common prefix and suffix: one pass over the text, fine for the editor's 1 MB limit. */
        fun between(old: CharSequence, new: CharSequence): TextChange? {
            if (old.length == new.length && old == new) return null
            val max = minOf(old.length, new.length)
            var p = 0
            while (p < max && old[p] == new[p]) p++
            var s = 0
            while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
            return TextChange(p, old.length - p - s, new.length - p - s)
        }
    }
}

/**
 * Undo and redo for the editor (todo §6.3). Edits are kept as diffs, not copies of the text, and
 * typing or deleting within [groupMs] of the previous edit, at the same place, is one step.
 */
class EditHistory(private val limit: Int = 500, private val groupMs: Long = 1000) {
    class Step(val text: String, val selStart: Int, val selEnd: Int)

    private class Edit(
        var pos: Int,
        var removed: String,
        var inserted: String,
        val selStart: Int,
        val selEnd: Int,
        var time: Long,
    )

    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()

    /** False right after an undo or redo, so the next edit starts a new step. */
    private var grouping = true

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /**
     * Records the change from [old] to [new]; [selStart]..[selEnd] is the selection before it.
     * Returns the change, or null if the texts are equal.
     */
    fun record(old: String, new: String, selStart: Int, selEnd: Int, now: Long): TextChange? {
        val c = TextChange.between(old, new) ?: return null
        val removed = old.substring(c.pos, c.pos + c.removed)
        val inserted = new.substring(c.pos, c.pos + c.inserted)
        redo.clear()
        val last = undo.lastOrNull()
        if (last != null && grouping && now - last.time < groupMs) {
            // Typing on: insertions right after the last one, without starting a new line.
            if (last.removed.isEmpty() && removed.isEmpty() && c.pos == last.pos + last.inserted.length && '\n' !in inserted) {
                last.inserted += inserted
                last.time = now
                return c
            }
            // Backspace held or repeated: deletions right before the last one.
            if (last.inserted.isEmpty() && inserted.isEmpty() && c.pos + removed.length == last.pos) {
                last.pos = c.pos
                last.removed = removed + last.removed
                last.time = now
                return c
            }
        }
        grouping = true
        undo.addLast(Edit(c.pos, removed, inserted, selStart, selEnd, now))
        if (undo.size > limit) undo.removeFirst()
        return c
    }

    /** [text] with the last edit undone and the selection it had before, or null. */
    fun undo(text: String): Step? {
        val e = undo.removeLastOrNull() ?: return null
        redo.addLast(e)
        grouping = false
        val out = text.replaceRange(e.pos, e.pos + e.inserted.length, e.removed)
        return Step(out, e.selStart.coerceAtMost(out.length), e.selEnd.coerceAtMost(out.length))
    }

    fun redo(text: String): Step? {
        val e = redo.removeLastOrNull() ?: return null
        undo.addLast(e)
        grouping = false
        val out = text.replaceRange(e.pos, e.pos + e.removed.length, e.inserted)
        val cursor = e.pos + e.inserted.length
        return Step(out, cursor, cursor)
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}
