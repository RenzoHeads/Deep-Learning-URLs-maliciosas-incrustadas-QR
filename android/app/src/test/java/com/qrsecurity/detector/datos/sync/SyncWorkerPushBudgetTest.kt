package com.qrsecurity.detector.datos.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkerPushBudgetTest {

    @Test
    fun `push budget is not exhausted before boundary`() {
        assertFalse(
            debeCederPresupuestoPush(
                inicioMs = 1_000L,
                ahoraMs = 1_000L + SyncWorker.PRESUPUESTO_PUSH_MS - 1L,
                presupuestoMs = SyncWorker.PRESUPUESTO_PUSH_MS
            )
        )
    }

    @Test
    fun `push budget is exhausted at boundary`() {
        assertTrue(
            debeCederPresupuestoPush(
                inicioMs = 1_000L,
                ahoraMs = 1_000L + SyncWorker.PRESUPUESTO_PUSH_MS,
                presupuestoMs = SyncWorker.PRESUPUESTO_PUSH_MS
            )
        )
    }
}
