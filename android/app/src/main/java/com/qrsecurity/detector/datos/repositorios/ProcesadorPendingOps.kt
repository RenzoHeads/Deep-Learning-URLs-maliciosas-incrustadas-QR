package com.qrsecurity.detector.datos.repositorios

import android.util.Log
import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.sync.DecisionPush
import com.qrsecurity.detector.datos.sync.decidirResultadoPushCreate
import com.qrsecurity.detector.datos.sync.decidirResultadoPushDelete
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Respuesta del CREATE remoto — al outbox solo le importa el id de servidor. */
class RespuestaPushCreate(val id: String)

/**
 * Fachada de una tabla sobre el outbox `pending_ops`.
 *
 * Captura TODO lo especifico de cada tabla (DAO, endpoint remoto,
 * serializacion, nombre) tras un unico esqueleto de procesamiento —
 * [ProcesadorPendingOps]. Antes de este, el esqueleto completo (rebuild
 * del payload, POST, re-key, decision HTTP, PK-collision, rethrow de
 * Cancellation) vivia duplicado en
 * `Repositorio{Escaneos,UrlsBloqueadas}PendingOps` solo cambiando estos
 * metodos.
 */
internal interface TablaOutbox<E : Any> {
    /** Etiqueta del op en `pending_ops` (ver [PendingOpEntity.TABLA_*]). */
    val nombre: String
    val serializer: KSerializer<E>
    fun idDe(entidad: E): String
    suspend fun obtenerPorId(idLocal: String): E?
    suspend fun crearRemoto(
        token: String,
        entidad: E,
        idCliente: String
    ): RespuestaPushCreate
    suspend fun eliminarRemoto(token: String, id: String)

    /** Re-key client-UUID → server-UUID. Devuelve filas afectadas. */
    suspend fun reKey(idViejo: String, idNuevo: String, ahora: Long): Int
    suspend fun marcarSincronizado(id: String, ahora: Long): Int
    suspend fun eliminarPorId(id: String)
}

/**
 * PUSH: procesa un pending_op (CREATE/DELETE) contra el backend.
 *
 * Semantica (identica a la que vivia en cada repositorio):
 *  - CREATE: reconstruye la entidad desde el payload (o desde la tabla
 *    local si el payload es null); POST; re-key al id de servidor; si el
 *    re-key afecto 0 filas (fila eliminada en vuelo) encola un DELETE
 *    con el id de servidor; saca el op de la cola.
 *  - 409 en CREATE: el servidor ya tiene la fila (idempotencia via
 *    idCliente) → eliminar la fila local; el siguiente PULL la trae.
 *  - 400 en CREATE: permanente → marcar op fallida (fuera de la cola).
 *  - 404 en DELETE: el servidor ya no la tiene → idempotente, exito.
 *  - [android.database.sqlite.SQLiteConstraintException]: el re-key choco
 *    con una PK que llego por PULL antes → eliminar la fila local y el op.
 *  - [kotlinx.coroutines.CancellationException]: rethrow (Bug H3) — sin
 *    side effects en corutina cancelada.
 *
 * @return true si el op fue procesado (sacarlo de la cola), false si debe
 *   reintentarse en el proximo run.
 */
