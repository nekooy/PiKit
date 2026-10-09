plugins {
    alias(libs.plugins.android.library)
}

// Vendored from termux/termux-app, module `terminal-view` (GPLv3).
// A Canvas-rendered terminal View driven by terminal-emulator's buffer.
// `android { }` resolves to the deprecated `Project.android` accessor, because
// this build keeps `android.newDsl=false` (gradle.properties): the new DSL is
// the `com.android.build.api.dsl.*Extension` one, and moving to it is the same
// AGP-10 migration the suppressed warnings name. Silenced on the block itself,
// so a reader editing this file still gets everything else the Kotlin DSL and
// javac have to say about it.
@Suppress("DEPRECATION")
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
