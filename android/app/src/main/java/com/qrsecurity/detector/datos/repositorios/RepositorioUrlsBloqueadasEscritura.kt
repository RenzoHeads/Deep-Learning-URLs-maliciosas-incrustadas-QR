package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Writes offline-first para [RepositorioUrlsBloqueadas].
 */

/**
 * Bloquea una URL localmente. NO llama al backend.
 * Bug A3 fix: dedup por contenido (URL) en lugar de idLocal (UUID fresh).
 *
 * S6 fix: el early-return por fila existente solo aplica cuando NO hay un
 * DELETE pendiente para esa fila. Si el usuario desbloqueo X (DELETE encolado)
 * y el PULL resucito la fila (dirty=0, DELETE aun en cola), el bloqueo nuevo
 * debe CANCELAR ese DELETE y re-encolar un CREATE (fila dirty=1) — si no, el
 * push dispararia el DELETE y el bloqueo del usuario se perderia en ambos
 * lados (obtenerPorUrl no excluye filas con DELETE pendiente, a diferencia
 * de observarTodos).
 *
 * @return el id local (nuevo o existente si ya estaba bloqueada).
 */
suspend fun RepositorioUrlsBloqueadas.bloquearLocal(
    url: String,
    razon: String? = null
): String = withContext(ioDispatcher) {
    db.withTransaction {
        val filaExistente = db.urlBloqueadaDao().obtenerPorUrl(url)
        if (filaExistente != null) {
            // S6 fix: cancelar el DELETE pendiente (si hay) y re-encolar un
            // CREATE con la fila revivida (dirty=1) — el push re-crea el
            // bloqueo en el servidor en vez de borrarlo.
            val opDelete = db.pendingOpDao().findExisting(
                tabla = PendingOpEntity.TABLA_URLS_BLOQUEADAS,
                idLocal = filaExistente.id,
                tipoOperacion = PendingOpEntity.OP_DELETE
            )
            if (opDelete == null) {
                return@withTransaction filaExistente.id
            }
            db.pendingOpDao().borrarPorId(opDelete.id)

            val ahora = System.currentTimeMillis()
            val revivida = filaExistente.copy(
                razon = razon ?: filaExistente.razon,
                dirty = true,
                syncedAtMillis = null
            )
            val payloadRevivida = json.encodeToString(
                UrlBloqueadaEntity.serializer(), revivida
            )
            db.urlBloqueadaDao().insertar(revivida)
            db.pendingOpDao().insertar(
                PendingOpEntity(
                    tabla = PendingOpEntity.TABLA_URLS_BLOQUEADAS,
                    tipoOperacion = PendingOpEntity.OP_CREATE,
                    idLocal = filaExistente.id,
                    payloadJson = payloadRevivida,
                    creadoEnMillis = ahora
                )
            )
            return@withTransaction filaExistente.id
        }

        val idLocal = UUID.randomUUID().toString()
        val ahora = System.currentTimeMillis()

        val entidad = UrlBloqueadaEntity(
            id = idLocal,
            url = url,
            razon = razon,
            creadoEnMillis = ahora,
            dirty = true,
            syncedAtMillis = null
        )

        val payloadJson = json.encodeToString(UrlBloqueadaEntity.serializer(), entidad)

        db.urlBloqueadaDao().insertar(entidad)
        db.pendingOpDao().insertar(
            PendingOpEntity(
                tabla = PendingOpEntity.TABLA_URLS_BLOQUEADAS,
                tipoOperacion = PendingOpEntity.OP_CREATE,
                idLocal = idLocal,
                payloadJson = payloadJson,
                creadoEnMillis = ahora
            )
        )
        idLocal
    }
}

/**
 * Desbloquea (elimina) una URL localmente. Si el row estaba dirty, borra
 * row + pending CREATE. Si estaba synced, encola DELETE.
 * Bug phantom-rows fix: simetrico con RepositorioEscaneos.eliminarLocal.
 */
suspend fun RepositorioUrlsBloqueadas.desbloquearLocal(id: String) = withContext(ioDispatcher) {
    db.withTransaction {
        val fila = db.urlBloqueadaDao().obtenerPorId(id)
        if (fila == null) return@withTransaction
        db.eliminarFilaDirty(PendingOpEntity.TABLA_URLS_BLOQUEADAS, id, fila.dirty) {
            db.urlBloqueadaDao().eliminarPorId(id)
        }
    }
}
