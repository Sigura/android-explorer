package com.mediaviewer.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mediaviewer.R
import com.mediaviewer.data.fs.TAG

/**
 * The font for code, logs and tables (todo §10.9): JetBrains Mono, bundled (SIL OFL 1.1, licence in
 * `assets/licenses/`), in its no-ligature edition and subset to Latin, Cyrillic, Greek, punctuation,
 * arrows and box drawing; or the system `monospace` when the setting says so.
 */
object CodeFonts {
    private val jetBrainsMono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
        Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
    )

    @Volatile private var typeface: Typeface? = null

    /** For Compose text: the editor. */
    fun family(bundled: Boolean): FontFamily = if (bundled) jetBrainsMono else FontFamily.Monospace

    /** For canvas drawing: monospace text previews on tiles. Loaded once; the system font if it can't be. */
    fun typeface(context: Context, bundled: Boolean): Typeface {
        if (!bundled) return Typeface.MONOSPACE
        typeface?.let { return it }
        return try {
            context.resources.getFont(R.font.jetbrains_mono_regular)
        } catch (e: Exception) {
            Log.w(TAG, "JetBrains Mono not loaded", e)
            Typeface.MONOSPACE
        }.also { typeface = it }
    }
}
