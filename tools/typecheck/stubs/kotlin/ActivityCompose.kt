package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext

fun ComponentActivity.setContent(parent: CompositionContext? = null, content: @Composable () -> Unit) {}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> = TODO()

class ManagedActivityResultLauncher<I, O> internal constructor() : ActivityResultLauncher<I>() {
    override fun launch(input: I, options: androidx.core.app.ActivityOptionsCompat?) {}
    override fun unregister() {}
    override val contract: ActivityResultContract<I, *> get() = TODO()
}
