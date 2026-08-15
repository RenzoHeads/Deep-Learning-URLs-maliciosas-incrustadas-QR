package com.qrsecurity.detector.datos.repositorios

import android.util.Log
import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.bloquearUrl
import com.qrsecurity.detector.api.desbloquearUrl
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.sync.DecisionPush
import com.qrsecurity.detector.datos.sync.decidirResultadoPushCreate
import com.qrsecurity.detector.datos.sync.decidirResultadoPushDelete
import kotlinx.coroutines.withContext

private const val TAG = "RepositorioUrlsBloqueadasPendingOps"

/** PUSH: procesa pending_ops (CREATE/DELETE) para [RepositorioUrlsBloqueadas]. */

suspend fun RepositorioUrlsBloqueadas.procesarPendingOp(
    op: PendingOpEntity,
    token: String
): Boolean = withContext(ioDispatcher) {
    when (op.tipoOperacion) {
        "CREATE" -> procesarCreate(op, token)
        "DELETE" -> procesarDelete(op, token)
        else -> {
            // Bug H4 (debuggability): tipoOperacion desconocido — ver comentario
            // en RepositorioEscaneosPendingOps. Log.wtf para que se vea en logcat.
            Log.wtf(
                TAG,
                "tipoOperacion desconocido: '${op.tipoOperacion}' " +
                    "(id=${op.id}, tabla=${op.tabla}, idLocal=${op.idLocal})"
            )
            false
        }
    }
}

private suspend fun RepositorioUrlsBloqueadas.procesarCreate(
    op: PendingOpEntity,
    token: String
): Boolean {
    return try {
        val entidadLocal = if (op.payloadJson != null) {
            json.decodeFromString(UrlBloqueadaEntity.serializer(), op.payloadJson)
        } else {
            val fila = db.urlBloqueadaDao().obtenerPorId(op.idLocal)
            if (fila != null) fila
            else {
                db.pendingOpDao().borrarPorId(op.id)
                return true
            }
        }
        val respuesta = backend.bloquearUrl(
            token = token,
            url = entidadLocal.url,
            razon = entidadLocal.razon,
            idCliente = op.idLocal
        )
        val ahora = System.currentTimeMillis()
        db.withTransaction {
            // Bug C1 fix: si re-key afecta 0 filas → encolar DELETE con server id.
            val filasAfectadas = if (respuesta.id != entidadLocal.id) {
                db.urlBloqueadaDao().reKey(entidadLocal.id, respuesta.id, ahora)
            } else {
                db.urlBloqueadaDao().marcarSincronizado(entidadLocal.id, ahora)
            }
            if (filasAfectadas == 0) {
                db.pendingOpDao().insertar(
                    PendingOpEntity(
                        tabla = "urls_bloqueadas",
                        tipoOperacion = "DELETE",
                        idLocal = respuesta.id,
                        payloadJson = null,
                        creadoEnMillis = ahora
                    )
                )
            }
            db.pendingOpDao().borrarPorId(op.id)
        }
        true
    } catch (e: ClienteBackend.HttpBackendException) {
        when (decidirResultadoPushCreate(e.codigo)) {
            DecisionPush.Decision.Success -> {
                // Bug A2 fix: eliminar la fila local. El servidor ya la tiene.
                db.withTransaction {
                    db.urlBloqueadaDao().eliminarPorId(op.idLocal)
                    db.pendingOpDao().borrarPorId(op.id)
                }
                true
            }
            DecisionPush.Decision.Failure -> {
                db.pendingOpDao().marcarFallida(op.id)
                true
            }
            DecisionPush.Decision.Retry -> false
        }
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        // Audit fix (reKey PK collision): la fila con el id del servidor ya
        // existe localmente (llegó por PULL antes de que este PUSH terminara
        // — p.ej. la misma URL bloqueada desde otro dispositivo). El UPDATE
        // `reKey` viola la PK en vez de afectar 0 filas. Sin esta rama, el
        // catch genérico devolvía false → retry infinito del op (nunca
        // marcarFallida). Resolución: eliminar la fila local (client UUID) y
        // borrar el op — el PULL ya insertó/reemplazará la fila con el id
        // del servidor.
        db.withTransaction {
            db.urlBloqueadaDao().eliminarPorId(op.idLocal)
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

private suspend fun RepositorioUrlsBloqueadas.procesarDelete(
    op: PendingOpEntity,
    token: String
): Boolean {
    return try {
        backend.desbloquearUrl(token, op.idLocal)
        db.withTransaction {
            db.pendingOpDao().borrarPorId(op.id)
            db.urlBloqueadaDao().eliminarPorId(op.idLocal)
        }
        true
    } catch (e: ClienteBackend.HttpBackendException) {
        when (decidirResultadoPushDelete(e.codigo)) {
            DecisionPush.Decision.Success -> {
                db.withTransaction {
                    db.pendingOpDao().borrarPorId(op.id)
                    db.urlBloqueadaDao().eliminarPorId(op.idLocal)
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
