// Top-level build file for the QR Security Detector app.
// Plugin versions are declared in gradle/libs.versions.toml and applied
// here via the version catalog alias (libs.plugins.*).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
