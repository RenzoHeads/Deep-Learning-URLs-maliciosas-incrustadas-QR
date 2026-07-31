package com.qrsecurity.detector.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Convention plugin for the Android Application module (:app).
 * Centralizes: compileSdk, minSdk, targetSdk, Java 17, Kotlin JVM target,
 * Compose compiler version, packaging, and test options.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<ApplicationExtension> {
                compileSdk = 35

                defaultConfig {
                    minSdk = 26
                    targetSdk = 35
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    vectorDrawables {
                        useSupportLibrary = true
                    }
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                buildFeatures {
                    compose = true
                    buildConfig = true
                }

                composeOptions {
                    kotlinCompilerExtensionVersion = "1.5.8"
                }

                packaging {
                    resources {
                        excludes += "/META-INF/{AL2.0,LGPL2.1}"
                        excludes += "/META-INF/DEPENDENCIES"
                    }
                }

                testOptions {
                    unitTests {
                        isIncludeAndroidResources = true
                    }
                }
            }

            // Configure Kotlin separately via the KotlinAndroidProjectExtension
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
