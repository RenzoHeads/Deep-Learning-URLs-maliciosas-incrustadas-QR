import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // KSP — necesario para Room 2.6.1 (reemplaza kapt, mas rapido con Kotlin 1.9.22)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.qrsecurity.detector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qrsecurity.detector"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Play Asset Delivery — large TFLite model delivered as on-demand asset pack.
        // The asset pack is configured separately in the Play Console.
        // TFLite model (~500 MB) uses inflation: ON_DEMAND.
    }

    // Keystore de depuración — mismo certificado que Android Studio usaría
    // por defecto. Permite producir un APK debug firmado sin configuracion
    // adicional.
    //
    // F1 (CWE-326+798+732): el buildType release NO debe firmar con el
    // keystore de depuracion. Antes, release usaba `signingConfig =
    // signingConfigs.getByName("debugFirma")`, lo que firmsba el APK de
    // produccion con el certificado de debug (clave "android" publica y
    // conocida). Cualquiera con el APK podria re-firmarlo con el mismo
    // keystore de debug y distribuir una version modificada. Ahora:
    //   - debug usa debugFirma (certificado de debug, sigiloso).
    //   - release carga el keystore desde `keystore.properties` (en
    //     .gitignore) o desde variables de entorno
    //     ($RELEASE_STORE_FILE, $RELEASE_STORE_PASSWORD,
    //     $RELEASE_KEY_ALIAS, $RELEASE_KEY_PASSWORD). Si ninguna fuente
    //     esta disponible, release NO se firma (el APK no sera
    //     instalable en dispositivos, pero el build no falla — util
    //     para CI sin secrets).
    signingConfigs {
        create("debugFirma") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // releaseFirma se construye dinamicamenteabajo si hay secrets
        // disponibles; si no, queda null y release no se firma.
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
            // F1: release se firma con releaseFirma solo si los secrets
            // del keystore estan disponibles. Si no, se deja sin firma
            // (el APK no sera instalable, pero el build no falla).
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs +
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api" +
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Kotlin 1.9.22 maps to Compose Compiler 1.5.8
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // Do NOT split APKs by ABI for simplicity with native TFLite libs.
    // bundling TFLite native libs across all ABIs.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // KSP — Room schema location (para exportar el schema JSON a /schemas)
    // ──────────────────────────────────────────────────────────────────
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }

    // ──────────────────────────────────────────────────────────────────
    // testOptions — necesario para Compose UI tests bajo Robolectric.
    // `includeAndroidResources = true` hace que Robolectric lea el manifest
    // fusionado de la app (con sus activities registradas) en lugar de un
    // manifest vacio por defecto. Sin esto, createAndroidComposeRule<*>
    // falla con "Unable to resolve activity for Intent ... ComponentActivity".
    // Permite tambien a Robolectric resolver recursos reales de la app
    // (strings, drawables) durante los tests unitarios.
    // ──────────────────────────────────────────────────────────────────
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // ──────────────────────────────────────────────────────────────────
    // Kotlin Coroutines
    // ──────────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // ──────────────────────────────────────────────────────────────────
    // AndroidX Core
    // ──────────────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // ──────────────────────────────────────────────────────────────────
    // Play Asset Delivery — delivers the ~500 MB TFLite model on demand.
    // ──────────────────────────────────────────────────────────────────
    implementation("com.google.android.play:asset-delivery:2.2.2")
    implementation("com.google.android.play:asset-delivery-ktx:2.2.2")

    // ──────────────────────────────────────────────────────────────────
    // CameraX — camera preview + frame analysis for QR scanning.
    // ──────────────────────────────────────────────────────────────────
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // ──────────────────────────────────────────────────────────────────
    // ML Kit Barcode Scanning (not bundled model version — uses GMS).
    // ──────────────────────────────────────────────────────────────────
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // ──────────────────────────────────────────────────────────────────
    // ZXing — fallback QR decoder if ML Kit is unavailable (no GMS).
    // ──────────────────────────────────────────────────────────────────
    implementation("com.google.zxing:core:3.5.3")

    // ──────────────────────────────────────────────────────────────────
    // TensorFlow Lite — on-device inference of CANINE-S INT8 model.
    // ──────────────────────────────────────────────────────────────────
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // Selectable delegate libraries — the app auto-selects NNAPI first,
    // then GPU, then plain CPU. These are optional but recommended.
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")

    // ──────────────────────────────────────────────────────────────────
    // Jetpack Compose
    // ──────────────────────────────────────────────────────────────────
    // BOM subida de 2024.01.00 a 2024.03.00 para alinear material3 con
    // animation-core (fix NoSuchMethodError `KeyframesSpec$KeyframeEntity.at(...)`
    // en CircularProgressIndicator al iniciar sesion — Bug 1).
    implementation(platform("androidx.compose:compose-bom:2024.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")

    // ──────────────────────────────────────────────────────────────────
    // Navigation Compose — NavHost routing between the 8 screens.
    // ──────────────────────────────────────────────────────────────────
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ──────────────────────────────────────────────────────────────────
    // OkHttp — cliente HTTP para /auth/registrar y endpoints del backend.
    // ──────────────────────────────────────────────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // HttpLoggingInterceptor — usado por ClienteBackend para loggear requests
    // cuerpos cuando BuildConfig.DEBUG == true (Batch 10). Mismo version-bucket
    // que OkHttp para evitar mismatch de clases internas.
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ──────────────────────────────────────────────────────────────────
    // Room — Offline-first local source of truth (SQLite + Flow)
    // Fuente de verdad local. Sobrevive cierre de app. Sync outbox涧
    // ──────────────────────────────────────────────────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ──────────────────────────────────────────────────────────────────
    // WorkManager — SyncWorker: drena el outbox + pull cuando hay red
    // ──────────────────────────────────────────────────────────────────
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ──────────────────────────────────────────────────────────────────
    // EncryptedSharedPreferences — token_api con AES-GCM (migracion de SharedPreferences plain)
    // ──────────────────────────────────────────────────────────────────
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ──────────────────────────────────────────────────────────────────
    // lifecycle-runtime-compose — collectAsStateWithLifecycle (Flow → Compose State)
    // Requerido por las pantallas que observan Room via Flow
    // ──────────────────────────────────────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Preview support for Android Studio Layout Inspector.
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ──────────────────────────────────────────────────────────────────
    // Testing
    // ──────────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    // kotlin-test — wrapper multiplataforma sobre JUnit (assertEquals,
    // assertTrue, assertFailsWith, etc.). usado por los tests cache/qr/ui
    // que importan `kotlin.test.*`. Sobre JVM se mapea a JUnit 4 automaticamente
    // cuando kotlin-test-junit esta en el classpath. Version alineada con el
    // plugin kotlin-android (1.9.22) — versioneada en build.gradle.kts top-level.
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.22")
    // Robolectric — permite correr tests que necesitan Context/Room en la JVM
    // local sin un device/emulador. Requerido por OrphanCleanupTest (Room in-memory).
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    // Room testing — `Room.inMemoryDatabaseBuilder` + allowMainThreadQueries en tests.
    testImplementation("androidx.room:room-testing:2.6.1")
    // Coroutines test — `StandardTestDispatcher` + `runTest` para suspending test
    // functions con dispatcher determinista.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // WorkManager testing — inicializa WorkManager en Robolectric tests antes
    // de que AppSeguridadQR.onCreate intente crear MediadorSincronizacion, el
    // cual llama a WorkManager.getInstance(context). Necesario para Compose
    // UI tests que indirectamente cargan la Application (Robolectric).
    testImplementation("androidx.work:work-testing:2.9.0")
    // MockWebServer — tests HTTP-level de ClienteBackend bajo MockWebServer
    // (auth header, 401 handling, retry-after, redact verify). Misma version
    // que okhttp:4.12.0 para evitar mismatch de clases internas.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Compose UI testing bajo Robolectric — createAndroidComposeRule()
    // setContent + onNode*. Necesario para tests de Pantalla Acerca (H3
    // logout dialog) y Denunciar (H4 reset syncDisparada).
    testImplementation(platform("androidx.compose:compose-bom:2024.03.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.material3:material3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
