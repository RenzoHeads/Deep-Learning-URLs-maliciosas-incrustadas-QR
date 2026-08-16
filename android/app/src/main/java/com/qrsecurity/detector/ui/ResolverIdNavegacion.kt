package com.qrsecurity.detector.ui

import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.pipeline.ResultadoAnalisis

/**
 * Resuelve el id del escaneo al que debe navegar la UI tras un
 * [com.qrsecurity.detector.pipeline.Estado.ResultadoListo].
 *
 * Estrategia en cascada (primera opcion no-vacia gana):
 *  1. [idLocal] — UUID persistido por el Pipeline en el momento exacto del
 *     INSERT en Room. Es la fuente de verdad cuando esta disponible (no
 *     sufre race con la emision del Flow `historial` de Room).
 *  2. Match exacto en [historial] por (urlLimpia + urlOriginal). Cubre la
 *     rara carrera en la que [idLocal] es null pero Room ya emitio la fila
 *     recien insertada.
 *  3. Match parcial por [ResultadoAnalisis.ResultadoUrl.urlLimpia], tomando
 *     el escaneo mas reciente por [EscaneoEntity.creadoEnMillis]. Fallback
 *     final cuando el historial aun no refleja el INSERT pero existe un
 *     escaneo previo de la misma URL limpia.
 *  4. `null` — sin candidato. La UI emite "No se pudo guardar el analisis".
 *
 * Extraida del bloque inline en [PantallaAnalisis] (Audit fix B3) para
 * habilitar testeo unitario JVM sin instanciar el Pipeline ni Room.
 *
 * @param idLocal UUID del escaneo persistido, o null si el Pipeline no lo
 *   asigno (path NoUrl o race de emision).
 * @param resultado Resultado de inferencia con [ResultadoAnalisis.ResultadoUrl.urlLimpia]
 *   y [ResultadoAnalisis.ResultadoUrl.urlOriginal] para el match.
 * @param historial Snapshot actual del historial de escaneos (List<EscaneoEntity>).
 * @return Id del escaneo candidato, o null si no se encuentra ninguno.
 */
internal fun resolverIdNavegacion(
    idLocal: String?,
    resultado: ResultadoAnalisis.ResultadoUrl,
    historial: List<EscaneoEntity>
): String? {
    // 1. idLocal directo (fuente de verdad, sin race).
    if (!idLocal.isNullOrEmpty()) return idLocal

    // 2. Match exacto por (urlLimpia + urlOriginal).
    historial.firstOrNull {
        it.urlLimpia == resultado.urlLimpia &&
            it.urlOriginal == resultado.urlOriginal
    }?.let { return it.id }

    // 3. Fallback parcial por urlLimpia, mas reciente.
    return historial
        .filter { it.urlLimpia == resultado.urlLimpia }
        .maxByOrNull { it.creadoEnMillis }
        ?.id
}
