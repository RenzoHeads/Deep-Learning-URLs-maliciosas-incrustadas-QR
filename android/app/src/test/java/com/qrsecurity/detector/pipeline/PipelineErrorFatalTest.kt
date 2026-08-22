package com.qrsecurity.detector.pipeline

import com.qrsecurity.detector.ui.PipelineViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.ml.MotorInferencia
import com.qrsecurity.detector.ml.MotorInferenciaFake
import com.qrsecurity.detector.ml.setupTestVocab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * U4: ``UnsatisfiedLinkError`` (JNI de TFLite) y ``OutOfMemoryError``
 * extienden ``java.lang.Error``, no ``Exception`` — el catch anterior solo
 * capturaba ``Exception`` y esos errores crashaban el proceso (escapaban
 * del viewModelScope). Contrato: cualquier Throwable no-cancellation se
 * traduce a [Estado.Error] y la app sigue viva.
 *
 * Misma infraestructura que [PipelineDedupTest]: Pipeline real + Room
 * in-memory, con un motor que lanza un ``Error`` de JVM en ``inferir``.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PipelineErrorFatalTest {

    private class MotorQueExploraFatal : MotorInferencia {
        override val nombreDelegado: String = "FATAL"
        override val devuelveLogits: Boolean = false
        override fun inferir(entradaTokenizada: Array<IntArray>): FloatArray {
            // java.lang.Error — NO Exception: exactamente la clase de fallo
            // que el catch antiguo dejaba escapar.
            throw OutOfMemoryError("Failed to allocate allocation (simulado)")
        }
        override fun cerrar() {}
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: BaseDatosSeguridad
    private lateinit var vm: PipelineViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build()
        )
        db = Room.inMemoryDatabaseBuilder(
            app,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
        val backend = ClienteBackend()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val repo = RepositorioEscaneos(db, backend, json, testDispatcher)
        val repoUrlsBloqueadas = RepositorioUrlsBloqueadas(db, backend, json, testDispatcher)
        val mediadorSync = MediadorSincronizacion(app)
        setupTestVocab()
        val pipeline = Pipeline(
            app, backend, repo, repoUrlsBloqueadas, mediadorSync,
            MotorQueExploraFatal()
        )
        vm = PipelineViewModel(pipeline, SavedStateHandle())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun drenar(scope: TestScope) {
        repeat(8) {
            Thread.sleep(40)
            scope.advanceUntilIdle()
        }
        scope.advanceUntilIdle()
    }

    @Test
    fun `error fatal de JVM se traduce a Estado Error sin crash`() = runTest(testDispatcher) {
        vm.analizar("https://fatal.example.com/path", forzar = true)
        drenar(this)

        val estado = vm.estado.value
        assertTrue(
            "Un Error fatal debe quedar como Estado.Error, fue: $estado",
            estado is Estado.Error
        )
        val error = estado as Estado.Error
        // Contrato nuevo (rediseño UI): el usuario ve un mensaje legible; el
        // detalle técnico (clase + mensaje del Throwable) va a logcat.
        assertTrue(
            "El usuario debe ver el mensaje legible, fue: ${error.mensaje}",
            error.mensaje.contains("No pudimos completar el análisis")
        )
        assertTrue(
            "La clase del Throwable debe conservarse en logcat (tag Pipeline)",
            ShadowLog.getLogs()
                .any { it.tag == "Pipeline" && it.msg.contains("OutOfMemoryError") }
        )
    }
}
