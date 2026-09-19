plugins {
    // Both AGP plugin ids must be registered here. Declaring only
    // `android.application` leaves `android.library` unversioned on the build
    // classpath, which fails in the subprojects with "already on the classpath
    // with an unknown version".
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
