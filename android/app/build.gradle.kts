import java.util.Properties

plugins {
    id("convention.android.application")
    alias(libs.plugins.kotlin.serialization)
    id("convention.hilt")
}

android {
    namespace = "com.qrsecurity.detector"

    defaultConfig {
        applicationId = "com.qrsecurity.detector"
        versionCode = 1
        versionName = "1.0.0"
    }

    // ──────────────────────────────────────────────────────────────────
    // Signing configs
    //
    // F1 (CWE-326+798+732): release never signs with the debug keystore.
    // Debug uses a dedicated debugFirma config; release loads secrets from
    // keystore.properties or env vars. If no secrets are available, release
    // is left unsigned (build succeeds, APK not installable).
    // ──────────────────────────────────────────────────────────────────
    signingConfigs {
        create("debugFirma") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (System.getenv("RELEASE_STORE_FILE") != null ||
            file("../keystore.properties").exists()) {

            val keystoreProperties = Properties()
            val propsFile = file("../keystore.properties")
            if (propsFile.exists()) {
                keystoreProperties.load(propsFile.inputStream())
            }

            create("releaseFirma") {
                storeFile = file(
                    System.getenv("RELEASE_STORE_FILE")
                        ?: keystoreProperties.getProperty("storeFile")
                )
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    ?: keystoreProperties.getProperty("storePassword")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    ?: keystoreProperties.getProperty("keyAlias")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            val releaseSigning = signingConfigs.findByName("releaseFirma")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugFirma")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // KSP — Room schema location for exporting schema JSON to /schemas
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

dependencies {
    // ─── Kotlin Coroutines ───
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ─── AndroidX Core ───
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.activity.compose)

    // ─── Lifecycle ───
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ─── Play Asset Delivery — ~500 MB TFLite model on demand ───
    implementation(libs.play.asset.delivery)
    implementation(libs.play.asset.delivery.ktx)

    // ─── CameraX ───
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ─── ML Kit Barcode Scanning (GMS) ───
    implementation(libs.mlkit.barcode.scanning)

    // ─── ZXing — fallback QR decoder ───
    implementation(libs.zxing.core)

    // ─── TensorFlow Lite ───
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api)
    implementation(libs.tensorflow.lite.gpu.delegate.plugin)

    // ─── Jetpack Compose (BOM-managed) ───
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.runtime)

    // ─── Navigation Compose ───
    implementation(libs.androidx.navigation.compose)

    // ─── OkHttp ───
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // ─── Room ───
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ─── WorkManager ───
    implementation(libs.androidx.work.runtime.ktx)

    // ─── Security Crypto (EncryptedSharedPreferences) ───
    implementation(libs.androidx.security.crypto)

    // ─── Hilt ───
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // ─── Debug / Preview ───
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // ─── Testing ───
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.material3)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
