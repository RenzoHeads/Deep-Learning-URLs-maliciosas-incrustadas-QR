package com.qrsecurity.detector.datos.repos

import com.qrsecurity.detector.datos.repositorios.CursorDelta
import com.qrsecurity.detector.datos.repositorios.ResultadoSync
import com.qrsecurity.detector.datos.repositorios.fetchBackfill
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fila minimal del delta — solo id + updatedAt (lo unico que fetchBackfill toca). */
private data class FilaBackfill(val id: String, val updatedAt: String?)

/**
 * Backfill DESC (v10) — tests de [fetchBackfill].
 *
 *  1. Primera pagina (cursor null) se pide SIN cursor_id y las siguientes
 *     retroceden con keyset DESC hacia la fila mas vieja.
 *  2. Pagina corta = fin del historial → pullCompleto=true.
 *  3. Presupuesto de paginas agotado → masPorSincronizar=true.
 *  4. Guard de cursor congelado (espejo del S4 de fetchDeltas).
 *  5. RC3 (coalescing): TODAS las paginas de la corrida se aplican en UNA
 *     sola invocacion de applyBatch, con la lista concatenada en orden DESC
 *     (first = mas nueva, last = mas vieja) — una sola transaccion Room por
 *     tabla/fase, en vez de una por pagina (causa del parpadeo de listas).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class FetchBackfillTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `primera pagina sin cursor_id y siguientes retroceden con keyset DESC`() =
        runTest(testDispatcher) {
            // Historial DESC: newest → oldest (indexado por cursor_id).
            val paginas = mapOf(
                null to listOf(
                    FilaBackfill("c", "2026-03-01T00:00:00Z"),
                    FilaBackfill("b", "2026-02-01T00:00:00Z")
                ),
                "b" to listOf(
                    FilaBackfill("a", "2026-01-01T00:00:00Z")
                )
            )
            val peticiones = mutableListOf<Pair<String, String?>>()
            val batchesAplicados = mutableListOf<List<FilaBackfill>>()

            val resultado = fetchBackfill(
                ioDispatcher = testDispatcher,
                cursorBackfill = null,
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchPagina = { ts, id ->
                    peticiones.add(ts to id)
                    paginas[id]!!
                },
                applyBatch = { batch, _ ->
                    batchesAplicados += batch
                    batch.map { it.id }
                },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            // Peticiones: pagina 1 sin cursor_id; pagina 2 con el "ts|id" de
            // la fila mas VIEJA de la pagina 1.
            assertEquals(
                listOf(
                    CursorDelta.EPOCH_TS to null,
                    "2026-02-01T00:00:00Z" to "b"
                ),
                peticiones
            )
            // RC3: un UNICO batch con las dos paginas concatenadas en orden
            // DESC — first()=c (la mas nueva, semilla incremental),
            // last()=a (la mas vieja, cursor backfill).
            assertEquals(
                "todas las paginas deben aplicarse en UNA sola invocacion coalescida",
                1,
                batchesAplicados.size
            )
            assertEquals(
                listOf("c", "b", "a"),
                batchesAplicados.first().map { it.id }
            )
            assertTrue(resultado is ResultadoSync.Exitoso)
            val exitoso = resultado as ResultadoSync.Exitoso
            assertEquals(3, exitoso.filaSincronizadas)
            assertEquals(listOf("c", "b", "a"), exitoso.idsServidor)
            assertTrue("pagina corta final = backfill completo", exitoso.pullCompleto)
            assertFalse(exitoso.masPorSincronizar)
        }

    @Test
    fun `presupuesto de paginas agotado aplica un solo batch y deja masPorSincronizar true`() =
        runTest(testDispatcher) {
            // Backend devuelve SIEMPRE paginas llenas (historial enorme).
            val paginaLlena = listOf(
                FilaBackfill("x1", "2026-03-01T00:00:00Z"),
                FilaBackfill("x2", "2026-02-01T00:00:00Z")
            )
            val batchesAplicados = mutableListOf<List<FilaBackfill>>()

            val resultado = fetchBackfill(
                ioDispatcher = testDispatcher,
                cursorBackfill = null,
                limitePagina = 2,
                maxPaginasPorRun = 3,
                fetchPagina = { _, _ -> paginaLlena },
                applyBatch = { batch, _ ->
                    batchesAplicados += batch
                    batch.map { it.id }
                },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            val exitoso = resultado as ResultadoSync.Exitoso
            assertEquals(6, exitoso.filaSincronizadas)
            assertTrue(exitoso.masPorSincronizar)
            assertFalse(exitoso.pullCompleto)
            assertEquals(
                "agotar el presupuesto tambien debe coalescer en UNA invocacion",
                1,
                batchesAplicados.size
            )
            assertEquals(6, batchesAplicados.first().size)
        }

    @Test
    fun `pagina llena sin updatedAt en la ultima fila deja pullCompleto false`() =
        runTest(testDispatcher) {
            // Espejo del S4 de fetchDeltas: cursor congelado.
            val pagina = listOf(
                FilaBackfill("a", "2026-01-01T00:00:00Z"),
                FilaBackfill("b", null)
            )

            val resultado = fetchBackfill(
                ioDispatcher = testDispatcher,
                cursorBackfill = null,
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchPagina = { _, _ -> pagina },
                applyBatch = { batch, _ -> batch.map { it.id } },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            val exitoso = resultado as ResultadoSync.Exitoso
            assertFalse(
                "pullCompleto debe ser false: pagina llena con cursor congelado",
                exitoso.pullCompleto
            )
            assertTrue(exitoso.masPorSincronizar)
        }

    @Test
    fun `cuenta vacia devuelve exitoso sin filas ni aplicacion de batch`() =
        runTest(testDispatcher) {
            var batchesAplicados = 0

            val resultado = fetchBackfill<FilaBackfill>(
                ioDispatcher = testDispatcher,
                cursorBackfill = null,
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchPagina = { _, _ -> emptyList() },
                applyBatch = { _, _ ->
                    batchesAplicados++
                    emptyList()
                },
                extraerCursor = { null },
                mensajeError = "error test"
            )

            val exitoso = resultado as ResultadoSync.Exitoso
            assertEquals(0, exitoso.filaSincronizadas)
            assertTrue(exitoso.pullCompleto)
            assertFalse(exitoso.masPorSincronizar)
            assertEquals(
                "con la primera pagina vacia no debe invocarse el applier (sin tx)",
                0,
                batchesAplicados
            )
        }

    // ── T1 (v10 + review) — validacion runtime de que la primera pagina del
    //    backfill llega DESCENDENTE. Backend legacy que ignora `orden=desc`
    //    devuelve ASC: si el cliente lo tratara como DESC fijaria el cursor
    //    incremental a la fila mas VIEJA y los deltas futuros arrancarian mal.
    //    Regla: solo marca ASC estricto (first.ts estrictamente < last.ts);
    //    EMPATES pasan (los multi-INSERT del backend comparten updated_at en
    //    masa — ver backend/app/consulta_listado.py:103-107). ──

    @Test
    fun `primera pagina ASC estricta aborta como Fallido sin aplicar batch`() =
        runTest(testDispatcher) {
            // Pagina ascendente: la primera fila es la mas VIEJA, la ultima la
            // mas nueva — senal inequivoca de backend legacy (ignoro orden=desc).
            val paginaAsc = listOf(
                FilaBackfill("a", "2026-01-01T00:00:00Z"),
                FilaBackfill("b", "2026-03-01T00:00:00Z")
            )
            var batchInvocado = false

            val resultado = fetchBackfill(
                ioDispatcher = testDispatcher,
                cursorBackfill = null,
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchPagina = { _, _ -> paginaAsc },
                applyBatch = { _, _ ->
                    batchInvocado = true
                    emptyList()
                },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            assertTrue("debe abortar como Fallido ante backend legacy", resultado is ResultadoSync.Fallido)
            assertFalse(
                "ninguna fila debe aplicarse si la primera pagina no es descendente",
                batchInvocado
            )
        }

    @Test
    fun `primera pagina con empate total de updated_at NO marca legacy`() =
        runTest(testDispatcher) {
            // Empate completo de timestamps (first.ts == last.ts): los
            // multi-INSERT del backend comparten `now()` y una pagina DESC
            // plenamente valida puede tener first.ts == last.ts. NO debe
            // marcarse como legacy (falso positivo → retry infinito).
            val paginaEmpate = listOf(
                FilaBackfill("a", "2026-03-01T00:00:00Z"),
                FilaBackfill("b", "2026-03-01T00:00:00Z")
            )
            var batchesAplicados = 0

            val resultado = fetchBackfill(
                ioDispatcher = testDispatcher,
                cursorBackfill = null,
                limitePagina = 2,
                maxPaginasPorRun = 5,
                // Segunda pagina vacia (fin de historial) tras la primera.
                fetchPagina = { _, id -> if (id == null) paginaEmpate else emptyList() },
                applyBatch = { _, _ ->
                    batchesAplicados++
                    listOf("a", "b")
                },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            assertTrue("el empate no debe marcar legacy: Exitoso", resultado is ResultadoSync.Exitoso)
            assertTrue("la pagina (aunque empatada) debe aplicarse", batchesAplicados == 1)
            val exitoso = resultado as ResultadoSync.Exitoso
            assertEquals(2, exitoso.filaSincronizadas)
        }

    @Test
    fun `primera pagina de una sola fila NO valida direccion`() =
        runTest(testDispatcher) {
            // size < 2: no hay pares que comparar — flujo normal.
            var batchesAplicados = 0

            val resultado = fetchBackfill(
                ioDispatcher = testDispatcher,
                cursorBackfill = null,
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchPagina = { _, _ ->
                    listOf(FilaBackfill("a", "2026-01-01T00:00:00Z"))
                },
                applyBatch = { _, _ ->
                    batchesAplicados++
                    listOf("a")
                },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            assertTrue("pagina corta de 1 fila = Exitoso", resultado is ResultadoSync.Exitoso)
            assertTrue(batchesAplicados == 1)
            assertEquals(1, (resultado as ResultadoSync.Exitoso).filaSincronizadas)
        }

    @Test
    fun `updatedAt null en extremos NO valida direccion`() =
        runTest(testDispatcher) {
            // Si la primera o ultima fila no trae updatedAt, no hay ts para
            // comparar — skip de la validacion y flujo normal (el guard de
            // cursor congelado existente cubre el resto).
            var batchesAplicados = 0

            val resultado = fetchBackfill(
                ioDispatcher = testDispatcher,
                cursorBackfill = null,
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchPagina = { _, id ->
                    if (id == null) {
                        listOf(
                            FilaBackfill("a", null),
                            FilaBackfill("b", "2026-03-01T00:00:00Z")
                        )
                    } else {
                        emptyList()
                    }
                },
                applyBatch = { _, _ ->
                    batchesAplicados++
                    listOf("a", "b")
                },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            assertTrue("updatedAt null en extremo = skip validacion, Exitoso", resultado is ResultadoSync.Exitoso)
            assertTrue(batchesAplicados == 1)
            assertEquals(2, (resultado as ResultadoSync.Exitoso).filaSincronizadas)
        }

    @Test
    fun `updatedAt no parseable NO valida direccion ni crashea`() =
        runTest(testDispatcher) {
            // Timestamp corrupto en el extremo: el runCatching de la
            // validacion debe degradar a "no validable" (skip) sin lanzar.
            var batchesAplicados = 0

            val resultado = fetchBackfill(
                ioDispatcher = testDispatcher,
                cursorBackfill = null,
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchPagina = { _, id ->
                    if (id == null) {
                        listOf(
                            FilaBackfill("a", "no-es-una-fecha"),
                            FilaBackfill("b", "2026-03-01T00:00:00Z")
                        )
                    } else {
                        emptyList()
                    }
                },
                applyBatch = { _, _ ->
                    batchesAplicados++
                    listOf("a", "b")
                },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            assertTrue("ts no parseable = skip validacion, Exitoso (sin crash)", resultado is ResultadoSync.Exitoso)
            assertTrue(batchesAplicados == 1)
            assertEquals(2, (resultado as ResultadoSync.Exitoso).filaSincronizadas)
        }
}
