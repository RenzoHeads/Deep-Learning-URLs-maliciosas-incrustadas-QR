package com.qrsecurity.detector.datos.repositorios

import android.util.Log
import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.crearDenuncia
import com.qrsecurity.detector.api.eliminarDenuncia
import com.qrsecurity.detector.datos.local.entidades.DenunciaEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.sync.DecisionPush
import com.qrsecurity.detector.datos.sync.decidirResultadoPushCreate
import com.qrsecurity.detector.datos.sync.decidirResultadoPushDelete
import kotlinx.coroutines.withContext
import java.time.Instant

private const val TAG = "RepositorioDenunciasPendingOps"

/** PUSH: procesa pending_ops (CREATE/DELETE) para [RepositorioDenuncias]. */

suspend fun RepositorioDenuncias.procesarPendingOp(
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

private suspend fun RepositorioDenuncias.procesarCreate(
    op: PendingOpEntity,
    token: String
): Boolean {
    return try {
        val entidadLocal = if (op.payloadJson != null) {
            json.decodeFromString(DenunciaEntity.serializer(), op.payloadJson)
        } else {
            val fila = db.denunciaDao().obtenerPorId(op.idLocal)
            if (fila != null) fila
            else {
                db.pendingOpDao().borrarPorId(op.id)
                return true
            }
        }
        val respuesta = backend.crearDenuncia(
            token = token,
            url = entidadLocal.url,
            idCategoria = entidadLocal.idCategoria,
            descripcion = entidadLocal.descripcion,
            idCliente = op.idLocal
        )
        val ahora = System.currentTimeMillis()
        db.withTransaction {
            // LWW: reemplaza el row local con la respuesta del servidor
            // (incluye nombre_categoria y estado server-assigned).
            val entidadFinalizada = DenunciaEntity(
                id = respuesta.id,
                url = respuesta.url,
                idCategoria = respuesta.idCategoria,
                nombreCategoria = respuesta.nombreCategoria,
                descripcion = respuesta.descripcion,
                estado = respuesta.estado,
                creadoEnMillis = try {
                    Instant.parse(respuesta.creadoEn).toEpochMilli()
                } catch (e: Exception) {
                    entidadLocal.creadoEnMillis
                },
                dirty = false,
                syncedAtMillis = ahora
            )
            if (respuesta.id != entidadLocal.id) {
                db.denunciaDao().eliminarPorId(entidadLocal.id)
            }
            db.denunciaDao().insertar(entidadFinalizada)
            db.pendingOpDao().borrarPorId(op.id)
        }
        true
    } catch (e: ClienteBackend.HttpBackendException) {
        // Bug D2-P2: 409 = servidor ya tiene la denuncia → exito.
        // 400 = permanente → marcar fallida.
        // 401/403/404/429/5xx → transitorio, retry.
        when (decidirResultadoPushCreate(e.codigo)) {
            DecisionPush.Decision.Success -> {
                // Bug A2 fix: eliminar la fila local U-A. El servidor ya la tiene.
                db.withTransaction {
                    db.denunciaDao().eliminarPorId(op.idLocal)
                    db.pendingOpDao().borrarPorId(op.id)
                }
                true
            }
            DecisionPush.Decision.Failure -> {
                // m8 fix: 400 = permanente (id_categoria inexistente, URL > 2048).
                db.pendingOpDao().marcarFallida(op.id)
                true
            }
            DecisionPush.Decision.Retry -> false
        }
    } catch (e: Exception) {
        // Bug H3 (mismo fix que Pipeline.kt:329): rethrow CancellationException
        // para no ejecutar side effects en corutina cancelada.
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    }
}

private suspend fun RepositorioDenuncias.procesarDelete(
    op: PendingOpEntity,
    token: String
): Boolean {
    return try {
        backend.eliminarDenuncia(token, op.idLocal)
        db.withTransaction {
            db.pendingOpDao().borrarPorId(op.id)
            // Bug delete-reaparece: eliminar tambien el row local.
            db.denunciaDao().eliminarPorId(op.idLocal)
        }
        true
    } catch (e: ClienteBackend.HttpBackendException) {
        // Bug D2-P2: 404 = ya borrado → idempotente, exito.
        when (decidirResultadoPushDelete(e.codigo)) {
            DecisionPush.Decision.Success -> {
                db.withTransaction {
                    db.pendingOpDao().borrarPorId(op.id)
                    db.denunciaDao().eliminarPorId(op.idLocal)
                }
                true
            }
            // DELETE no tiene caso permanente (m8/m10 fixes son solo CREATE).
            DecisionPush.Decision.Failure, DecisionPush.Decision.Retry -> false
        }
    } catch (e: Exception) {
        // Bug H3 (mismo fix que Pipeline.kt:329): rethrow CancellationException
        // para no ejecutar side effects en corutina cancelada.
        if (e is kotlinx.coroutines.CancellationException) throw e
        false
    }
}
