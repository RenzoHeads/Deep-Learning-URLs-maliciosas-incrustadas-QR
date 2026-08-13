package com.qrsecurity.detector.datos.repositorios

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.Denuncia
import com.qrsecurity.detector.datos.local.entidades.DenunciaEntity
import java.time.Instant

/** Extension: mapea un DTO Denuncia del backend a la entidad Room (LWW). */
internal fun Denuncia.aEntidad(syncedAt: Long): DenunciaEntity {
    val creadoMillis = try {
        Instant.parse(creadoEn).toEpochMilli()
    } catch (e: Exception) {
        // BUG-M4 fix: sentinel Long.MIN_VALUE para "valor desconocido".
        Long.MIN_VALUE
    }
    return DenunciaEntity(
        id = id,
        url = url,
        idCategoria = idCategoria,
        nombreCategoria = nombreCategoria,
        descripcion = descripcion,
        estado = estado,
        creadoEnMillis = creadoMillis,
        dirty = false,
        syncedAtMillis = syncedAt
    )
}
