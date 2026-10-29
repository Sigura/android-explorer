// Stand-ins for the Gradle-generated R and BuildConfig of com.mediaviewer.
package com.mediaviewer

object BuildConfig {
    const val DEBUG: Boolean = true
    const val APPLICATION_ID: String = "com.mediaviewer"
    const val BUILD_TYPE: String = "debug"
    const val VERSION_CODE: Int = 1
    const val VERSION_NAME: String = "0.1.0-dev"
}

object R {
    object font {
        const val jetbrains_mono_regular: Int = 0x7f080001
        const val jetbrains_mono_bold: Int = 0x7f080002
        const val jetbrains_mono_italic: Int = 0x7f080003
    }
}
