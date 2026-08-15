package com.qrsecurity.detector.datos.repositorios

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.eliminarEscaneo
import com.qrsecurity.detector.api.registrarEscaneo
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity

/**
 * PUSH: procesa pending_ops (CREATE/DELETE) para [RepositorioEscaneos].
 *
 * Extension functions sobre [RepositorioEscaneos] — acceden a las
 * propiedades `internal` de la clase principal. El esqueleto completo de
 * procesamiento vive en [ProcesadorPendingOps]; aqui solo lo especifico
 * de la tabla (DAO, endpoint, serializer).
 */

/**
 * PUSH: envia un pending_op al backend. Llamado por SyncWorker al vaciar la cola.
 *
 * @return true si el op fue procesado con exito (eliminar de cola), false si debe retry.
 */
suspend fun RepositorioEscaneos.procesarPendingOp(
    op: PendingOpEntity,
    token: String
): Boolean = ProcesadorPendingOps(
    db, json, ioDispatcher, TablaEscaneosOutbox(db, backend)
).procesar(op, token)

/** [TablaOutbox] de `escaneos` — DAO Room + endpoints HTTP + serializacion. */
private class TablaEscaneosOutbox(
    private val db: BaseDatosSeguridad,
    private val backend: ClienteBackend
) : TablaOutbox<EscaneoEntity> {

    override val nombre = PendingOpEntity.TABLA_ESCANEOS
    override val serializer = EscaneoEntity.serializer()

    override fun idDe(entidad: EscaneoEntity) = entidad.id

    override suspend fun obtenerPorId(idLocal: String) =
        db.escaneoDao().obtenerPorId(idLocal)

    override suspend fun crearRemoto(
        token: String,
        entidad: EscaneoEntity,
        idCliente: String
    ): RespuestaPushCreate {
        val respuesta = backend.registrarEscaneo(
            token = token,
            urlOriginal = entidad.urlOriginal,
            urlLimpia = entidad.urlLimpia,
            probabilidad = entidad.probabilidad,
            nivelAlerta = entidad.nivelAlerta,
            delegado = entidad.delegado,
            idCliente = idCliente
        )
        return RespuestaPushCreate(respuesta.id)
    }

    override suspend fun eliminarRemoto(token: String, id: String) {
        backend.eliminarEscaneo(token, id)
    }

    override suspend fun reKey(idViejo: String, idNuevo: String, ahora: Long): Int =
        db.escaneoDao().reKey(
            idViejo = idViejo,
            idNuevo = idNuevo,
            syncedAt = ahora
        )

    override suspend fun marcarSincronizado(id: String, ahora: Long): Int =
        db.escaneoDao().marcarSincronizado(id, ahora)

    override suspend fun eliminarPorId(id: String) =
        db.escaneoDao().eliminarPorId(id)
}
