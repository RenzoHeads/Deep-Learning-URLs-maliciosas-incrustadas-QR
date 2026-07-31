package com.qrsecurity.detector.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin that applies Hilt dependency injection to a module.
 * Applies the Hilt Gradle plugin + KSP for annotation processing,
 * and adds the runtime dependencies.
 *
 * Used by both :app (application) and library modules.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("com.google.dagger.hilt.android")
            }

            dependencies {
                // Hilt is added via the plugin's own dependency configuration,
                // but we need the runtime + compiler explicitly for non-app modules
                add("implementation", "com.google.dagger:hilt-android:2.50")
                add("ksp", "com.google.dagger:hilt-compiler:2.50")
            }
        }
    }
}
