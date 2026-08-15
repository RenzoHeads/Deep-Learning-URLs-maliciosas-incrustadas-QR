package com.qrsecurity.detector.pipeline

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug 1 — Race condition post-escaneo de URL nueva.
 *
 * `Estado.ResultadoListo` debe exponer el `idLocal` (UUID) asignado al escaneo
 * persistido, para que [com.qrsecurity.detector.ui.AnalisisScreen] navegue a
 * DetalleUrl con el id exacto en vez de hacer un match heuristico en el Flow
 * `historial` (que sufre race con la emision de Room → id invalido →
 * DetalleUrlViewModel muestra "NoEncontrado").
 *
 * Contrato de este test:
 *  - `analizar(urlValida, forzar=true)` → `ResultadoListo` cuyo `idLocal` es
 *    no-nulo, no-vacio y coincide con una fila en `escaneos` (via
 *    `EscaneoDao.obtenerPorId`), y esa fila tiene el mismo `urlLimpia` del
 *    resultado.
 *  - `analizar(payloadNoUrl)` → `ResultadoListo` cuyo `idLocal` es `null`
 *    (path NoUrl no persiste → no hay id).
 *
 * Red: este test no compila porque `ResultadoListo` no tiene `idLocal` aun.
 * Green: anadir `val idLocal: String? = null` a `ResultadoListo`, propagar
 *   el UUID de `registrarLocal` → `registrarEscaneoLocal` → `analizar` →
 *   `ResultadoListo`, y este test compila y pasa.
 *
 * Estrategia: mismo patron que [PipelineDedupTest] — instancia real del
 * [Pipeline] + [PipelineViewModel] sobre Room in-memory. El
 * `withContext(Dispatchers.Default)` interno del Pipeline corre en un OS thread
 * real; el helper `drenar` (advanceUntilIdle + Thread.sleep) espera a que el
 * estado se asiente. El motor de inferencia es el placeholder aleatorio
 * determinista (no carga TFLite).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResultadoListoIdLocalTest {

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
        val backend = ClienteBackend() // no se invoca (sin sync)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val repo = RepositorioEscaneos(db, backend, json, testDispatcher)
        val repoUrlsBloqueadas = RepositorioUrlsBloqueadas(db, backend, json, testDispatcher)
        val mediadorSync = MediadorSincronizacion(app)
        com.qrsecurity.detector.ml.setupTestVocab()
        val pipeline = Pipeline(app, db, backend, json, repo, repoUrlsBloqueadas, mediadorSync, com.qrsecurity.detector.ml.MotorInferenciaFake())
        vm = PipelineViewModel(pipeline, SavedStateHandle())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /** Drena el OS thread del Pipeline (Dispatchers.Default) + el testDispatcher. */
    private suspend fun drenar(scope: TestScope) {
        repeat(8) {
            Thread.sleep(40)
            scope.advanceUntilIdle()
        }
        scope.advanceUntilIdle()
    }

    /** URL con path (limpiarUrl no la reduce a vacio) y host ASCII valido. */
    private val urlValida = "https://www.bug1-test.example.com/path"

    @Test
    fun `analizar url valida produce ResultadoListo con idLocal no nulo coincidente con DB`() =
        runTest(testDispatcher) {
            // When: escanear una URL nueva (forzar=true para obviar el dedup
            // y garantizar persistencia + ResultadoListo).
            vm.analizar(urlValida, forzar = true)
            drenar(this)

            // Then: el estado es ResultadoListo.
            val estado = vm.estado.value
            assertTrue(
                "El estado debe ser ResultadoListo tras analizar URL valida, fue: $estado",
                estado is Estado.ResultadoListo
            )
            val resultadoListo = estado as Estado.ResultadoListo

            // El resultado interno debe ser ResultadoUrl (no NoUrl).
            assertTrue(
                "El resultado debe ser ResultadoUrl, fue: ${resultadoListo.resultado}",
                resultadoListo.resultado is ResultadoAnalisis.ResultadoUrl
            )

            // ── Bug 1 contrato: idLocal no nulo ni vacio ──
            // Red: esta linea no compila porque ResultadoListo aun no tiene idLocal.
            val idLocal = resultadoListo.idLocal
            assertNotNull(
                "idLocal no debe ser null tras persistir un escaneo de URL",
                idLocal
            )
            assertTrue(
                "idLocal no debe ser vacio tras persistir un escaneo de URL",
                idLocal!!.isNotEmpty()
            )

            // ── Bug 1 contrato: idLocal coincide con una fila en escaneos ──
            val fila = db.escaneoDao().obtenerPorId(idLocal)
            assertNotNull(
                "idLocal debe coincidir con una fila en la tabla escaneos. " +
                    "idLocal=$idLocal no encontrado en DB.",
                fila
            )

            // La fila persistida tiene el mismo urlLimpia del resultado.
            val resultadoUrl =
                resultadoListo.resultado as ResultadoAnalisis.ResultadoUrl
            assertEquals(
                "La fila persistida debe tener el mismo urlLimpia del resultado",
                resultadoUrl.urlLimpia,
                fila!!.urlLimpia
            )

            // Solo debe haber un escaneo en el log (esta URL, nueva).
            assertEquals(
                "Debe haber exactamente 1 escaneo en la DB tras un analisis de URL nueva",
                1,
                db.escaneoDao().todosLosIds().size
            )
        }

    @Test
    fun `analizar no url produce ResultadoListo con idLocal null`() =
        runTest(testDispatcher) {
            // When: escanear un payload que NO contiene una URL (vCard).
            vm.analizar("BEGIN:VCARD\nFN:Test\nEND:VCARD")
            drenar(this)

            // Then: el estado es ResultadoListo con resultado NoUrl.
            val estado = vm.estado.value
            assertTrue(
                "El estado debe ser ResultadoListo, fue: $estado",
                estado is Estado.ResultadoListo
            )
            val resultadoListo = estado as Estado.ResultadoListo
            assertTrue(
                "El resultado debe ser NoUrl, fue: ${resultadoListo.resultado}",
                resultadoListo.resultado is ResultadoAnalisis.NoUrl
            )

            // ── Bug 1 contrato: NoUrl no persiste → idLocal es null ──
            // Red: esta linea no compila porque ResultadoListo aun no tiene idLocal.
            assertNull(
                "idLocal debe ser null cuando el QR no contenia URL (no hay persistencia)",
                resultadoListo.idLocal
            )

            // Y efectivamente no se inserto ningun escaneo en la DB.
            assertEquals(
                "No debe haber escaneos en la DB tras analizar payload no-URL",
                0,
                db.escaneoDao().todosLosIds().size
            )
        }
}
