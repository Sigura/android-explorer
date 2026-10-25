package com.mediaviewer.ui

/** Set by the slideshow: the mouse back button means "previous item" there (README §5.2). */
object MouseBack {
    @Volatile var handler: (() -> Boolean)? = null

    /**
     * Uptime of the last right-button press. DeX turns a right-click that no view consumed into
     * `KEYCODE_BACK` from the mouse; a Back that follows a right-click this closely is dropped,
     * so a stray right-click never goes up a folder or steps back in the slideshow (README §3.9).
     */
    @Volatile var lastSecondaryPress = 0L

    const val RIGHT_CLICK_BACK_MS = 500L
}
