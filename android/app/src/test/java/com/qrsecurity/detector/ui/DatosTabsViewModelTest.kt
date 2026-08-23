package com.qrsecurity.detector.ui

import androidx.lifecycle.viewModelScope
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.*
import com.qrsecurity.detector.datos.sync.FakeMediadorSincronizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests unitarios JVM de [DatosTabsViewModel] — arquitectura NowInAndroid.
 *
 * Estrategia (fix dispatcher):
 *  - `StandardTestDispatcher` para `setMain` → viewModelScope.launch
 *    despacha a Main (testDispatcher), advanceUntilIdle lo drena.
 *  - `Dispatchers.Unconfined` para `ioDispatcher` del repo →
 *    withContext inline, withTransaction commit sincrono.
 *  - `FakeMediadorSincronizacion` (no-op) → elimina WorkManager del path.
 *  - `drenarRoomYDispatcher()` → drena multiples hops:
 *    la continuation despues de db.withTransaction (Unconfined) vuelve
 *    a Main (testDispatcher) async en multiples rondas.
 *  - StateFlow WhileSubscribed(5_000): collectorJobs mantienen suscriptores
 *    activos para que los Flows emitan desde Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DatosTabsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: BaseDatosSeguridad
    private lateinit var repoEscaneos: RepositorioEscaneos
    private lateinit var repoUrls: RepositorioUrlsBloqueadas
    private lateinit var mediadorSync: FakeMediadorSincronizacion
    private lateinit var viewModel: DatosTabsViewModel
    private val collectorJobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        db = Room.inMemoryDatabaseBuilder(
            context,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val backend = ClienteBackend()
        repoEscaneos = RepositorioEscaneos(db, backend, json, Dispatchers.Unconfined)
        repoUrls = RepositorioUrlsBloqueadas(db, backend, json, Dispatchers.Unconfined)
        mediadorSync = FakeMediadorSincronizacion(context)
        viewModel = DatosTabsViewModel(repoEscaneos, mediadorSync)
    }

    @After
    fun tearDown() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()
        Dispatchers.resetMain()
        db.close()
    }

    /**
     * Helper: drena Room's executor + test dispatcher en multiple rondas.
     *
     * `db.withTransaction` corre en Room's real ThreadPoolExecutor (OS thread).
     * La continuation despues de withTransaction vuelve a Main (testDispatcher)
     * async. `Thread.sleep` da tiempo al OS thread para completar la tx y
     * encolar la continuation; `runCurrent` la drena.
     *
     * v10 fix (hang): se usa `runCurrent` y NO `advanceUntilIdle` — el VM
     * colecta Eagerly el ticker de medianoche (flujo INFINITO de delays);
     * `advanceUntilIdle` avanza el reloj virtual a traves de la cadena
     * infinita de delays y nunca termina (spin del scheduler confirmado por
     * thread dump). `runCurrent` solo ejecuta tareas VENCIDAS: el delay de
     * medianoche esta a horas de distancia virtual y nunca dispara.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.drenarRoomYDispatcher() {
        repeat(5) {
            Thread.sleep(50)
            runCurrent()
        }
        runCurrent()
    }

    /**
     * Suscribe los StateFlows del ViewModel para que el upstream Eagerly
     * mantenga Room emitiendo cambios reactivos.
     *
     * Audit fix M1: totalEscaneos/amenazas eliminados del VM (flows eager
     * sin consumidor en la UI) — sus subscriptores y tests tambien se
     * retiraron. F4.3-b: urlsBloqueadas eliminado del VM (badge por fila en
     * SQL) — sus tests viven a nivel repo.
     */
    private fun subscribirTodos() {
        collectorJobs += viewModel.viewModelScope.launch { viewModel.historialUiState.collect { } }
    }

    // ── Estado inicial — todos los StateFlows arrancan con initialValue ──

    @Test
    fun estadoInicial_historialPaging_vacio() = runTest(testDispatcher) {
        assertEquals(emptyList<FilaHistorial>(), viewModel.historialPaging.asSnapshot())
    }

    @Test
    fun estadoInicial_urlsBloqueadas_repoVacio() = runTest(testDispatcher) {
        // F4.3-b: el Flow de urls_bloqueadas salió del VM (badge por fila en
        // SQL) — el contrato reactivo del repo se testea aquí directamente.
        assertEquals(emptyList<UrlBloqueadaEntity>(), repoUrls.observarTodos().first())
    }

    /**
     * RC2 — hasta que Room emita los conteos reales, el estado expone los
     * contadores en null ("—" en la UI). Antes el valor inicial de
     * conteosTotales era ConteosHistorial(0,0,0,0): el estado emitía
     * totalTodos=0 ANTES del dato real y los chips destellaban "0 escaneos /
     * 0% seguros" en el arranque frío (derrotaba el fix V-1).
     *
     * Determinista: se inserta la fila ANTES de crear el VM de prueba y el
     * colector se lanza sin suspensions intermedias — la primera emisión es
     * el valor inicial (nulls) y la siguiente el dato real (1). Un
     * totalTodos=0 intermedio solo puede venir del valor inicial zeros
     * eliminado: con el código viejo la secuencia era [nulls, 0, 1].
     */
    @Test
    fun rc2_primerasEmisiones_contadoresNullHastaElDatoRealDeRoom() = runTest(testDispatcher) {
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = "esc-rc2",
                urlOriginal = "https://rc2.example.com",
                urlLimpia = "rc2.example.com",
                probabilidad = 0.5f,
                nivelAlerta = "SOSPECHOSO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = System.currentTimeMillis(),
                dirty = false,
                syncedAtMillis = System.currentTimeMillis()
            )
        )

        val vmPrueba = DatosTabsViewModel(repoEscaneos, mediadorSync)
        val estados = mutableListOf<HistorialUiState>()
        val job = launch { vmPrueba.historialUiState.collect { estados += it } }
        runCurrent()
        drenarRoomYDispatcher()
        job.cancel()

        assertTrue(
            "El estado inicial (pre-Room) debe tener contadores null. Fue: $estados",
            estados.first().totalTodos == null
        )
        assertTrue(
            "Con 1 escaneo en Room NO puede aparecer un totalTodos=0 intermedio " +
                "(flash de '0 escaneos' del RC2). Fue: $estados",
            estados.none { it.totalTodos == 0 }
        )
        assertEquals(
            "La última emisión debe ser el conteo real",
            1,
            estados.last().totalTodos
        )
    }

    // ── Emision reactiva desde Room ──

    @Test
    fun historialPaging_emiteTrasInsertConCabeceraDeGrupo() = runTest(testDispatcher) {
        subscribirTodos()
        val escaneo = EscaneoEntity(
            id = "esc-1",
            urlOriginal = "https://malware.example.com",
            urlLimpia = "malware.example.com",
            probabilidad = 0.9f,
            nivelAlerta = "MALICIOSO",
            delegado = "NNAPI",
            esMalicioso = true,
            creadoEnMillis = System.currentTimeMillis(),
            dirty = false,
            syncedAtMillis = System.currentTimeMillis()
        )
        db.escaneoDao().insertar(escaneo)
        drenarRoomYDispatcher()

        val snapshot = viewModel.historialPaging.asSnapshot()
        // v10: la primera fila del grupo lleva su cabecera ("Hoy" para un
        // escaneo de ahora) insertada por insertSeparators.
        assertEquals(2, snapshot.size)
        assertTrue(snapshot[0] is FilaHistorial.Cabecera)
        assertEquals("Hoy", (snapshot[0] as FilaHistorial.Cabecera).titulo)
        assertEquals("esc-1", (snapshot[1] as FilaHistorial.Entrada).escaneo.id)
    }

    /**
     * F4.3-b — el flag de bloqueo viaja en la fila (EXISTS en SQL, sin Set
     * derivado de la tabla completa de urls_bloqueadas) y respeta un DELETE
     * pendiente de desbloqueo (la fila sigue físicamente en la tabla pero
     * está lógicamente desbloqueada — mismo predicado que totalBloqueadas).
     */
    @Test
    fun filaHistorial_flagBloqueada_calculadaEnSqlYRespetaDeletePendiente() = runTest(testDispatcher) {
        subscribirTodos()
        val ahora = System.currentTimeMillis()
        db.escaneoDao().insertar(
            escaneoDePrueba("bloq-1", ahora).copy(
                urlOriginal = "https://evil.example.com",
                urlLimpia = "evil.example.com"
            )
        )
        db.urlBloqueadaDao().insertar(
            UrlBloqueadaEntity(
                id = "ub-1",
                url = "evil.example.com",
                razon = "Malicioso",
                creadoEnMillis = ahora,
                dirty = false,
                syncedAtMillis = ahora
            )
        )
        drenarRoomYDispatcher()

        val conBloqueo = viewModel.historialPaging.asSnapshot()
            .filterIsInstance<FilaHistorial.Entrada>()
            .first()
        assertTrue("URL bloqueada → Entrada.bloqueada=true (EXISTS en SQL)", conBloqueo.bloqueada)

        // Desbloqueo pendiente: DELETE en el outbox — la fila sigue en
        // urls_bloqueadas pero el flag debe pasar a false.
        db.pendingOpDao().insertar(
            PendingOpEntity(
                tabla = PendingOpEntity.TABLA_URLS_BLOQUEADAS,
                tipoOperacion = PendingOpEntity.OP_DELETE,
                idLocal = "ub-1",
                payloadJson = null,
                creadoEnMillis = ahora
            )
        )
        drenarRoomYDispatcher()

        val trasDesbloqueoPendiente = viewModel.historialPaging.asSnapshot()
            .filterIsInstance<FilaHistorial.Entrada>()
            .first()
        assertFalse(
            "DELETE pendiente de desbloqueo → Entrada.bloqueada=false (mismo predicado que el COUNT)",
            trasDesbloqueoPendiente.bloqueada
        )
    }

    @Test
    fun historialUiState_emiteInmediatoSinEsperarMedianoche() = runTest(testDispatcher) {
        subscribirTodos()
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = "esc-ui-1",
                urlOriginal = "https://ui.example.com",
                urlLimpia = "ui.example.com",
                probabilidad = 0.5f,
                nivelAlerta = "SOSPECHOSO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = System.currentTimeMillis(),
                dirty = false,
                syncedAtMillis = System.currentTimeMillis()
            )
        )
        // Drena Room + dispatcher SIN avanzar el tiempo virtual: el ticker de
        // medianoche tiene un delay de horas; si el combine depende de su
        // primer emit, historialUiState queda congelado en el valor inicial
        // (totalTodos=null) y el historial se pintaria vacio en la UI.
        repeat(5) {
            Thread.sleep(50)
            runCurrent()
        }
        runCurrent()

        val estado = viewModel.historialUiState.value
        assertEquals("historialUiState debe emitir totalTodos sin esperar a medianoche", 1, estado.totalTodos)
    }

    @Test
    fun urlsBloqueadas_repoEmiteTrasInsert() = runTest(testDispatcher) {
        // F4.3-b: el Flow salió del VM; el contrato reactivo del repo queda
        // cubierto aquí.
        val ahora = System.currentTimeMillis()
        db.urlBloqueadaDao().insertar(
            UrlBloqueadaEntity(
                id = "url-1",
                url = "evil.example.com",
                razon = "Malicioso (probabilidad 90%)",
                creadoEnMillis = ahora,
                dirty = false,
                syncedAtMillis = ahora
            )
        )
        drenarRoomYDispatcher()

        val emitidas = repoUrls.observarTodos().first()
        assertEquals("el repo debe emitir 1 tras insert", 1, emitidas.size)
        assertEquals("evil.example.com", emitidas[0].url)
    }

    @Test
    fun paginacion_aplicaFiltroDeBloqueadasYBusquedaEnSQLSinDistinguirMayusculas() = runTest(testDispatcher) {
        subscribirTodos()
        val ahora = System.currentTimeMillis()
        db.escaneoDao().insertar(escaneoDePrueba("evil-1", ahora).copy(
            urlOriginal = "https://evil.example.com/login",
            urlLimpia = "evil.example.com/login"
        ))
        db.escaneoDao().insertar(escaneoDePrueba("evil-2", ahora - 1_000).copy(
            urlOriginal = "https://other.example.com/login",
            urlLimpia = "other.example.com/login"
        ))
        db.escaneoDao().insertar(escaneoDePrueba("safe-1", ahora - 2_000).copy(
            urlOriginal = "https://evil.example.com/about",
            urlLimpia = "evil.example.com/about",
            nivelAlerta = "SEGURO"
        ))
        db.urlBloqueadaDao().insertar(
            UrlBloqueadaEntity(
                id = "url-1",
                url = "evil.example.com/login",
                razon = "Malicioso",
                creadoEnMillis = ahora,
                dirty = false,
                syncedAtMillis = ahora
            )
        )
        drenarRoomYDispatcher()

        // v10: el filtro (bloqueadas + búsqueda) vive en SQL — solo
        // materializa las filas que matchean. Equivalente del test
        // `filtrarHistorial` de la era en-memoria.
        viewModel.actualizarFiltroHistorial(FiltroHistorial.BLOQUEADAS)
        viewModel.actualizarBusquedaHistorial("EVIL")
        advanceTimeBy(301) // debounce de búsqueda (300ms)
        drenarRoomYDispatcher()

        val snapshot = viewModel.historialPaging.asSnapshot()
        val ids = snapshot.filterIsInstance<FilaHistorial.Entrada>().map { it.escaneo.id }
        assertEquals(listOf("evil-1"), ids)
    }

    @Test
    fun paginacion_filtroSegurasExcluyeSospechosas() = runTest(testDispatcher) {
        subscribirTodos()
        val ahora = System.currentTimeMillis()
        db.escaneoDao().insertar(escaneoDePrueba("seg-1", ahora).copy(nivelAlerta = "SEGURO"))
        db.escaneoDao().insertar(escaneoDePrueba("sos-1", ahora - 1_000).copy(nivelAlerta = "SOSPECHOSO"))
        drenarRoomYDispatcher()

        viewModel.actualizarFiltroHistorial(FiltroHistorial.SEGURAS)
        drenarRoomYDispatcher()

        val ids = viewModel.historialPaging.asSnapshot()
            .filterIsInstance<FilaHistorial.Entrada>().map { it.escaneo.id }
        assertEquals(listOf("seg-1"), ids)
    }

    @Test
    fun paginacion_dedupSoloMuestraLaUltimaVersionPorURL() = runTest(testDispatcher) {
        subscribirTodos()
        val ahora = System.currentTimeMillis()
        db.escaneoDao().insertar(escaneoDePrueba("v1", ahora - 10_000).copy(urlLimpia = "dup.example.com", urlOriginal = "https://dup.example.com"))
        db.escaneoDao().insertar(escaneoDePrueba("v2", ahora).copy(urlLimpia = "dup.example.com", urlOriginal = "https://dup.example.com"))
        drenarRoomYDispatcher()

        val ids = viewModel.historialPaging.asSnapshot()
            .filterIsInstance<FilaHistorial.Entrada>().map { it.escaneo.id }
        assertEquals("solo la versión más reciente de la URL aparece", listOf("v2"), ids)
    }

    @Test
    fun claveGrupoHistorial_fechasFuturasVanAlGrupoHoy() {
        // Fechas-futuras fix: un creadoEnMillis futuro (reloj del device
        // atrasado contra el servidor) daba diasDeDiferencia negativo y la
        // fila caia fuera de los 3 grupos — sumaba en totalTodos pero nunca
        // se pintaba.
        val ahora = System.currentTimeMillis()
        val enUnaHora = ahora + 3_600_000L
        val haceTresDias = ahora - 3L * 24 * 3_600_000L

        assertEquals("Hoy", claveGrupoHistorial(enUnaHora, ahora))
        assertEquals(formatoFechaCorta(haceTresDias), claveGrupoHistorial(haceTresDias, ahora))
    }

    @Test
    fun claveGrupoHistorial_anteayerYDiasAnterioresConFechaConcreta() {
        // Auditoría UI 2: jerarquía temporal útil — Hoy/Ayer/Anteayer relativos
        // y a partir de ahí un grupo por día calendario titulado con la fecha
        // concreta, ordenado del más reciente al más antiguo.
        val ahora = System.currentTimeMillis()
        val hace2Dias = ahora - 2L * 24 * 3_600_000L
        val hace3Dias = ahora - 3L * 24 * 3_600_000L
        val hace5Dias = ahora - 5L * 24 * 3_600_000L

        assertEquals("Hoy", claveGrupoHistorial(ahora, ahora))
        assertEquals("Ayer", claveGrupoHistorial(ahora - 24 * 3_600_000L, ahora))
        assertEquals("Anteayer", claveGrupoHistorial(hace2Dias, ahora))
        assertEquals(formatoFechaCorta(hace3Dias), claveGrupoHistorial(hace3Dias, ahora))
        assertEquals(formatoFechaCorta(hace5Dias), claveGrupoHistorial(hace5Dias, ahora))
    }

    @Test
    fun paginacion_insertaCabeceraAlCambiarDeGrupoTemporal() = runTest(testDispatcher) {
        subscribirTodos()
        val ahora = System.currentTimeMillis()
        val haceTresDias = ahora - 3L * 24 * 3_600_000L
        db.escaneoDao().insertar(escaneoDePrueba("nuevo", ahora))
        db.escaneoDao().insertar(escaneoDePrueba("viejo", haceTresDias))
        drenarRoomYDispatcher()

        val snapshot = viewModel.historialPaging.asSnapshot()
        val titulos = snapshot.filterIsInstance<FilaHistorial.Cabecera>().map { it.titulo }
        // DESC: "Hoy" (con su fila) primero, luego la cabecera de la fecha
        // concreta con su fila — un header por cambio de grupo.
        assertEquals(listOf("Hoy", formatoFechaCorta(haceTresDias)), titulos)
        assertEquals(4, snapshot.size) // 2 cabeceras + 2 filas
    }

    /** Entidad mínima para tests de agrupación — solo id y fecha varían. */
    private fun escaneoDePrueba(id: String, creadoEnMillis: Long) = EscaneoEntity(
        id = id,
        urlOriginal = "https://$id.example.com",
        urlLimpia = "$id.example.com",
        probabilidad = 0.5f,
        nivelAlerta = "SOSPECHOSO",
        delegado = null,
        esMalicioso = false,
        creadoEnMillis = creadoEnMillis,
        dirty = false,
        syncedAtMillis = creadoEnMillis
    )
}
