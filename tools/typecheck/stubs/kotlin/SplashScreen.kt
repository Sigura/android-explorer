// androidx.core:core-splashscreen 1.0.1
package androidx.core.splashscreen

import android.app.Activity

class SplashScreen private constructor() {
    companion object {
        @JvmStatic
        fun Activity.installSplashScreen(): SplashScreen = TODO()
    }
    fun interface KeepOnScreenCondition { fun shouldKeepOnScreen(): Boolean }
    fun interface OnExitAnimationListener { fun onSplashScreenExit(splashScreenViewProvider: Any) }
    fun setKeepOnScreenCondition(condition: KeepOnScreenCondition) {}
    fun setOnExitAnimationListener(listener: OnExitAnimationListener) {}
}
