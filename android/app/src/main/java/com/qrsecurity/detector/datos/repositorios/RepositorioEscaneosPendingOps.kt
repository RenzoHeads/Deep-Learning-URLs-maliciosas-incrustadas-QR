package com.qrsecurity.detector.datos.repositorios

import android.util.Log
import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.eliminarEscaneo
import com.qrsecurity.detector.api.registrarEscaneo
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.sync.DecisionPush
import com.qrsecurity.detector.datos.sync.decidirResultadoPushCreate
import com.qrsecurity.detector.datos.sync.decidirResultadoPushDelete
import kotlinx.coroutines.withContext

private const val TAG = "RepositorioEscaneosPendingOps"

/**
 * PUSH: procesa pending_ops (CREATE/DELETE) para [RepositorioEscaneos].
 *
 * Extension functions sobre [RepositorioEscaneos] — acceden a las
 * propiedades `internal` de la clase principal.
 */

/**
 * PUSH: envia un pending_op al backend. Llamado por SyncWorker al vaciar la cola.
 *
 * @return true si el op fue procesado con exito (eliminar de cola), false si debe retry.
 */
suspend fun RepositorioEscaneos.procesarPendingOp(
    op: PendingOpEntity,
    token: String
): Boolean = withContext(ioDispatcher) {
    when (op.tipoOperacion) {
        "CREATE" -> procesarCreate(op, token)
        "DELETE" -> procesarDelete(op, token)
        else -> {
            // Bug H4 (debuggability): un tipoOperacion desconocido nunca
            // debe llegar aqui — el indice de pending_ops solo admite CREATE
            // y DELETE. Si una migracion futura anade un tipo nuevo y se
            // olvida de propagarlo a este when, el worker entraria en retry
            // infinito silencioso. Log.wtf asegura que se vea en logcat.
            Log.wtf(
                TAG,
                "tipoOperacion desconocido: '${op.tipoOperacion}' " +
                    "(id=${op.id}, tabla=${op.tabla}, idLocal=${op.idLocal})"
            )
            false
        }
    }
}

private suspend fun RepositorioEscaneos.procesarCreate(
    op: PendingOpEntity,
    token: String
): Boolean {
    return try {
        // Bug payloadJson=NULL fix: reconstruir desde tabla local si es NULL.
        val entidadLocal = if (op.payloadJson != null) {
            json.decodeFromString(
                com.qrsecurity.detector.datos.local.entidades.EscaneoEntity.serializer(),
                op.payloadJson
            )
        } else {
            val fila = db.escaneoDao().obtenerPorId(op.idLocal)
            if (fila != null) {
                fila
            } else {
                db.pendingOpDao().borrarPorId(op.id)
                return true
            }
        }
        val escaneoRespuesta = backend.registrarEscaneo(
            token = token,
            urlOriginal = entidadLocal.urlOriginal,
            urlLimpia = entidadLocal.urlLimpia,
            probabilidad = entidadLocal.probabilidad,
            nivelAlerta = entidadLocal.nivelAlerta,
            delegado = entidadLocal.delegado,
            // Bug A5 fix: idempotencia server-side
            idCliente = op.idLocal
        )
        // Re-key: id local (client UUID) → id servidor (server UUID).
        val ahora = System.currentTimeMillis()
        db.withTransaction {
            // Bug C1 fix: si el re-key afecto 0 filas → la fila fue eliminada
            // en vuelo → encolar DELETE con el id de servidor.
            val filasAfectadas = if (escaneoRespuesta.id != entidadLocal.id) {
                db.escaneoDao().reKey(
                    idViejo = entidadLocal.id,
                    idNuevo = escaneoRespuesta.id,
                    syncedAt = ahora
                )
            } else {
                db.escaneoDao().marcarSincronizado(entidadLocal.id, ahora)
            }
            if (filasAfectadas == 0) {
                db.pendingOpDao().insertar(
                    PendingOpEntity(
                        tabla = "escaneos",
                        tipoOperacion = "DELETE",
                        idLocal = escaneoRespuesta.id,
                        payloadJson = null,
                        creadoEnMillis = ahora
                    )
                )
            }
            db.pendingOpDao().borrarPorId(op.id)
        }
        true
    } catch (e: ClienteBackend.HttpBackendException) {
        // Bug D2-P2: 409 = servidor ya tiene la fila (idempotente) → exito.
        // 400 = peticion invalida permanente → marcar fallida.
        // 401/403/404/429/5xx/IOException → transitorio, retry.
        when (decidirResultadoPushCreate(e.codigo)) {
            DecisionPush.Decision.Success -> {
                // Bug A2 fix: eliminar la fila local U-A. El servidor ya tiene el
                // row (bajo id=U-Z), el siguiente PULL hara INSERT OR REPLACE.
                db.withTransaction {
                    db.escaneoDao().eliminarPorId(op.idLocal)
                    db.pendingOpDao().borrarPorId(op.id)
                }
                true
            }
            DecisionPush.Decision.Failure -> {
                // m10 fix: 400 = permanente, marcar fallida para sacarlo de la cola.
                db.pendingOpDao().marcarFallida(op.id)
                true
            }
            DecisionPush.Decision.Retry -> false
        }
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        // Audit fix (reKey PK collision): la fila con el id del servidor ya
        // existe localmente (llegó por PULL antes de que este PUSH terminara).
        // El UPDATE `reKey` viola la PK en vez de afectar 0 filas. Sin esta
        // rama, el catch genérico devolvía false → retry infinito del op.
        // Resolución: eliminar la fila local (client UUID) y borrar el op —
        // el PULL ya insertó/reemplazará la fila con el id del servidor.
        // Nota: `urls_catalogo` se reconciliará en el próximo PULL/borrado
        // (reconciliarUrlsCatalogo corre en aplicarBatchEscaneos).
        db.withTransaction {
            db.escaneoDao().eliminarPorId(op.idLocal)
            db.pendingOpDao().borrarPorId(op.id)
        }
        true
    } catch (e: Exception) {
        // Bug H3 (mismo fix que Pipeline.kt:329): rethrow CancellationException
        // para no ejecutar side effects en corutina cancelada.
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    }
}

private suspend fun RepositorioEscaneos.procesarDelete(
    op: PendingOpEntity,
    token: String
): Boolean {
    return try {
        backend.eliminarEscaneo(token, op.idLocal)
        db.withTransaction {
            db.pendingOpDao().borrarPorId(op.id)
            // Bug delete-reaparece: eliminar tambien el row local.
            db.escaneoDao().eliminarPorId(op.idLocal)
        }
        true
    } catch (e: ClienteBackend.HttpBackendException) {
        // Bug D2-P2: 404 = row ya borrado en backend → idempotente, exito.
        // 401/403/409/429/5xx/IOException → transitorio, retry.
        when (decidirResultadoPushDelete(e.codigo)) {
            DecisionPush.Decision.Success -> {
                db.withTransaction {
                    db.pendingOpDao().borrarPorId(op.id)
                    db.escaneoDao().eliminarPorId(op.idLocal)
                }
                true
            }
            // DELETE no tiene caso permanente (m10/m8 fixes son solo CREATE).
            DecisionPush.Decision.Failure, DecisionPush.Decision.Retry -> false
        }
    } catch (e: Exception) {
        // Bug H3 (mismo fix que Pipeline.kt:329): rethrow CancellationException
        // para no ejecutar side effects en corutina cancelada.
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    }
}
