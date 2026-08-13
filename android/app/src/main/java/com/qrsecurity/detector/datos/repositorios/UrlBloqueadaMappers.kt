package com.qrsecurity.detector.datos.repositorios

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.UrlBloqueada
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import java.time.Instant

/** Extension: DTO UrlBloqueada backend → entidad Room. */
internal fun UrlBloqueada.aEntidad(syncedAt: Long): UrlBloqueadaEntity {
    val creadoMillis = try {
        Instant.parse(creadoEn).toEpochMilli()
    } catch (e: Exception) {
        // WAVE 13 fix (M2): sentinel Long.MIN_VALUE para orden al fondo.
        Long.MIN_VALUE
    }
    return UrlBloqueadaEntity(
        id = id,
        url = url,
        razon = razon,
        creadoEnMillis = creadoMillis,
        dirty = false,
        syncedAtMillis = syncedAt
    )
}
