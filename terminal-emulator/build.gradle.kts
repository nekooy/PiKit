plugins {
    alias(libs.plugins.android.library)
}

// Vendored from termux/termux-app, module `terminal-emulator` (GPLv3).
// Provides: an xterm-compatible VT parser/terminal buffer (pure Java) and a
// JNI PTY allocator (src/main/jni/termux.c) used for the embedded shell.
// `android { }` resolves to the deprecated `Project.android` accessor, because
// this build keeps `android.newDsl=false` (gradle.properties): the new DSL is
// the `com.android.build.api.dsl.*Extension` one, and moving to it is the same
// AGP-10 migration the suppressed warnings name. Silenced on the block itself,
// so a reader editing this file still gets everything else the Kotlin DSL and
// javac have to say about it.
@Suppress("DEPRECATION")
android {
    namespace = "com.termux.emulator"
    compileSdk = providers.gradleProperty("pikit.compileSdk").get().toInt()
    ndkVersion = "29.0.14033849"

    defaultConfig {
        minSdk = providers.gradleProperty("pikit.minSdk").get().toInt()

        externalNativeBuild {
            ndkBuild {
                // Mirrors the upstream Android.mk flags, minus -Werror, which
                // is too brittle across NDK releases for vendored code.
                cFlags += listOf(
                    "-std=c11",
                    "-Wall",
                    "-Wextra",
                    "-Os",
                    "-fno-stack-protector",
                    "-ffunction-sections",
                    "-fdata-sections",
                )
            }
        }

        ndk {
            // Both flavours are supported; the app module picks one per APK.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
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
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
