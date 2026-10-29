package androidx.activity.result

import androidx.activity.result.contract.ActivityResultContract

abstract class ActivityResultLauncher<I> {
    open fun launch(input: I) = launch(input, null)
    abstract fun launch(input: I, options: androidx.core.app.ActivityOptionsCompat?)
    abstract fun unregister()
    abstract val contract: ActivityResultContract<I, *>
}

fun ActivityResultLauncher<Unit>.launch(options: androidx.core.app.ActivityOptionsCompat? = null) {}

class ActivityResult(val resultCode: Int, val data: android.content.Intent?)
