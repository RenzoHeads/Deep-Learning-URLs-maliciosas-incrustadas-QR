package com.qrsecurity.detector.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin that applies Hilt dependency injection to a module.
 * Applies the Hilt Gradle plugin + KSP for annotation processing,
 * and adds the runtime dependencies.
 *
 * Used by both :app (application) and library modules.
 *
 * Audit fix: las versiones se toman del version catalog del proyecto
 * (`gradle/libs.versions.toml`) — antes estaban hardcodeadas ("2.50") y
 * podian divergir del catalogo sin que el build lo detectara.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("com.google.dagger.hilt.android")
            }

            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            val hiltVersion = libs.findVersion("hilt").get().toString()

            dependencies {
                // Hilt is added via the plugin's own dependency configuration,
                // but we need the runtime + compiler explicitly for non-app modules
                add("implementation", "com.google.dagger:hilt-android:$hiltVersion")
                add("ksp", "com.google.dagger:hilt-compiler:$hiltVersion")
            }
        }
    }
}
