package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.datos.local.entidades.DenunciaEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Writes offline-first para [RepositorioDenuncias].
 */

/**
 * Crea una denuncia localmente. NO llama al backend.
 * Bug A3 fix: dedup por CONTENIDO (url, idCategoria, descripcion), no por UUID.
 *
 * @return el id local (nuevo o existente si ya habia una dirty).
 */
suspend fun RepositorioDenuncias.crearLocal(
    url: String,
    idCategoria: Int,
    descripcion: String? = null
): String = withContext(ioDispatcher) {
    db.withTransaction {
        val filaExistente = db.denunciaDao()
            .buscarDirtyPorContenido(url, idCategoria, descripcion)
        if (filaExistente != null) {
            return@withTransaction filaExistente.id
        }

        val idLocal = UUID.randomUUID().toString()
        val ahora = System.currentTimeMillis()

        val entidad = DenunciaEntity(
            id = idLocal,
            url = url,
            idCategoria = idCategoria,
            nombreCategoria = null,
            descripcion = descripcion,
            estado = "PENDIENTE",
            creadoEnMillis = ahora,
            dirty = true,
            syncedAtMillis = null
        )

        val payloadJson = json.encodeToString(DenunciaEntity.serializer(), entidad)

        db.denunciaDao().insertar(entidad)
        db.pendingOpDao().insertar(
            PendingOpEntity(
                tabla = "denuncias",
                tipoOperacion = "CREATE",
                idLocal = idLocal,
                payloadJson = payloadJson,
                creadoEnMillis = ahora
            )
        )
        idLocal
    }
}
