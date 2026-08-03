package com.qrsecurity.detector.pipeline

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.local.sha256Hex
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test de la deduplicación persistente del [Pipeline] (Task 4) — el cache
 * maestro `urls_catalogo` cortocircuita el análisis cuando todas las URLs del
 * QR ya fueron escaneadas antes.
 *
 * Contrato cache + log (multi-URL):
 *  - Si [Pipeline.analizar] sin `forzar` y TODAS las URLs del QR ya están en
 *    `urls_catalogo` → emite [Pipeline.Estado.UrlDuplicada] y NO persiste un
 *    nuevo escaneo (el log `escaneos` no crece).
 *  - Si al menos una URL es nueva → inferencia/persistencia normal
 *    ([ResultadoListo]) y el cache se puebla con esa URL.
 *  - `forzar = true` salta el dedup: re-escanea de todas formas, persiste un
 *    nuevo escaneo y hace UPSERT del cache (veces+1).
 *
 * Estratégia: instancia real del [Pipeline] + [PipelineViewModel] sobre Room
 * in-memory (mismo patrón que [PipelineViewModelTest]). La dedup se ejerce a
 * través del VM porque lanza `analizar` en `viewModelScope` (Main =
 * testDispatcher), y el `withContext(Dispatchers.Default)` interno del
 * Pipeline corre en un OS thread real; el helper `drenar` (advanceUntilIdle +
 * yield) espera a que el estado se asiente. El motor de inferencia es el
 * placeholder aleatorio determinista (no carga TFLite).
 *
 * Seed del catalog: en vez de adivinar la `urlLimpia` que produce
 * [com.qrsecurity.detector.ml.Preprocesador.limpiarUrl], hacemos un primer
 * `analizar(forzar=true)` para poblar el catálogo con la urlLimpia exacta, lo
 * leemos del repo, y el segundo `analizar` (sin forzar) contra esa misma URL
 * debe emitir `UrlDuplicada`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class PipelineDedupTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: BaseDatosSeguridad
    private lateinit var repo: RepositorioEscaneos
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
        repo = RepositorioEscaneos(db, backend, json, testDispatcher)
        val mediadorSync = MediadorSincronizacion(app)
        val pipeline = Pipeline(app, db, backend, json, repo, mediadorSync)
        vm = PipelineViewModel(pipeline, SavedStateHandle())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /** URL con path (limpiarUrl no la reduce a vacío) y host ASCII válido. */
    private val url = "https://www.dedup.example.com/path"

    /** Drena el OS thread del Pipeline (Dispatchers.Default) + el testDispatcher. */
    private suspend fun drenar(scope: TestScope) {
        repeat(8) {
            Thread.sleep(40)
            scope.advanceUntilIdle()
        }
        scope.advanceUntilIdle()
    }

    @Test
    fun `analizar emite UrlDuplicada cuando la url ya esta en el catalogo`() = runTest(testDispatcher) {
        // Given: poblar el catálogo con la urlLimpia real producida por el pipeline.
        // Hacemos un primer escaneo forzado → persiste + pobla el cache.
        vm.analizar(url, forzar = true)
        drenar(this)
        // Confirmamos que el cache quedó con esa URL leyendo el resultado persistido.
        val urlLimpiaPersistida = db.urlCatalogoDao().contar()
        assertTrue("el primer escaneo debe poblar el cache (count>=1)", urlLimpiaPersistida >= 1)
        val estadoInicial = vm.estado.value
        assertTrue("tras el primer escaneo: ResultadoListo, fue: $estadoInicial",
            estadoInicial is Pipeline.Estado.ResultadoListo)

        // Resetear el pipeline para un segundo escaneo de la misma URL.
        vm.reiniciar()
        advanceUntilIdle()

        // When: re-escanear la MISMA URL sin forzar.
        vm.analizar(url, forzar = false)
        drenar(this)

        // Then: emite UrlDuplicada (todas las URLs del QR ya están en el cache).
        val estadoFinal = vm.estado.value
        assertTrue(" debe emitir UrlDuplicada, fue: $estadoFinal",
            estadoFinal is Pipeline.Estado.UrlDuplicada)
        val duplicada = estadoFinal as Pipeline.Estado.UrlDuplicada
        // El resultado peor del QR se empaqueta para que el diálogo lo renderice.
        assertNotNull(duplicada.resultado)
    }

    @Test
    fun `UrlDuplicada no persiste un nuevo escaneo en el log append-only`() = runTest(testDispatcher) {
        // Given: un escaneo previo (puebla cache y log con 1 fila).
        vm.analizar(url, forzar = true)
        drenar(this)
        vm.reiniciar()
        advanceUntilIdle()
        val escaneosTrasPrimero = db.escaneoDao().todosLosIds().size
        assertEquals("un escaneo en el log tras el primero", 1, escaneosTrasPrimero)

        // When: re-escanear la misma URL (dedup → UrlDuplicada, no persiste).
        vm.analizar(url, forzar = false)
        drenar(this)

        // Then: el log NO creció (no se persistió un escaneo nuevo).
        val escaneosTrasDedup = db.escaneoDao().todosLosIds().size
        assertEquals(
            "UrlDuplicada no debe insertar un escaneo en el log",
            escaneosTrasPrimero,
            escaneosTrasDedup
        )
        // El cache tampoco creció en filas (es UPSERT sobre la misma urlLimpia).
        assertEquals("cache maestro sigue con 1 fila", 1, db.urlCatalogoDao().contar())
    }

    @Test
    fun `analizar con forzar=true ignora el cache y reescanea`() = runTest(testDispatcher) {
        // Given: la URL ya en el cache (tras un primer escaneo).
        vm.analizar(url, forzar = true)
        drenar(this)
        vm.reiniciar()
        advanceUntilIdle()

        // When: re-escanear forzando (salta el dedup).
        vm.analizar(url, forzar = true)
        drenar(this)

        // Then: ResultadoListo (no UrlDuplicada) y se persistió un 2º escaneo.
        val estado2 = vm.estado.value
        assertTrue("forzar debe producir ResultadoListo, no UrlDuplicada", estado2 is Pipeline.Estado.ResultadoListo)
        assertEquals("2 escaneos en el log (append-only)", 2, db.escaneoDao().todosLosIds().size)
    }

    @Test
    fun `url nueva en un QR multi-URL no dispara dedup (hay novedad)`() = runTest(testDispatcher) {
        // Given: cache con una URL (a.com) pre-poblada a mano via el DAO.
        val urlLimpiaA = PreprocessorLimpia("https://www.dedup-a.example.com/x")
        db.urlCatalogoDao().upsert(
            UrlCatalogoEntity(
                urlHash = sha256Hex(urlLimpiaA),
                urlLimpia = urlLimpiaA,
                ultimoNivelAlerta = "SEGURO",
                ultimaProbabilidad = 0.1f,
                ultimoEscaneoMillis = 1L,
                vecesEscaneada = 1
            )
        )

        // When: escanear un QR con DOS URLs: la ya-catalogada (a.com) + una NUEVA (b.com).
        // Como b.com NO está en el cache, hay novedad → NO dedup → ResultadoListo.
        val qrMulti = "https://www.dedup-a.example.com/x https://www.dedup-b.example.com/y"
        vm.analizar(qrMulti, forzar = false)
        drenar(this)

        // Then: NO es UrlDuplicada (al menos una URL nueva).
        val estado = vm.estado.value
        assertTrue(
            "QR con al menos una URL nueva debe inferir (no dedup), fue: $estado",
            estado is Pipeline.Estado.ResultadoListo
        )
    }

    @Test
    fun `QR multi-URL contodas las urls en cache dispara dedup`() = runTest(testDispatcher) {
        // Given: cache con DOS URLs pre-pobladas (a.com y b.com).
        val urlLimpiaA = PreprocessorLimpia("https://www.dedup-a.example.com/x")
        val urlLimpiaB = PreprocessorLimpia("https://www.dedup-b.example.com/y")
        db.urlCatalogoDao().upsert(
            UrlCatalogoEntity(sha256Hex(urlLimpiaA), urlLimpiaA, "SEGURO", 0.1f, 1L, 1)
        )
        db.urlCatalogoDao().upsert(
            UrlCatalogoEntity(sha256Hex(urlLimpiaB), urlLimpiaB, "MALICIOSO", 0.9f, 2L, 5)
        )

        // When: escanear un QR con ambas URLs (todas ya en cache).
        val qrMulti = "https://www.dedup-a.example.com/x https://www.dedup-b.example.com/y"
        vm.analizar(qrMulti, forzar = false)
        drenar(this)

        // Then: UrlDuplicada (todas las URLs del QR ya están en el cache).
        val estado = vm.estado.value
        assertTrue("QR con todas las URLs ya en cache debe emitir UrlDuplicada, fue: $estado",
            estado is Pipeline.Estado.UrlDuplicada)
        val dup = estado as Pipeline.Estado.UrlDuplicada
        // vecesEscaneadaMaxima = max(1, 5) = 5 (info para el diálogo).
        assertEquals(5, dup.vecesEscaneadaMaxima)
    }

    /** Pequeño wrapper para no importar el objeto Preprocesador con otros nombres. */
    private fun PreprocessorLimpia(url: String): String =
        com.qrsecurity.detector.ml.Preprocesador.limpiarUrl(url)
}
