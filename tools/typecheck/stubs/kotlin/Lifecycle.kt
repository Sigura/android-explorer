// lifecycle-common / lifecycle-viewmodel 2.8.7 (Google Maven only) — the API the app uses.
package androidx.lifecycle

import android.app.Application
import kotlinx.coroutines.CoroutineScope

abstract class Lifecycle {
    abstract fun addObserver(observer: LifecycleObserver)
    abstract fun removeObserver(observer: LifecycleObserver)
    abstract val currentState: State
    enum class Event {
        ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY;
        val targetState: State get() = TODO()
    }
    enum class State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED;
        fun isAtLeast(state: State): Boolean = compareTo(state) >= 0
    }
}

interface LifecycleObserver

fun interface LifecycleEventObserver : LifecycleObserver {
    fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event)
}

interface DefaultLifecycleObserver : LifecycleObserver {
    fun onCreate(owner: LifecycleOwner) {}
    fun onStart(owner: LifecycleOwner) {}
    fun onResume(owner: LifecycleOwner) {}
    fun onPause(owner: LifecycleOwner) {}
    fun onStop(owner: LifecycleOwner) {}
    fun onDestroy(owner: LifecycleOwner) {}
}

interface LifecycleOwner {
    val lifecycle: Lifecycle
}

abstract class ViewModel {
    constructor()
    constructor(vararg closeables: AutoCloseable)
    protected open fun onCleared() {}
    fun addCloseable(key: String, closeable: AutoCloseable) {}
    open fun addCloseable(closeable: AutoCloseable) {}
}

val ViewModel.viewModelScope: CoroutineScope get() = TODO()

/** Java in the real library: `public <T extends Application> T getApplication()`. */
open class AndroidViewModel(application: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    open fun <T : Application> getApplication(): T = TODO()
}

interface ViewModelStoreOwner

object ViewModelProvider {
    interface Factory
}
