// androidx.activity 1.10.1 (Google Maven only) — the API the app uses.
package androidx.activity

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner

open class ComponentActivity : android.app.Activity(), LifecycleOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
    override val lifecycle: Lifecycle get() = TODO()
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = TODO()
}

interface OnBackPressedDispatcherOwner : LifecycleOwner {
    val onBackPressedDispatcher: OnBackPressedDispatcher
}

class OnBackPressedDispatcher {
    fun onBackPressed() {}
}

class SystemBarStyle private constructor() {
    companion object {
        fun auto(lightScrim: Int, darkScrim: Int): SystemBarStyle = TODO()
        fun dark(scrim: Int): SystemBarStyle = TODO()
        fun light(scrim: Int, darkScrim: Int): SystemBarStyle = TODO()
    }
}

fun ComponentActivity.enableEdgeToEdge(
    statusBarStyle: SystemBarStyle = SystemBarStyle.auto(0, 0),
    navigationBarStyle: SystemBarStyle = SystemBarStyle.auto(0, 0),
) {}

@Suppress("UNUSED_PARAMETER")
inline fun <reified VM : ViewModel> ComponentActivity.viewModels(
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null,
): Lazy<VM> = TODO()
