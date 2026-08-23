package com.qrsecurity.detector.datos.repos

import com.qrsecurity.detector.datos.repositorios.CursorDelta
import com.qrsecurity.detector.datos.repositorios.ResultadoSync
import com.qrsecurity.detector.datos.repositorios.fetchDeltas
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fila minimal del delta — solo id + updatedAt (lo unico que fetchDeltas toca). */
private data class FilaDelta(val id: String, val updatedAt: String?)

/**
 * S4 — TDD red phase.
 *
 * Bug: en [fetchDeltas], una pagina llena (size == limitePagina) cuya ultima
 * fila no trae `updatedAt` corta el loop con `break` SIN marcar
 * `masPorSincronizar`. El resultado es `ResultadoSync.Exitoso` con
 * `pullCompleto=true`, y SyncWorker ejecuta `limpiarHuerfanos` con una lista
 * PARCIAL de idsServidor — borrado masivo de filas locales dirty=0 que viven
 * en paginas nunca fetcheadas (perdida de datos).
 *
 * Fix: antes de ese break, `masPorSincronizar=true` para que `pullCompleto`
 * sea false y el worker NO corra orphan cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class FetchDeltasCursorCongeladoTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `pagina llena sin updatedAt en la ultima fila deja pullCompleto false`() =
        runTest(testDispatcher) {
            // Given: el backend devuelve SIEMPRE una pagina llena de 2 filas
            // cuya ultima fila no trae updatedAt (cursor no puede avanzar).
            val pagina = listOf(
                FilaDelta(id = "a", updatedAt = "2026-01-01T00:00:00Z"),
                FilaDelta(id = "b", updatedAt = null)
            )

            // When
            val resultado = fetchDeltas(
                ioDispatcher = testDispatcher,
                cursor = CursorDelta.EPOCH.aString(),
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchDelta = { _, _ -> pagina },
                applyBatch = { batch, _ -> batch.map { it.id } },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            // Then: el pull NO se reporta completo — sin eso el worker haria
            // limpiarHuerfanos con los ids de UNA sola pagina (parcial).
            assertTrue(resultado is ResultadoSync.Exitoso)
            val exitoso = resultado as ResultadoSync.Exitoso
            assertFalse(
                "pullCompleto debe ser false: pagina llena con cursor congelado NO es un pull completo",
                exitoso.pullCompleto
            )
            assertTrue(
                "masPorSincronizar debe ser true: quedan paginas sin fetchear",
                exitoso.masPorSincronizar
            )
        }

    @Test
    fun `pagina incompleta con ultima fila sin updatedAt sigue siendo pullCompleto true`() =
        runTest(testDispatcher) {
            // Given: batch INCOMPLETO (1 < limite 2) — el loop corta por
            // `delta.size < limitePagina` ANTES de necesitar cursor nuevo.
            val pagina = listOf(FilaDelta(id = "a", updatedAt = null))

            // When
            val resultado = fetchDeltas(
                ioDispatcher = testDispatcher,
                cursor = CursorDelta.EPOCH.aString(),
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchDelta = { _, _ -> pagina },
                applyBatch = { batch, _ -> batch.map { it.id } },
                extraerCursor = { fila ->
                    fila.updatedAt?.let { CursorDelta(it, fila.id) }
                },
                mensajeError = "error test"
            )

            // Then: batch incompleto = fin real del stream → pull completo.
            assertTrue(resultado is ResultadoSync.Exitoso)
            val exitoso = resultado as ResultadoSync.Exitoso
            assertTrue(exitoso.pullCompleto)
            assertFalse(exitoso.masPorSincronizar)
        }

    // ──────────────────────────────────────────────────────────────
    // RC3 — coalescing: TODAS las paginas de la corrida se aplican en UNA
    // sola invocacion de applyBatch (una tx Room por tabla/fase). Antes cada
    // pagina era su propia tx: ~20 invalidaciones por worker-run colapsaban
    // el PagingData del Historial/Analisis (parpadeo de lista vacia).
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `paginas multiples se aplican en UN solo batch coalescido`() =
        runTest(testDispatcher) {
            // 3 paginas: dos llenas + una corta (fin del stream).
            val paginas = listOf(
                listOf(
                    FilaDelta("a1", "2026-01-01T00:00:00Z"),
                    FilaDelta("a2", "2026-01-02T00:00:00Z")
                ),
                listOf(
                    FilaDelta("b1", "2026-01-03T00:00:00Z"),
                    FilaDelta("b2", "2026-01-04T00:00:00Z")
                ),
                listOf(FilaDelta("c1", "2026-01-05T00:00:00Z"))
            )
            var pagina = 0
            val batchesAplicados = mutableListOf<List<FilaDelta>>()

            val resultado = fetchDeltas(
                ioDispatcher = testDispatcher,
                cursor = CursorDelta.EPOCH.aString(),
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchDelta = { _, _ -> paginas[pagina++] },
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
            assertEquals(5, exitoso.filaSincronizadas)
            assertEquals(listOf("a1", "a2", "b1", "b2", "c1"), exitoso.idsServidor)
            assertEquals(
                "las 3 paginas deben aplicarse en UNA sola invocacion (una tx)",
                1,
                batchesAplicados.size
            )
            assertEquals(
                "el batch coalescido contiene las paginas concatenadas en orden",
                listOf("a1", "a2", "b1", "b2", "c1"),
                batchesAplicados.first().map { it.id }
            )
            assertTrue(exitoso.pullCompleto)
        }

    @Test
    fun `primera pagina vacia no invoca el applier`() =
        runTest(testDispatcher) {
            var batchesAplicados = 0

            val resultado = fetchDeltas(
                ioDispatcher = testDispatcher,
                cursor = CursorDelta.EPOCH.aString(),
                limitePagina = 2,
                maxPaginasPorRun = 5,
                fetchDelta = { _, _ -> emptyList<FilaDelta>() },
                applyBatch = { _, _ ->
                    batchesAplicados++
                    emptyList<String>()
                },
                extraerCursor = { null },
                mensajeError = "error test"
            )

            val exitoso = resultado as ResultadoSync.Exitoso
            assertEquals(0, exitoso.filaSincronizadas)
            assertTrue(exitoso.pullCompleto)
            assertEquals("sin filas no hay tx que aplicar", 0, batchesAplicados)
        }
}
