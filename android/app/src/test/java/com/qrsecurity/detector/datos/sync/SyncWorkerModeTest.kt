package com.qrsecurity.detector.datos.sync

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
                syncReciente = false
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
                syncReciente = true
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
                syncReciente = true
            )
        )
    }
}
