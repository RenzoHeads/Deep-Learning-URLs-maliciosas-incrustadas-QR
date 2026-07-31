package com.qrsecurity.detector.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Convention plugin for Android Library modules (:core:*, :feature:*).
 * Centralizes: compileSdk, minSdk, Java 17, Kotlin JVM target, and common Android options.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<LibraryExtension> {
                compileSdk = 35

                defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    consumerProguardFiles("consumer-rules.pro")
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                buildFeatures {
                    buildConfig = true
                }

                testOptions {
                    unitTests {
                        isIncludeAndroidResources = true
                    }
                }
            }

            extensions.configure<KotlinAndroidProjectExtension> {
                jvmToolchain(17)
                compilerOptions {
                    freeCompilerArgs.addAll(
                        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                        "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                    )
                }
            }
        }
    }
}
