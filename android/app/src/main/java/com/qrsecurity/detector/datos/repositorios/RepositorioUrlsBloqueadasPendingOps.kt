package com.qrsecurity.detector.datos.repositorios

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.bloquearUrl
import com.qrsecurity.detector.api.desbloquearUrl
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity

/**
 * PUSH: procesa pending_ops (CREATE/DELETE) para [RepositorioUrlsBloqueadas].
 *
 * Extension functions sobre [RepositorioUrlsBloqueadas] — acceden a las
 * propiedades `internal` de la clase principal. El esqueleto completo de
 * procesamiento vive en [ProcesadorPendingOps]; aqui solo lo especifico
 * de la tabla (DAO, endpoint, serializer).
 */

/**
 * PUSH: envia un pending_op al backend. Llamado por SyncWorker al vaciar la cola.
 *
 * @return true si el op fue procesado con exito (eliminar de cola), false si debe retry.
 */
suspend fun RepositorioUrlsBloqueadas.procesarPendingOp(
    op: PendingOpEntity,
    token: String
): Boolean = ProcesadorPendingOps(
    db, json, ioDispatcher, TablaUrlsBloqueadasOutbox(db, backend)
).procesar(op, token)

/** [TablaOutbox] de `urls_bloqueadas` — DAO Room + endpoints HTTP + serializacion. */
private class TablaUrlsBloqueadasOutbox(
    private val db: BaseDatosSeguridad,
    private val backend: ClienteBackend
) : TablaOutbox<UrlBloqueadaEntity> {

    override val nombre = PendingOpEntity.TABLA_URLS_BLOQUEADAS
    override val serializer = UrlBloqueadaEntity.serializer()

    override fun idDe(entidad: UrlBloqueadaEntity) = entidad.id

    override suspend fun obtenerPorId(idLocal: String) =
        db.urlBloqueadaDao().obtenerPorId(idLocal)

    override suspend fun crearRemoto(
        token: String,
        entidad: UrlBloqueadaEntity,
        idCliente: String
    ): RespuestaPushCreate {
        val respuesta = backend.bloquearUrl(
            token = token,
            url = entidad.url,
            razon = entidad.razon,
            idCliente = idCliente
        )
        return RespuestaPushCreate(respuesta.id)
    }

    override suspend fun eliminarRemoto(token: String, id: String) {
        backend.desbloquearUrl(token, id)
    }

    override suspend fun reKey(idViejo: String, idNuevo: String, ahora: Long): Int =
        db.urlBloqueadaDao().reKey(idViejo, idNuevo, ahora)

    override suspend fun marcarSincronizado(id: String, ahora: Long): Int =
        db.urlBloqueadaDao().marcarSincronizado(id, ahora)

    override suspend fun eliminarPorId(id: String) =
        db.urlBloqueadaDao().eliminarPorId(id)
}
