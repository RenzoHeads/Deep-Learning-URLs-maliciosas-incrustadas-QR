// build-logic/build.gradle.kts
// Registers convention plugins as Gradle plugins so they can be applied by id.

plugins {
    `kotlin-dsl`
}

group = "com.qrsecurity.detector.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.2.0")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    compileOnly("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:1.9.22-1.0.17")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "convention.android.application"
            implementationClass = "com.qrsecurity.detector.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "convention.android.library"
            implementationClass = "com.qrsecurity.detector.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("hilt") {
            id = "convention.hilt"
            implementationClass = "com.qrsecurity.detector.buildlogic.HiltConventionPlugin"
        }
    }
}
