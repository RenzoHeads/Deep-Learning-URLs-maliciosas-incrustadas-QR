package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.local.sha256Hex
import com.qrsecurity.detector.datos.repositorios.reconciliarUrlsCatalogo
import com.qrsecurity.detector.datos.repositorios.reconciliarUrlsCatalogoBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit R1 — paridad entre [reconciliarUrlsCatalogo] (single-URL loop) y
 * [reconciliarUrlsCatalogoBatch] (M4 fix: batch IN (:urls)). El batch
 * reemplaza un loop N+1 que sigue vivo en paths single-URL
 * ([com.qrsecurity.detector.datos.repositorios.RepositorioEscaneosEscritura.eliminarLocal])
 * — si las dos variantes divergen en semántica, el catálogo offline-first
 * se contamina silenciosamente (vecesEscaneada desincronizado, DELETEs
 * perdidos,ultimoNivelAlerta desactualizado) sin que el build lo note.
 *
 * Mapeo de modos equivalentes:
 *  - single(vecesEscaneadaOverride=null)      ≡ batch(preservarVecesEscaneada=false)
 *    → ambos usan el conteo de filas vivas (`restantes` / `conteo`)
 *  - single(vecesEscaneadaOverride=V)        ≡ batch(preservarVecesEscaneada=true)
 *    → ambos usan el valor dado (single) / el `vecesEscaneada` existente (batch)
 *
 * Cada escenario corre single sobre una DB y batch sobre otra (estado
 * aislado), y afirma que el snapshot final del catálogo es idéntico.
 * 4 escenarios cubren las 4 ramas semánticas del reconciler:
 *  1. URL nueva con escaneos vivos → UPSERT con conteo
 *  2. URL existente, preservar=true → UPSERT conservando vecesEscaneada
 *  3. URL existente, recalcular  → UPSERT con conteo actual
 *  4. Todas las filas en DELETE-pendiente → DELETE entrada del catálogo
 *
 * Patrón de test copiado de [com.qrsecurity.detector.datos.repos.RegistrarEscaneoLocalTest]
 * (Robolectric + Room in-memory + StandardTestDispatcher + drenar executor).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReconciliarUrlsCatalogoBatchParidadTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dbs = mutableListOf<BaseDatosSeguridad>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dbs.forEach { it.close() }
        dbs.clear()
    }

    // ── Helpers ──

    private fun nuevaDb(): BaseDatosSeguridad {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, BaseDatosSeguridad::class.java)
            .allowMainThreadQueries()
            .build()
        dbs.add(db)
        return db
    }

    /**
     * Drena el executor real de Room + el testDispatcher en varias rondas.
     * `withTransaction` corre en el ThreadPoolExecutor de Room (OS thread);
     * la continuation vuelve al testDispatcher async. `Thread.sleep` da
     * tiempo al OS thread para completar la tx y encolar la continuation;
     * `advanceUntilIdle` la drena. Copiado de `DatosTabsViewModelTest`.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.drenar() {
        repeat(5) {
            Thread.sleep(50)
            advanceUntilIdle()
        }
        advanceUntilIdle()
    }

    private fun escaneo(
        id: String,
        url: String,
        nivel: String,
        prob: Float,
        millis: Long,
        dirty: Boolean = false
    ): EscaneoEntity = EscaneoEntity(
        id = id,
        urlOriginal = "https://$url",
        urlLimpia = url,
        probabilidad = prob,
        nivelAlerta = nivel,
        delegado = null,
        esMalicioso = nivel == "MALICIOSO",
        creadoEnMillis = millis,
        dirty = dirty,
        syncedAtMillis = millis
    )

    /** op DELETE pendiente (no fallido) que excluye el escaneo `idLocal` del conteo */
    private fun opDeletePendiente(escaneoId: String, millis: Long): PendingOpEntity =
        PendingOpEntity(
            tabla = PendingOpEntity.TABLA_ESCANEOS,
            tipoOperacion = PendingOpEntity.OP_DELETE,
            idLocal = escaneoId,
            payloadJson = null,
            creadoEnMillis = millis
        )

    private suspend fun runSingle(
        db: BaseDatosSeguridad,
        url: String,
        override: Int? = null
    ) {
        db.withTransaction { db.reconciliarUrlsCatalogo(url, override) }
    }

    private suspend fun runBatch(
        db: BaseDatosSeguridad,
        urls: List<String>,
        preservar: Boolean = true
    ) {
        db.withTransaction { db.reconciliarUrlsCatalogoBatch(urls, preservar) }
    }

    private suspend fun snapshot(db: BaseDatosSeguridad, url: String): UrlCatalogoEntity? =
        db.urlCatalogoDao().buscarPorHash(sha256Hex(url))

    // ── Escenario 1 — URL nueva con escaneos vivos ──

    @Test
    fun paridad_url_nueva_single_null_vs_batch_preservar_false_usan_conteo_vivo() =
        runTest(testDispatcher) {
            val dbSingle = nuevaDb()
            val dbBatch = nuevaDb()
            val url = "evil.example.com"

            for (db in listOf(dbSingle, dbBatch)) {
                db.escaneoDao().insertar(escaneo("s1", url, "SOSPECHOSO", 0.4f, 1000L))
                db.escaneoDao().insertar(escaneo("s2", url, "MALICIOSO", 0.9f, 2000L))
                db.escaneoDao().insertar(escaneo("s3", url, "SEGURO", 0.05f, 1500L))
            }
            drenar()

            runSingle(dbSingle, url, override = null)
            runBatch(dbBatch, listOf(url), preservar = false)
            drenar()

            val s = snapshot(dbSingle, url)
            val b = snapshot(dbBatch, url)

            assertNotNull(s)
            assertEquals(s, b)
            // Ambos usan conteo vivo = 3, último escaneo es s2 (millis 2000)
            assertEquals(3, s!!.vecesEscaneada)
            assertEquals("MALICIOSO", s.ultimoNivelAlerta)
            assertEquals(0.9f, s.ultimaProbabilidad, 0.0001f)
            assertEquals(2000L, s.ultimoEscaneoMillis)
        }

    // ── Escenario 2 — URL existente, preservar vecesEscaneada ──

    @Test
    fun paridad_preservar_true_single_override_existente_vs_batch_preservar_true() =
        runTest(testDispatcher) {
            val dbSingle = nuevaDb()
            val dbBatch = nuevaDb()
            val url = "evil.example.com"
            val hash = sha256Hex(url)

            val seed = UrlCatalogoEntity(
                urlHash = hash,
                urlLimpia = url,
                ultimoNivelAlerta = "SEGURO",
                ultimaProbabilidad = 0.01f,
                ultimoEscaneoMillis = 500L,
                vecesEscaneada = 15
            )
            for (db in listOf(dbSingle, dbBatch)) {
                db.urlCatalogoDao().upsert(seed)
                db.escaneoDao().insertar(escaneo("s1", url, "SOSPECHOSO", 0.4f, 1000L))
                db.escaneoDao().insertar(escaneo("s2", url, "MALICIOSO", 0.9f, 2000L))
                db.escaneoDao().insertar(escaneo("s3", url, "SEGURO", 0.05f, 1500L))
            }
            drenar()

            // Single: pasar override = 15 (preserva el contador histórico)
            runSingle(dbSingle, url, override = 15)
            // Batch: preservarVecesEscaneada=true lee el existente (15)
            runBatch(dbBatch, listOf(url), preservar = true)
            drenar()

            val s = snapshot(dbSingle, url)
            val b = snapshot(dbBatch, url)

            assertEquals(s, b)
            // Ambos preservan 15; últimos campos recalculados desde s2
            assertEquals(15, s!!.vecesEscaneada)
            assertEquals("MALICIOSO", s.ultimoNivelAlerta)
            assertEquals(0.9f, s.ultimaProbabilidad, 0.0001f)
            assertEquals(2000L, s.ultimoEscaneoMillis)
        }

    // ── Escenario 3 — URL existente, recalcular desde conteo vivo ──

    @Test
    fun paridad_preservar_false_single_null_recalculan_desde_conteo_actual() =
        runTest(testDispatcher) {
            val dbSingle = nuevaDb()
            val dbBatch = nuevaDb()
            val url = "evil.example.com"
            val hash = sha256Hex(url)

            // Catálogo dice vecesEscaneada=15 (viejo); después del sync sobreviven 2
            val seed = UrlCatalogoEntity(
                urlHash = hash,
                urlLimpia = url,
                ultimoNivelAlerta = "SEGURO",
                ultimaProbabilidad = 0.01f,
                ultimoEscaneoMillis = 500L,
                vecesEscaneada = 15
            )
            for (db in listOf(dbSingle, dbBatch)) {
                db.urlCatalogoDao().upsert(seed)
                db.escaneoDao().insertar(escaneo("s1", url, "SOSPECHOSO", 0.4f, 1000L))
                db.escaneoDao().insertar(escaneo("s2", url, "MALICIOSO", 0.9f, 2000L))
            }
            drenar()

            runSingle(dbSingle, url, override = null)
            runBatch(dbBatch, listOf(url), preservar = false)
            drenar()

            val s = snapshot(dbSingle, url)
            val b = snapshot(dbBatch, url)

            assertEquals(s, b)
            // Ambos recalculan desde conteo vivo = 2 (semántica limpiarHuerfanos)
            assertEquals(2, s!!.vecesEscaneada)
            assertEquals("MALICIOSO", s.ultimoNivelAlerta)
            assertEquals(0.9f, s.ultimaProbabilidad, 0.0001f)
            assertEquals(2000L, s.ultimoEscaneoMillis)
        }

    // ── Escenario 4 — todas las filas en DELETE pendiente → DELETE entrada ──

    @Test
    fun `paridad filas en DELETE pendiente eliminan entrada existente`() =
        runTest(testDispatcher) {
            val dbSingle = nuevaDb()
            val dbBatch = nuevaDb()
            val url = "evil.example.com"
            val hash = sha256Hex(url)

            val seed = UrlCatalogoEntity(
                urlHash = hash,
                urlLimpia = url,
                ultimoNivelAlerta = "MALICIOSO",
                ultimaProbabilidad = 0.9f,
                ultimoEscaneoMillis = 1000L,
                vecesEscaneada = 5
            )
            for (db in listOf(dbSingle, dbBatch)) {
                db.urlCatalogoDao().upsert(seed)
                // El único escaneo existente; tiene un DELETE pendiente en outbox
                db.escaneoDao().insertar(escaneo("s1", url, "MALICIOSO", 0.9f, 1000L))
                db.pendingOpDao().insertar(opDeletePendiente("s1", 1500L))
            }
            drenar()

            runSingle(dbSingle, url, override = null)
            runBatch(dbBatch, listOf(url), preservar = false)
            drenar()

            // contarPorUrlLimpia == 0 → ambos eliminan la entrada del catálogo
            assertNull(snapshot(dbSingle, url))
            assertNull(snapshot(dbBatch, url))
        }
}
