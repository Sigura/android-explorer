package com.mediaviewer.ui.browser

import android.app.Activity
import android.content.ClipDescription
import android.net.Uri
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent

/**
 * Drop target of the folder view (todo §10.5): files and folders dragged in from other apps
 * (My Files in DeX, a browser, split screen). Text drags are not accepted.
 */
internal class FileDrop(
    private val activity: Activity?,
    private val onHover: (Boolean) -> Unit,
    /**
     * The dropped URIs and whether `Shift` was held (todo §11.3: own tiles move); call `release`
     * when the copy is over, to give up the read permission.
     */
    private val onDrop: (uris: List<Uri>, shift: Boolean, release: () -> Unit) -> Unit,
) : DragAndDropTarget {
    override fun onEntered(event: DragAndDropEvent) = onHover(true)

    override fun onExited(event: DragAndDropEvent) = onHover(false)

    override fun onEnded(event: DragAndDropEvent) = onHover(false)

    override fun onDrop(event: DragAndDropEvent): Boolean {
        onHover(false)
        val ev = event.toAndroidDragEvent()
        val clip = ev.clipData ?: return false
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isEmpty()) return false
        // Read access to content URIs of other apps lasts until release (or the activity ends).
        val perms = activity?.requestDragAndDropPermissions(ev)
        onDrop(uris, shiftHeld) { perms?.release() }
        return true
    }

    companion object {
        /**
         * `Shift` is held, as the app last saw it (todo §11.3). A drop event carries no key state,
         * so `MainActivity` keeps this from key events and the meta state of mouse events, in every
         * window of the app (one process); it is cleared when a window loses focus, since the
         * release would go elsewhere. During a drag the keyboard focus stays with the window the
         * drag started in, which is ours for our own tiles (**verify** on DeX).
         */
        @Volatile var shiftHeld = false

        private val TEXT = setOf(ClipDescription.MIMETYPE_TEXT_PLAIN, ClipDescription.MIMETYPE_TEXT_HTML, ClipDescription.MIMETYPE_TEXT_INTENT)

        /** A drag that may carry files: any MIME type besides plain text, HTML and intents. */
        fun accepts(event: DragAndDropEvent): Boolean {
            val d = event.toAndroidDragEvent().clipDescription ?: return false
            return (0 until d.mimeTypeCount).any { d.getMimeType(it) !in TEXT }
        }
    }
}
