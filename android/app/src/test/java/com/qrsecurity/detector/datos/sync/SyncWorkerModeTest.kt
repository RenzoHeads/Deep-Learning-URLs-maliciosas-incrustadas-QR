package com.qrsecurity.detector.datos.sync

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerModeTest {

    @Test
    fun `pending ops after initial sync use push-only mode`() {
        assertEquals(
            SyncMode.SOLO_PUSH,
            decidirModoSync(
                hayPendingOps = true,
                initialSyncCompleted = true,
                syncReciente = false,
                pullReciente = true
            )
        )
    }

    @Test
    fun `pending ops during initial sync still run pull and push`() {
        assertEquals(
            SyncMode.PULL_Y_PUSH,
            decidirModoSync(
                hayPendingOps = true,
                initialSyncCompleted = false,
                syncReciente = true,
                pullReciente = true
            )
        )
    }

    @Test
    fun `recent sync without pending ops skips the worker`() {
        assertEquals(
            SyncMode.OMITIR,
            decidirModoSync(
                hayPendingOps = false,
                initialSyncCompleted = true,
                syncReciente = true,
                pullReciente = true
            )
        )
    }

    // ──────────────────────────────────────────────────────────────
    // S5 fix (SOLO_PUSH starvation): mientras haya pending_ops el modo
    // SOLO_PUSH omitia el PULL SIEMPRE — con un flujo continuo de writes
    // (o un op venenoso que reintenta), el PULL podia quedar privado por
    // horas. Ahora SOLO_PUSH exige ademas que el ultimo sync este dentro
    // de la ventana de 5 min (pullReciente); fuera de ella se baja a
    // PULL_Y_PUSH para refrescar el delta del servidor.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `pending ops con ultimo sync hace 10 min usan pull y push`() {
        assertEquals(
            SyncMode.PULL_Y_PUSH,
            decidirModoSync(
                hayPendingOps = true,
                initialSyncCompleted = true,
                syncReciente = false,
                pullReciente = false
            )
        )
    }

    @Test
    fun `pending ops con ultimo sync dentro de la ventana mantienen push-only`() {
        assertEquals(
            SyncMode.SOLO_PUSH,
            decidirModoSync(
                hayPendingOps = true,
                initialSyncCompleted = true,
                syncReciente = true,
                pullReciente = true
            )
        )
    }

    // ──────────────────────────────────────────────────────────────
    // v10 — restriccion de red del periodico: CONNECTED durante el
    // backfill inicial (usuario solo-movil), UNMETERED despues (M11).
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `periodico usa CONNECTED mientras el sync inicial no completa`() {
        assertEquals(
            NetworkType.CONNECTED,
            restriccionRedSyncPeriodico(initialSyncCompleted = false)
        )
    }

    @Test
    fun `periodico vuelve a UNMETERED tras completar el sync inicial`() {
        assertEquals(
            NetworkType.UNMETERED,
            restriccionRedSyncPeriodico(initialSyncCompleted = true)
        )
    }
}
