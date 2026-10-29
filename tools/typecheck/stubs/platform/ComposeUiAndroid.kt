// Android-only parts of compose-ui 1.8 that desktop Compose doesn't have (AndroidCompositionLocals.android.kt).
package androidx.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf

val LocalContext: ProvidableCompositionLocal<Context> = staticCompositionLocalOf { TODO() }
val LocalConfiguration: ProvidableCompositionLocal<Configuration> = compositionLocalOf { TODO() }
val LocalView: ProvidableCompositionLocal<View> = staticCompositionLocalOf { TODO() }