internal class ProcesadorPendingOps<E : Any>(
    private val db: BaseDatosSeguridad,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
    private val tabla: TablaOutbox<E>
) {
    suspend fun procesar(op: PendingOpEntity, token: String): Boolean =
        withContext(ioDispatcher) {
            when (op.tipoOperacion) {
                PendingOpEntity.OP_CREATE -> procesarCreate(op, token)
                PendingOpEntity.OP_DELETE -> procesarDelete(op, token)
                else -> {
                    // El indice de pending_ops solo admite CREATE/DELETE; un
                    // tipo desconocido nunca debe llegar aqui. Si una
                    // migracion futura anade un tipo y se olvida de
                    // propagarlo a este when, el worker entraria en retry
                    // infinito silencioso — Log.wtf asegura que se vea.
                    Log.wtf(
                        TAG,
                        "tipoOperacion desconocido: '${op.tipoOperacion}' " +
                            "(id=${op.id}, tabla=${op.tabla}, idLocal=${op.idLocal})"
                    )
                    false
                }
            }
        }

    private suspend fun procesarCreate(op: PendingOpEntity, token: String): Boolean {
        return try {
            // Bug payloadJson=NULL fix: reconstruir desde tabla local si es NULL.
            val entidadLocal = if (op.payloadJson != null) {
                json.decodeFromString(tabla.serializer, op.payloadJson)
            } else {
                val fila = tabla.obtenerPorId(op.idLocal)
                if (fila != null) {
                    fila
                } else {
                    // La fila local ya no existe — nada que pushear.
                    db.pendingOpDao().borrarPorId(op.id)
                    return true
                }
            }
            val idEntidad = tabla.idDe(entidadLocal)
            // Bug A5 fix: idempotencia server-side via idCliente.
            val respuesta = tabla.crearRemoto(token, entidadLocal, idCliente = op.idLocal)
            val ahora = System.currentTimeMillis()
            db.withTransaction {
                // Bug C1 fix: si el re-key afecto 0 filas → la fila fue
                // eliminada en vuelo → encolar DELETE con el id de servidor.
                val filasAfectadas = if (respuesta.id != idEntidad) {
                    tabla.reKey(idEntidad, respuesta.id, ahora)
                } else {
                    tabla.marcarSincronizado(idEntidad, ahora)
                }
                if (filasAfectadas == 0) {
                    db.pendingOpDao().insertar(
                        PendingOpEntity(
                            tabla = tabla.nombre,
                            tipoOperacion = PendingOpEntity.OP_DELETE,
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
                    // Bug A2 fix: el servidor ya tiene el row (bajo otro id);
                    // eliminar la fila local — el siguiente PULL hara
                    // INSERT OR REPLACE.
                    db.withTransaction {
                        tabla.eliminarPorId(op.idLocal)
                        db.pendingOpDao().borrarPorId(op.id)
                    }
                    true
                }
                DecisionPush.Decision.Failure -> {
                    // 400 = permanente, marcar fallida para sacarlo de la cola.
                    db.pendingOpDao().marcarFallida(op.id)
                    true
                }
                DecisionPush.Decision.Retry -> false
            }
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // Audit fix (reKey PK collision): la fila con el id del servidor
            // ya existe localmente (llego por PULL antes de que este PUSH
            // terminara). El UPDATE `reKey` viola la PK en vez de afectar 0
            // filas. Resolucion: eliminar la fila local (client UUID) y
            // borrar el op — el PULL ya inserto/reemplazara la fila con el
            // id del servidor.
            db.withTransaction {
                tabla.eliminarPorId(op.idLocal)
                db.pendingOpDao().borrarPorId(op.id)
            }
            true
        } catch (e: Exception) {
            // Bug H3: rethrow CancellationException para no ejecutar side
            // effects en corutina cancelada.
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    private suspend fun procesarDelete(op: PendingOpEntity, token: String): Boolean {
        return try {
            tabla.eliminarRemoto(token, op.idLocal)
            db.withTransaction {
                db.pendingOpDao().borrarPorId(op.id)
                // Bug delete-reaparece: eliminar tambien el row local.
                tabla.eliminarPorId(op.idLocal)
            }
            true
        } catch (e: ClienteBackend.HttpBackendException) {
            when (decidirResultadoPushDelete(e.codigo)) {
                DecisionPush.Decision.Success -> {
                    // 404 = row ya borrado en backend → idempotente, exito.
                    db.withTransaction {
                        db.pendingOpDao().borrarPorId(op.id)
                        tabla.eliminarPorId(op.idLocal)
                    }
                    true
                }
                // DELETE no tiene caso permanente.
                DecisionPush.Decision.Failure, DecisionPush.Decision.Retry -> false
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    private companion object {
        const val TAG = "ProcesadorPendingOps"
    }
}
