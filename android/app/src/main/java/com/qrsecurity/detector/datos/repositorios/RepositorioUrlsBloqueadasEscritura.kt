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
 * @return el id local (nuevo o existente si ya estaba bloqueada).
 */
suspend fun RepositorioUrlsBloqueadas.bloquearLocal(
    url: String,
    razon: String? = null
): String = withContext(ioDispatcher) {
    db.withTransaction {
        val filaExistente = db.urlBloqueadaDao().obtenerPorUrl(url)
        if (filaExistente != null) {
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
                tabla = "urls_bloqueadas",
                tipoOperacion = "CREATE",
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
        if (fila.dirty) {
            val opCreate = db.pendingOpDao().findExisting(
                tabla = "urls_bloqueadas", idLocal = id, tipoOperacion = "CREATE"
            )
            if (opCreate != null) db.pendingOpDao().borrarPorId(opCreate.id)
            db.urlBloqueadaDao().eliminarPorId(id)
        } else {
            val op = PendingOpEntity(
                tabla = "urls_bloqueadas",
                tipoOperacion = "DELETE",
                idLocal = id,
                payloadJson = null,
                creadoEnMillis = System.currentTimeMillis()
            )
            db.urlBloqueadaDao().eliminarPorId(id)
            db.pendingOpDao().insertar(op)
        }
    }
}
