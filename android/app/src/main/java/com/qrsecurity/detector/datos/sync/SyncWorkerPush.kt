package com.qrsecurity.detector.datos.sync

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.qrsecurity.detector.datos.local.dao.PendingOpDao
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity

/**
 * PUSH pending_ops (outbox) para [SyncWorker].
 * Extraido a extension function para mantener SyncWorker.kt bajo 250 LOC.
 *
 * A3-a audit fix — claim BATCH. Antes reclamaba un op por iteracion
 * (`minPendingId` + `markInProgress` + `getById` en una tx). Ahora
 * reclama hasta [SyncWorker.BATCH_SIZE_PUSH] ops en una sola tx
 * (`minPendingIds` + `markInProgressBatch` + `getByIds`) y los
 * procesa secuencialmente fuera de la tx, amortizando el fsync de WAL.
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

        // A3-a — reclamar un batch de hasta BATCH_SIZE_PUSH ops en una
        // sola transaccion. markInProgressBatch ya incrementa `intentos`
        // dentro de la tx (garantia atomica: o todos claimados o ninguno).
        // Si el worker muere tras la tx y antes de procesar el ultimo,
        // los K-1 restantes tendran `intentos` phantom-bumped; el margen
        // MAX_INTENTOS_OP=10 cubre >=10 claims fantasma consecutivos
        // (escenario excepcional: SO mata al worker en >50% de sus runs).
        val batch: List<PendingOpEntity> = db.withTransaction {
            val ids = pendingDao.minPendingIds(SyncWorker.BATCH_SIZE_PUSH)
            if (ids.isEmpty()) return@withTransaction emptyList()
            val reclamados = pendingDao.markInProgressBatch(ids)
            if (reclamados == 0) return@withTransaction emptyList()
            pendingDao.getByIds(ids)
        }
        if (batch.isEmpty()) break

        for (op in batch) {
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
                // Salir del batch entero: el presupuesto podria estar
                // agotado y los ops restantes seran re-claimados en el
                // siguiente run. Romper el for evita agotar el batch
                // completo cuando el primer fallo es transitorio (p.ej.,
                // red caida — todos los ops siguientes fallaran igual).
                return errorTransitorio
            }
        }
    }
    return errorTransitorio
}
