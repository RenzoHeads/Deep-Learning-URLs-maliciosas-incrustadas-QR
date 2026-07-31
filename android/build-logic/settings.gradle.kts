// build-logic/settings.gradle.kts
// Convention plugin project — compiled against the same Gradle as the root build.

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
