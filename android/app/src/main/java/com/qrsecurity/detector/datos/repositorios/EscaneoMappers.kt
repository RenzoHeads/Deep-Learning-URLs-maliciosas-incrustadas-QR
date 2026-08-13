package com.qrsecurity.detector.datos.repositorios

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import java.time.Instant

// ── WAVE 11 fix (C1 CRITICAL): esMalicioso se deriva SIEMPRE de nivelAlerta,
// igual que el path local (l.174). Antes aEntidad copiaba el bool del DTO
// (`es_malicious`), que puede desincronizarse del nivelAlerta por bug de
// backend, partial update o DB drift — un scan MALICIOSO aparecia en la
// pestaña "Seguros". Ahora nivelAlerta es la unica fuente de verdad.
internal fun ClienteBackend.Escaneo.aEntidad(syncedAt: Long): EscaneoEntity {
    val creadoMillis = try {
        Instant.parse(creadoEn).toEpochMilli()
    } catch (e: Exception) {
        // WAVE 13 fix (M2): antes System.currentTimeMillis() → la fila aparecia
        // como "hoy" en el historial y rompia el ORDER BY creadoEnMillis DESC
        // (dedup por ultima version). Usamos Long.MIN_VALUE como sentinel para
        // que el row se ordene al fondo (fecha desconocida) y sea detectable
        // en diagnostico. No dropamos la fila: el backend la mando, el usuario
        // merece verla; solo no debe contaminar el orden cronologico.
        Long.MIN_VALUE
    }
    val nivelUpper = nivelAlerta.uppercase()
    val esMalicioso = nivelUpper == "MALICIOSO"
    return EscaneoEntity(
        id = id,
        urlOriginal = urlOriginal,
        urlLimpia = urlLimpia,
        probabilidad = probabilidad,
        nivelAlerta = nivelUpper,
        delegado = delegado,
        esMalicioso = esMalicioso,
        creadoEnMillis = creadoMillis,
        dirty = false,
        syncedAtMillis = syncedAt,
        notasAnalisis = notasAnalisis
    )
}
