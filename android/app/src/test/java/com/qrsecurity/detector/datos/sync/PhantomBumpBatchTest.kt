package com.qrsecurity.detector.datos.sync

import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import kotlinx.coroutines.test.runTest
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
 * R2 — Audit fix A3-a: phantom bump del batch push en
 * [SyncWorker.procesarPendingOps].
 *
 * El claim BATCH (`minPendingIds` + `markInProgressBatch` + `getByIds` en
 * una tx) incrementa `intentos` para los K ops reclamados atomically. Si
 * el worker muere tras procesar solo el primero, los K-1 restantes quedan
 * con `intentos` phantom-bumped sin haber sido procesados. El guard
 * `op.intentos > MAX_INTENTOS_OP` (10) los descarta tras >10 phantom bumps.
 *
 * Estos tests validan:
 *  1. El batch claim atomiza `intentos+1` para K ops oldest-first y los
 *     procesa secuencialmente fuera de la tx (happy path).
 *  2. Un op con `intentos` post-claim > MAX_INTENTOS_OP se marca fallida
 *     sin invocar el procesador (descarte permanente).
 *  3. Un op en el boundary (post-claim == MAX_INTENTOS_OP) aun se procesa
 *     normalmente — el guard es `>`, no `>=`, dejando margen para >=10
 *     phantom bumps consecutivos.
 *
 * Sin MockWebServer: los procesadores son lambdas inyectadas via el
 * parametro `repos: Map<String, suspend (PendingOpEntity) -> Boolean>`,
 * no requieren IO de red. El procesador de exito debe borrar el op
 * (`borrarPorId`) como hace el real en
 * [com.qrsecurity.detector.datos.repositorios.procesarPendingOp].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class PhantomBumpBatchTest {

    private val fixture = FixtureSyncWorker()

    @Before
    fun setUp() {
        fixture.iniciar()
    }

    @After
    fun tearDown() {
        fixture.cerrar()
    }

    /** Helper: inserta un op CREATE con idLocal y creadoEnMillis dados. */
    private suspend fun sembrarOp(idLocal: String, creadoEnMillis: Long): Long =
        fixture.db.pendingOpDao().insertar(
            PendingOpEntity(
                tabla = PendingOpEntity.TABLA_ESCANEOS,
                tipoOperacion = PendingOpEntity.OP_CREATE,
                idLocal = idLocal,
                payloadJson = "{}",
                creadoEnMillis = creadoEnMillis
            )
        )

    // ── Escenario 1 — batch claim atomiza intentos+1 para K ops oldest-first ──

    @Test
    fun `batch claim atomiza intentos mas 1 para K ops oldest first y los procesa`() =
        runTest(fixture.testDispatcher) {
            // Given: 4 ops encolados oldest-first (creadoEnMillis creciente),
            // todos con intentos=0 (sin phantom bumps previos).
            val ids = listOf(
                sembrarOp("op-1", creadoEnMillis = 100L),
                sembrarOp("op-2", creadoEnMillis = 200L),
                sembrarOp("op-3", creadoEnMillis = 300L),
                sembrarOp("op-4", creadoEnMillis = 400L)
            )

            val procesadas = mutableListOf<PendingOpEntity>()
            val procesador: suspend (PendingOpEntity) -> Boolean = { op ->
                procesadas.add(op)
                fixture.db.pendingOpDao().borrarPorId(op.id)
                true
            }
            val repos = mapOf(PendingOpEntity.TABLA_ESCANEOS to procesador)

            // When: procesarPendingOps reclama un batch de hasta
            // BATCH_SIZE_PUSH=8 (>= 4) ops en una sola tx y los procesa.
            val errorTransitorio = fixture.construirWorker()
                .procesarPendingOps(fixture.db.pendingOpDao(), repos, workerStartMs = 0L)

            // Then: no hay error transitorio (todos exitosos).
            assertFalse("happy path no senala error transitorio", errorTransitorio)
            // Los 4 ops fueron procesados.
            assertEquals(4, procesadas.size)
            // El orden oldest-first se preserva (getByIds ORDER BY creadoEnMillis ASC).
            assertEquals(
                listOf("op-1", "op-2", "op-3", "op-4"),
                procesadas.map { it.idLocal }
            )
            // El claim atomiza intentos+1: todos ven intentos=1 (empiezan en 0).
            assertTrue(
                "todos deben verse con intentos=1 (claim increments once)",
                procesadas.all { it.intentos == 1 }
            )
            // Todos borrados tras replay exitoso (como hace el procesador real).
            val restantes = fixture.db.pendingOpDao().getByIds(ids)
            assertTrue("todos los ops borrados tras procesar", restantes.isEmpty())
        }

    // ── Escenario 2 — intentos > MAX tras bump → fallida sin procesar ──

    @Test
    fun `op con intentos sobre MAX INTENTOS OP tras phantom bump se marca fallida sin procesar`() =
        runTest(fixture.testDispatcher) {
            // Given: 1 op con intentos=10 (10 phantom bumps previos).
            // Al claim del batch se bump a 11, que > MAX_INTENTOS_OP (10).
            val id = sembrarOp("op-agotado", creadoEnMillis = 100L)
            repeat(10) { fixture.db.pendingOpDao().markInProgressBatch(listOf(id)) }

            var invocaciones = 0
            val procesador: suspend (PendingOpEntity) -> Boolean = { _ ->
                invocaciones++
                true
            }
            val repos = mapOf(PendingOpEntity.TABLA_ESCANEOS to procesador)

            // When: el batch claim bumps intentos 10 -> 11; el guard
            // `op.intentos > MAX_INTENTOS_OP` se dispara.
            val errorTransitorio = fixture.construirWorker()
                .procesarPendingOps(fixture.db.pendingOpDao(), repos, workerStartMs = 0L)

            // Then: el procesador NO se invoca (el guard descarta antes).
            assertFalse(
                "descarte por agotamiento no debe senalar error transitorio",
                errorTransitorio
            )
            assertEquals(
                "el procesador no debe invocarse para op agotado",
                0,
                invocaciones
            )
            // El op se marca fallida (NO se borra: marcarFallida setea fallida=1).
            val opFinal = fixture.db.pendingOpDao().getById(id)
            assertNotNull(
                "el op debe seguir en la cola (marcarFallida no borra fisicamente)",
                opFinal
            )
            assertTrue("el op debe quedar marcado fallida", opFinal!!.fallida)
            assertEquals(
                "intentos tras phantom bumps + claim = 11 (10 previos + 1 del claim)",
                11,
                opFinal.intentos
            )
        }

    // ── Escenario 3 — boundary: intentos == MAX tras bump → aún procesado ──

    @Test
    fun `op en boundary intentos igual a MAX tras bump aun se procesa, guard es mayor estricto`() =
        runTest(fixture.testDispatcher) {
            // Given: 1 op con intentos=9 (9 phantom bumps previos).
            // Al claim del batch se bump a 10, que NO > MAX_INTENTOS_OP (10).
            val id = sembrarOp("op-limite", creadoEnMillis = 100L)
            repeat(9) { fixture.db.pendingOpDao().markInProgressBatch(listOf(id)) }

            val procesadas = mutableListOf<PendingOpEntity>()
            val procesador: suspend (PendingOpEntity) -> Boolean = { op ->
                procesadas.add(op)
                fixture.db.pendingOpDao().borrarPorId(op.id)
                true
            }
            val repos = mapOf(PendingOpEntity.TABLA_ESCANEOS to procesador)

            // When: el batch claim bumps intentos 9 -> 10; el guard
            // `10 > 10` es false (mayor estricto), el op se procesa.
            val errorTransitorio = fixture.construirWorker()
                .procesarPendingOps(fixture.db.pendingOpDao(), repos, workerStartMs = 0L)

            // Then: el op se procesa normalmente.
            assertFalse(
                "op en boundary no debe senalar error transitorio",
                errorTransitorio
            )
            assertEquals(
                "el op en boundary debe procesarse (guard > no >=)",
                1,
                procesadas.size
            )
            assertEquals(
                "intentos tras 9 phantom bumps + 1 claim = 10 (== MAX, no >)",
                10,
                procesadas[0].intentos
            )
            // Op borrado tras replay exitoso.
            assertNull(
                "op en boundary borrado tras procesar",
                fixture.db.pendingOpDao().getById(id)
            )
        }
}
