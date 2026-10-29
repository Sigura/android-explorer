plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mediaviewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mediaviewer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        // Git hash injected by build-apk.sh via -PgitHash=<sha>.
        versionName = "0.1.0-" + (project.findProperty("gitHash") as? String ?: "dev")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No release keystore yet: sign with the debug key so the APK installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes.getByName("debug") {
        // JaCoCo line coverage of the JVM tests (tools/check-media-folder.sh).
        enableUnitTestCoverage = true
    }

    testOptions {
        // android.util.Log, SystemClock and FileObserver are no-ops in JVM tests.
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.testLogging { showStandardStreams = true }
            it.maxHeapSize = "6g"
            // Opt-in real-folder checks read their settings from the environment.
            listOf("MV_CHECK_DIR", "MV_CHECK_THREADS", "MV_CHECK_REPORT", "MV_CHECK_LARGE_GIF_MB", "MV_GIF_REF", "MV_GIF_BENCH", "MV_GIF_DUMP")
                .forEach { k -> System.getenv(k)?.let { v -> it.environment(k, v) } }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // Installs src/main/baseline-prof.txt on release installs (README §8).
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    // FFmpeg audio decoders (AC-3, E-AC-3, DTS, TrueHD, …) for files the device can't decode itself.
    implementation(libs.media3.ffmpeg)

    // SVG tiles (todo §10.10); no other SVG use.
    implementation(libs.androidsvg)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
