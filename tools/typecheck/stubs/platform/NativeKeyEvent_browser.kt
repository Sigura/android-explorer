// SHIM: on Android KeyEvent.nativeKeyEvent is android.view.KeyEvent; desktop Compose types it as Any.
package com.mediaviewer.ui.browser

internal val Any.repeatCount: Int get() = (this as android.view.KeyEvent).repeatCount
