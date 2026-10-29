// compose-ui 1.8 androidx.compose.ui.viewinterop.AndroidView (Android-only).
package androidx.compose.ui.viewinterop

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

val NoOpUpdate: View.() -> Unit = {}

@Composable
fun <T : View> AndroidView(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = NoOpUpdate,
) {}

@Composable
fun <T : View> AndroidView(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    onReset: ((T) -> Unit)? = null,
    onRelease: (T) -> Unit = NoOpUpdate,
    update: (T) -> Unit = NoOpUpdate,
) {}
