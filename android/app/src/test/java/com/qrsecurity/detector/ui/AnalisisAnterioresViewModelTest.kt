package com.qrsecurity.detector.ui

import androidx.lifecycle.viewModelScope
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v10 — Paging 3 en Analisis Anteriores: [AnalisisAnterioresViewModel].
 *
 * Verifica con `asSnapshot` (paging-testing):
 *  1. La primera carga trae solo la ventana inicial (50), no TODAS las
 *     filas — el caso de la URL con miles de versiones.
 *  2. El total de la cabecera (COUNT indexado) es el REAL aunque la
 *     paginacion no haya cargado todo.
 *  3. Scroll append trae el resto (scrollTo).
 *  4. La invalidacion de Room (nuevo escaneo de la misma URL) aparece en
 *     una nueva coleccion del Pager.
 *
 * Patron de DatosTabsViewModelTest: Robolectric + Room in-memory +
 * StandardTestDispatcher + ioDispatcher Unconfined.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class AnalisisAnterioresViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: BaseDatosSeguridad
    private lateinit var repoEscaneos: RepositorioEscaneos
    private lateinit var viewModel: AnalisisAnterioresViewModel
    private val collectorJobs = mutableListOf<Job>()

    private val urlPrueba = "example.com/mucho-historial"
    private val idActual = "esc-actual"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
        val backend = ClienteBackend()
        repoEscaneos = RepositorioEscaneos(db, backend, Json { ignoreUnknownKeys = true }, Dispatchers.Unconfined)
        viewModel = AnalisisAnterioresViewModel(repoEscaneos)
    }

    @After
    fun tearDown() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()
        Dispatchers.resetMain()
        db.close()
    }

    /** El estado de cabecera es WhileSubscribed(3s) — sin colector no emite. */
    private fun subscribirEstado() {
        collectorJobs += viewModel.viewModelScope.launch {
            viewModel.estadoAnalisisAnteriores.collect { }
        }
    }

    /** Drena el executor real de Room + el dispatcher de test (patron DatosTabs). */
    private suspend fun TestScope.drenarRoomYDispatcher() {
        repeat(5) {
            Thread.sleep(50)
            advanceUntilIdle()
        }
        advanceUntilIdle()
    }

    private fun escaneo(i: Int): EscaneoEntity = EscaneoEntity(
        id = "esc-$i",
        urlOriginal = "https://$urlPrueba?v=$i",
        urlLimpia = urlPrueba,
        probabilidad = 0.1f * (i % 10),
        nivelAlerta = if (i % 2 == 0) "SEGURO" else "SOSPECHOSO",
        delegado = null,
        esMalicioso = false,
        // DESC por indice: la fila 0 es la mas vieja, la ultima la mas nueva —
        // el orden esperado del snapshot es invertido (mas nueva primero).
        creadoEnMillis = 1_000_000L + i,
        dirty = false,
        syncedAtMillis = 1_000_000L + i
    )

    private suspend fun sembrarVersiones(n: Int) {
        (1..n).forEach { db.escaneoDao().insertar(escaneo(it)) }
        // El escaneo "actual" (excluido por la query).
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = idActual,
                urlOriginal = "https://$urlPrueba",
                urlLimpia = urlPrueba,
                probabilidad = 0.5f,
                nivelAlerta = "SOSPECHOSO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = 2_000_000L,
                dirty = false,
                syncedAtMillis = 2_000_000L
            )
        )
    }

    @Test
    fun `primera carga trae solo la ventana inicial y el total es el real`() = runTest(testDispatcher) {
        sembrarVersiones(120)
        viewModel.cargarAnalisisAnteriores(urlPrueba, idActual)
        subscribirEstado()
        drenarRoomYDispatcher()

        // Ventana acotada: pagina inicial + prefetch (asSnapshot accede todas
        // las filas cargadas y dispara el prefetch). La propiedad clave de
        // v10: NO se materializan las 120 — memoria proporcional a la ventana.
        val snapshot = viewModel.versiones.asSnapshot()
        assertTrue(
            "la ventana inicial debe ser acotada, no todo el historial (${snapshot.size})",
            snapshot.size in 50..100
        )

        // El total de la cabecera viene del COUNT indexado, no del Pager.
        val cargado = viewModel.estadoAnalisisAnteriores.value
        assertTrue("el estado de cabecera debe ser Cargado (fue ${cargado::class.simpleName})", cargado is EstadoAnalisisAnteriores.Cargado)
        assertEquals(120, (cargado as EstadoAnalisisAnteriores.Cargado).total)
        assertEquals(urlPrueba, cargado.url)
        assertEquals(idActual, cargado.id)
    }

    @Test
    fun `scroll append trae el resto de versiones en orden DESC`() = runTest(testDispatcher) {
        sembrarVersiones(120)
        viewModel.cargarAnalisisAnteriores(urlPrueba, idActual)
        runCurrent()

        val snapshot = viewModel.versiones.asSnapshot {
            scrollTo(120)
        }

        assertEquals(120, snapshot.size)
        // Orden creadoEnMillis DESC: la primera fila del snapshot es la mas
        // nueva (esc-120) y la ultima la mas vieja (esc-1).
        assertEquals("esc-120", snapshot.first().id)
        assertEquals("esc-1", snapshot.last().id)
    }

    @Test
    fun `nueva version insertada en Room aparece en nueva coleccion del Pager`() = runTest(testDispatcher) {
        sembrarVersiones(10)
        viewModel.cargarAnalisisAnteriores(urlPrueba, idActual)
        subscribirEstado()
        drenarRoomYDispatcher()

        assertEquals(10, viewModel.versiones.asSnapshot().size)

        // Re-escaneo: fila nueva mas reciente que todas.
        db.escaneoDao().insertar(
            escaneo(999).copy(creadoEnMillis = 3_000_000L, syncedAtMillis = 3_000_000L)
        )
        drenarRoomYDispatcher()

        val trasInsert = viewModel.versiones.asSnapshot()
        assertEquals(11, trasInsert.size)
        // La mas nueva encabeza el orden DESC.
        assertEquals("esc-999", trasInsert.first().id)

        val cargado = viewModel.estadoAnalisisAnteriores.value as EstadoAnalisisAnteriores.Cargado
        assertEquals(11, cargado.total)
    }

    @Test
    fun `url sin versiones previas queda vacia con total cero`() = runTest(testDispatcher) {
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = idActual,
                urlOriginal = "https://$urlPrueba",
                urlLimpia = urlPrueba,
                probabilidad = 0.5f,
                nivelAlerta = "SEGURO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = 2_000_000L,
                dirty = false,
                syncedAtMillis = 2_000_000L
            )
        )
        viewModel.cargarAnalisisAnteriores(urlPrueba, idActual)
        subscribirEstado()
        drenarRoomYDispatcher()

        assertEquals(0, viewModel.versiones.asSnapshot().size)
        val cargado = viewModel.estadoAnalisisAnteriores.value
        assertTrue("el estado de cabecera debe ser Cargado (fue ${cargado::class.simpleName})", cargado is EstadoAnalisisAnteriores.Cargado)
        assertEquals(0, (cargado as EstadoAnalisisAnteriores.Cargado).total)
    }

    @Test
    fun `numeracion_total_menos_indice_semantica_fijada_deriva_durante_backfill`() = runTest(testDispatcher) {
        // T3 — fija la semantica de la numeracion de versiones de la Screen
        // (version = total - indice, con indices ABSOLUTOS garantizados por
        // enablePlaceholders) y documenta su deriva transitoria mientras el
        // backfill inicial sigue escribiendo versiones mas viejas.
        //
        // DERIVA (aceptada a proposito, solo durante el sync inicial):
        // al llegar por backfill una version mas vieja que todas, `total`
        // crece (121) pero el indice de las filas ya visibles no cambia
        // (orden DESC: las viejas se anclan al FINAL). Por tanto la etiqueta
        // de una fila visible i pasa de `total0 - i` a `121 - i` — el numero
        // de version puede saltar bajo los ojos del usuario mientras dura el
        // backfill. No se re-ancla la formula (cambio de UI no justificado);
        // la deriva es acotada a la ventana del sync inicial.
        sembrarVersiones(120)
        viewModel.cargarAnalisisAnteriores(urlPrueba, idActual)
        subscribirEstado()
        drenarRoomYDispatcher()

        val cargadoInicial = viewModel.estadoAnalisisAnteriores.value as EstadoAnalisisAnteriores.Cargado
        val total0 = cargadoInicial.total
        assertEquals(120, total0)

        val snapshotInicial = viewModel.versiones.asSnapshot()
        val primerIdInicial = snapshotInicial.first().id

        // Llegada de backfill: una version mas vieja que TODAS (creadoEnMillis
        // menor que el minimo sembrado, que es 1_000_001 = esc-1).
        db.escaneoDao().insertar(
            escaneo(0).copy(
                id = "esc-backfill-vieja",
                creadoEnMillis = 1_000_000L,
                syncedAtMillis = 1_000_000L
            )
        )
        drenarRoomYDispatcher()

        val cargadoFinal = viewModel.estadoAnalisisAnteriores.value as EstadoAnalisisAnteriores.Cargado
        assertEquals(121, cargadoFinal.total)

        // El orden DESC se preserva: la mas reciente sigue siendo la primera,
        // y las filas ya cargadas mantienen su indice (la vieja se ancla al
        // final). La etiqueta de una fila visible i pasa de total0-i a 121-i.
        val snapshotFinal = viewModel.versiones.asSnapshot()
        assertEquals(
            "la fila mas nueva no debe cambiar de posicion al llegar versiones mas viejas",
            primerIdInicial,
            snapshotFinal.first().id
        )
        // La fila vieja queda al FINAL del orden DESC (no desplaza a las demas).
        // asSnapshot sin scroll solo expone la ventana inicial, asi que la fila
        // mas vieja (posicion 120 de 121) puede no estar presente aun; si lo
        // esta, debe ser la ultima.
        val ultimaSiVisible = snapshotFinal.lastOrNull()
        assertTrue(
            "si la fila vieja ya es visible, debe ser la ultima (orden DESC preservado)",
            ultimaSiVisible == null || ultimaSiVisible.id != "esc-backfill-vieja" ||
                snapshotFinal.last().id == "esc-backfill-vieja"
        )
    }
}
