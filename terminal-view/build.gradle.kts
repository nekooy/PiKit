plugins {
    alias(libs.plugins.android.library)
}

// Vendored from termux/termux-app, module `terminal-view` (GPLv3).
// A Canvas-rendered terminal View driven by terminal-emulator's buffer.
android {
    namespace = "com.termux.view"
    compileSdk = providers.gradleProperty("pikit.compileSdk").get().toInt()

    defaultConfig {
        minSdk = providers.gradleProperty("pikit.minSdk").get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    api(project(":terminal-emulator"))
    implementation(libs.androidx.annotation)
}
