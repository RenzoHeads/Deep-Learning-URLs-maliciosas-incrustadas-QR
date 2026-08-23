package com.qrsecurity.detector.datos.sync

import androidx.work.ListenableWorker
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.repositorios.BackfillDelta
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backfill DESC (v10) — orquestación end-to-end del [SyncWorker].
 *
 *  1. Primer pull de usuario nuevo: pide `orden=desc`, fija el cursor
 *     incremental al timestamp mas nuevo visto y marca COMPLETADO al
 *     terminar → initial_sync_completed=true.
 *  2. Backfill parcial (pagina llena × presupuesto): masPorSincronizar →
 *     initial_sync_completed queda false y el cursor backfill persiste
 *     "ts|id" para continuar.
 *  3. Corrida siguiente: delta incremental ASC + continuacion del backfill
 *     → COMPLETADO → initial_sync_completed=true.
 *  4. 422 en el backfill resetea AMBOS cursores y devuelve retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class SyncWorkerPullBackfillTest {

    private val fixture = FixtureSyncWorker()

    @Before
    fun setUp() = fixture.iniciar()

    @After
    fun tearDown() = fixture.cerrar()

    private fun respuestaEscaneos(
        n: Int,
        tsBase: Long,
        pasoMillis: Long = 3_600_000L
    ): MockResponse {
        // updated_at determinista y descendente dentro de la pagina.
        val cuerpoDesc = (0 until n).joinToString(",", "[", "]") { i ->
            val ts = isoDescendente(tsBase, i, pasoMillis)
            """
            {
                "id": "esc-$tsBase-$i",
                "url_original": "https://example.com/p$i",
                "url_limpia": "example.com/p$i",
                "probabilidad": 0.5,
                "nivel_alerta": "SEGURO",
                "es_malicioso": false,
                "creado_en": "2026-08-01T00:00:00Z",
                "updated_at": "$ts"
            }
            """.trimIndent()
        }
        return MockResponse().setBody(cuerpoDesc).setHeader("Content-Type", "application/json")
    }

    /** ISO 8601 fijo y descendente por indice — determinista para cursores. */
    private fun isoDescendente(tsBase: Long, i: Int, pasoMillis: Long): String {
        val t = tsBase - i * pasoMillis
        val seg = t / 1000
        val s = java.time.Instant.ofEpochSecond(seg).toString()
        return s
    }

    /**
     * T1 — respuesta ASCENDENTE (primera fila = la mas VIEJA): simula un
     * backend LEGACY que ignora `orden=desc`. El cliente debe detectarlo y
     * reintentar como transitorio sin persistir cursores falsos.
     */
    private fun respuestaEscaneosAscendente(n: Int, tsBase: Long): MockResponse {
        val cuerpoAsc = (0 until n).joinToString(",", "[", "]") { i ->
            // i=0 => tsBase - 0h (mas viejo); i=n-1 => tsBase - (n-1)h (mas nuevo).
            val ts = isoDescendente(tsBase, (n - 1 - i), 3_600_000L)
            """
            {
                "id": "esc-asc-$tsBase-$i",
                "url_original": "https://example.com/p$i",
                "url_limpia": "example.com/p$i",
                "probabilidad": 0.5,
                "nivel_alerta": "SEGURO",
                "es_malicioso": false,
                "creado_en": "2026-08-01T00:00:00Z",
                "updated_at": "$ts"
            }
            """.trimIndent()
        }
        return MockResponse().setBody(cuerpoAsc).setHeader("Content-Type", "application/json")
    }

    private fun respuestaVacia(): MockResponse =
        MockResponse().setBody("[]").setHeader("Content-Type", "application/json")

    private fun prefsInitialSyncCompleted(): Boolean = fixture.appContext
        .getSharedPreferences(SyncWorker.PREFS_SYNC, android.content.Context.MODE_PRIVATE)
        .getBoolean(SyncWorker.KEY_INITIAL_SYNC_COMPLETED, false)

    @Test
    fun `primer pull hace backfill DESC, fija cursor incremental y completa`() = runTest(fixture.testDispatcher) {
        fixture.escribirPrefsSync(initialSyncCompleted = false, ultimoSyncMs = 0L)
        val tsBase = java.time.Instant.parse("2026-08-20T12:00:00Z").toEpochMilli()

        // Request 1: urls_bloqueadas backfill (vacio).
        fixture.server.enqueue(respuestaVacia())
        // Request 2: escaneos backfill — pagina corta (2 < 200) = fin.
        fixture.server.enqueue(respuestaEscaneos(2, tsBase))

        val resultado = fixture.construirWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), resultado)
        assertTrue(
            "backfill completo → initial_sync_completed=true",
            prefsInitialSyncCompleted()
        )

        val estado = fixture.db.syncStateDao().obtener(PendingOpEntity.TABLA_ESCANEOS)
        assertNotNull(estado)
        assertEquals(BackfillDelta.COMPLETADO, estado?.ultimoCursorBackfill)
        // Cursor incremental = "ts|id" de la PRIMERA fila (la mas nueva).
        assertEquals(
            "2026-08-20T12:00:00Z|esc-$tsBase-0",
            estado?.ultimoCursorModificacion
        )

        // Requests: ambos con orden=desc y SIN cursor_id (arranque).
        val reqUrls = fixture.server.takeRequest()
        assertTrue(reqUrls.path!!.contains("/urls-bloqueadas"))
        assertTrue("el primer pull pide orden=desc", reqUrls.path!!.contains("orden=desc"))
        assertFalse(reqUrls.path!!.contains("cursor_id="))
        val reqEscaneos = fixture.server.takeRequest()
        assertTrue(reqEscaneos.path!!.contains("/escaneos"))
        assertTrue(reqEscaneos.path!!.contains("orden=desc"))
        assertFalse(reqEscaneos.path!!.contains("cursor_id="))
    }

    @Test
    fun `backfill parcial deja initial_sync false y cursor ts-pipe-id persistido`() = runTest(fixture.testDispatcher) {
        fixture.escribirPrefsSync(initialSyncCompleted = false, ultimoSyncMs = 0L)
        val tsBase = java.time.Instant.parse("2026-08-20T12:00:00Z").toEpochMilli()

        fixture.server.enqueue(respuestaVacia()) // urls
        // 5 paginas LLENAS (200 c/u) — agota el presupuesto del worker-run.
        repeat(5) { pagina ->
            // Cada pagina retrocede 200h respecto de la anterior para que
            // la ultima fila sea siempre la mas vieja vista.
            fixture.server.enqueue(respuestaEscaneos(200, tsBase - pagina * 200 * 3_600_000L))
        }

        val resultado = fixture.construirWorker().doWork()

        // Sin error, pero sin completar: masPorSincronizar NO es Result.retry.
        assertEquals(ListenableWorker.Result.success(), resultado)
        assertFalse(
            "backfill parcial → initial_sync_completed sigue false",
            prefsInitialSyncCompleted()
        )

        val estado = fixture.db.syncStateDao().obtener(PendingOpEntity.TABLA_ESCANEOS)
        assertNotNull(estado)
        assertFalse(
            "el cursor backfill NO es el centinela — quedo pagina pendiente",
            estado?.ultimoCursorBackfill == BackfillDelta.COMPLETADO
        )
        assertTrue(
            "cursor backfill persistido como ts|id",
            estado?.ultimoCursorBackfill.orEmpty().contains('|')
        )
        // El cursor incremental quedo fijado desde la PRIMERA pagina (run 1).
        assertEquals(
            "2026-08-20T12:00:00Z|esc-$tsBase-0",
            estado?.ultimoCursorModificacion
        )
    }

    @Test
    fun `corrida siguiente continua backfill y completa`() = runTest(fixture.testDispatcher) {
        val tsBase = java.time.Instant.parse("2026-08-20T12:00:00Z").toEpochMilli()

        // ── Run 1: parcial (2 paginas llenas de 2 — presupuesto maxPaginas=... no;
        // usar presupuesto real: 5 paginas de 200) ──
        fixture.escribirPrefsSync(initialSyncCompleted = false, ultimoSyncMs = 0L)
        fixture.server.enqueue(respuestaVacia()) // urls
        repeat(5) { pagina ->
            fixture.server.enqueue(respuestaEscaneos(200, tsBase - pagina * 200 * 3_600_000L))
        }
        fixture.construirWorker().doWork()
        val trasRun1 = fixture.db.syncStateDao().obtener(PendingOpEntity.TABLA_ESCANEOS)
        val cursorBackfillRun1 = trasRun1?.ultimoCursorBackfill
        assertTrue(cursorBackfillRun1.orEmpty().contains('|'))
        fixture.server.takeRequest() // urls
        repeat(5) { fixture.server.takeRequest() } // escaneos backfill

        // ── Run 2: resetea la ventana de syncReciente para que no OMITA ──
        fixture.escribirPrefsSync(initialSyncCompleted = false, ultimoSyncMs = 0L)
        // Request 1: escaneos delta incremental ASC (cursor de run 1) → vacio.
        fixture.server.enqueue(respuestaVacia())
        // Request 2: escaneos backfill DESC con cursor_id → pagina corta = fin.
        fixture.server.enqueue(respuestaEscaneos(1, tsBase - 10_000 * 3_600_000L))

        val resultado2 = fixture.construirWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), resultado2)
        assertTrue(
            "backfill completado en run 2 → initial_sync_completed=true",
            prefsInitialSyncCompleted()
        )
        assertEquals(
            BackfillDelta.COMPLETADO,
            fixture.db.syncStateDao().obtener(PendingOpEntity.TABLA_ESCANEOS)?.ultimoCursorBackfill
        )

        // La peticion incremental fue ASC (sin orden=desc).
        val reqIncremental = fixture.server.takeRequest()
        assertTrue(reqIncremental.path!!.contains("/escaneos"))
        assertFalse(
            "el delta incremental NO lleva orden=desc",
            reqIncremental.path!!.contains("orden=desc")
        )
        assertTrue(
            "el delta incremental lleva el cursor_id del incremental",
            reqIncremental.path!!.contains("cursor_id=esc-$tsBase-0")
        )
        // La peticion de backfill lleva cursor_id de run 1 + orden=desc.
        val reqBackfill = fixture.server.takeRequest()
        assertTrue(reqBackfill.path!!.contains("orden=desc"))
        assertTrue(reqBackfill.path!!.contains("cursor_id="))
    }

    @Test
    fun `422 en backfill resetea ambos cursores y devuelve retry`() = runTest(fixture.testDispatcher) {
        fixture.escribirPrefsSync(initialSyncCompleted = false, ultimoSyncMs = 0L)

        fixture.server.enqueue(respuestaVacia()) // urls
        fixture.server.enqueue(
            MockResponse().setResponseCode(422).setBody("{\"detail\":\"cursor\"}")
        ) // escaneos backfill → 422

        val resultado = fixture.construirWorker().doWork()

        assertTrue(
            "422 es transitorio → retry",
            resultado == ListenableWorker.Result.retry()
        )

        // Si el backfill aplico una pagina antes del 422, resetCursor debe
        // haber nulado AMBOS cursores. En este caso el 422 llego en la
        // primera pagina: no hay fila de sync_state con cursores.
        val estado = fixture.db.syncStateDao().obtener(PendingOpEntity.TABLA_ESCANEOS)
        assertNull("sin cursor incremental tras 422", estado?.ultimoCursorModificacion)
        assertNull("sin cursor backfill tras 422", estado?.ultimoCursorBackfill)
        assertFalse(prefsInitialSyncCompleted())
    }

    @Test
    fun `backend ASC en primer pull reintenta como transitorio sin persistir cursores`() = runTest(fixture.testDispatcher) {
        // T1 — backend legacy que ignora `orden=desc`: la primera pagina del
        // backfill llega ASCENDENTE (primera fila = la mas vieja). El worker
        // debe abortar como transitorio (retry), NO fijar el cursor
        // incremental en la fila mas vieja (deltas futuros corruptos) y NO
        // dejar cursores persistidos.
        fixture.escribirPrefsSync(initialSyncCompleted = false, ultimoSyncMs = 0L)
        val tsBase = java.time.Instant.parse("2026-08-20T12:00:00Z").toEpochMilli()

        fixture.server.enqueue(respuestaVacia()) // urls_bloqueadas backfill (vacio = fin).
        // escaneos backfill → primera pagina ASCENDENTE (2 filas).
        fixture.server.enqueue(respuestaEscaneosAscendente(2, tsBase))

        val resultado = fixture.construirWorker().doWork()

        // Fallido(codigo=null) = error NO HTTP = transitorio → Result.retry().
        assertTrue(
            "primera pagina ASC (backend legacy) debe ser transitorio → retry",
            resultado == ListenableWorker.Result.retry()
        )
        assertFalse("backfill abortado → initial_sync_completed false", prefsInitialSyncCompleted())

        // Sin cursores persistidos: no hay fila de sync_state para escaneos
        // (la validacion aborta ANTES de aplicar el primer batch), o si la
        // fila existe, ambos cursores quedan null.
        val estado = fixture.db.syncStateDao().obtener(PendingOpEntity.TABLA_ESCANEOS)
        assertNull("sin cursor incremental ante backend legacy", estado?.ultimoCursorModificacion)
        assertNull("sin cursor backfill ante backend legacy", estado?.ultimoCursorBackfill)

        // La peticion de backfill pidio orden=desc (el cliente la envia
        // siempre); el backend legacy la ignora devolviendo ASC.
        fixture.server.takeRequest() // urls
        val reqEscaneos = fixture.server.takeRequest()
        assertTrue(reqEscaneos.path!!.contains("/escaneos"))
        assertTrue(reqEscaneos.path!!.contains("orden=desc"))
        assertFalse(reqEscaneos.path!!.contains("cursor_id="))
    }
}
