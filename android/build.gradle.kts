// Top-level build file for the QR Security Detector app.
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
    // KSP — Kotlin Symbol Processing (necesario para Room 2.6.1 con Kotlin 1.9.22)
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
