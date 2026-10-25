package com.mediaviewer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mediaviewer.data.settings.ThemeMode

private val Dark = darkColorScheme(
    primary = Color(0xFF9CC3F5),
    secondary = Color(0xFFB6C8DA),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1D2733),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2B5F96),
    secondary = Color(0xFF52606F),
)

@Composable
fun MediaViewerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val ctx = LocalContext.current
    var scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    if (mode == ThemeMode.AMOLED) {
        scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF0A0A0A))
    }
    MaterialTheme(colorScheme = scheme) {
        // Screens draw their own backgrounds instead of sitting in a Surface, so untinted icons and
        // text would otherwise fall back to Compose's default black, unreadable on dark themes.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
