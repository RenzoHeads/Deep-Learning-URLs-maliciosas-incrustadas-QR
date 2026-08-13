package com.qrsecurity.detector.datos.sync

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.qrsecurity.detector.datos.local.dao.PendingOpDao
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity

/**
 * PUSH pending_ops (outbox) para [SyncWorker].
 * Extraido a extension function para mantener SyncWorker.kt bajo 250 LOC.
 */

internal suspend fun SyncWorker.procesarPendingOps(
    pendingDao: PendingOpDao,
    repos: Map<String, suspend (PendingOpEntity) -> Boolean>,
    workerStartMs: Long
): Boolean {
    var errorTransitorio = false
    while (true) {
        if (debeCederPresupuestoPush(
                workerStartMs,
                SystemClock.elapsedRealtime(),
                SyncWorker.PRESUPUESTO_PUSH_MS
            )
        ) {
            Log.w(SyncWorker.TAG, "procesarPendingOps: presupuesto agotado → Result.retry()")
            errorTransitorio = true
            break
        }

        val op = db.withTransaction {
            val id = pendingDao.minPendingId() ?: return@withTransaction null
            val filas = pendingDao.markInProgress(id)
            if (filas == 0) return@withTransaction null
            pendingDao.getById(id)
        }
        if (op == null) break

        if (op.intentos > SyncWorker.MAX_INTENTOS_OP) {
            pendingDao.marcarFallida(op.id)
            continue
        }

        val procesador = repos[op.tabla]
        val exito = if (procesador != null) {
            procesador(op)
        } else {
            pendingDao.marcarFallida(op.id)
            true
        }

        if (!exito) {
            errorTransitorio = true
            break
        }
    }
    return errorTransitorio
}
