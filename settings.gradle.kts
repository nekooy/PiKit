pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PiKit"

include(":app")

// Vendored from termux-app (GPLv3). These two modules are self-contained:
// terminal-emulator is pure Java + one C file exposing a PTY via JNI, and
// terminal-view depends only on terminal-emulator + androidx.annotation.
// Neither needs termux-shared, which is why we can vendor exactly these.
include(":terminal-emulator")
include(":terminal-view")
