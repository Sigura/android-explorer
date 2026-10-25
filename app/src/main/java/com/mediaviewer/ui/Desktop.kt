package com.mediaviewer.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.mediaviewer.data.settings.MouseMode

/** Samsung DeX and Chromebook detection (README §3.9). */
object Desktop {
    fun isDesktop(context: Context): Boolean =
        isDex(context.resources.configuration) || context.packageManager.hasSystemFeature(PackageManager.FEATURE_PC)

    /** Samsung's own flag, read by reflection: `semDesktopModeEnabled == SEM_DESKTOP_MODE_ENABLED`. */
    fun isDex(c: Configuration): Boolean = try {
        val cls = c.javaClass
        cls.getField("semDesktopModeEnabled").getInt(c) == cls.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null)
    } catch (e: Exception) {
        false
    }
}

/** Whether mouse clicks follow desktop rules (README §2, "Desktops"); re-read when DeX is switched on or off. */
@Composable
fun desktopClicks(mode: MouseMode): Boolean {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val desktop = remember(cfg) { Desktop.isDesktop(ctx) }
    return when (mode) {
        MouseMode.AUTO -> desktop
        MouseMode.GALLERY -> false
        MouseMode.DESKTOP -> true
    }
}
