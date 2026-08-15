package com.qrsecurity.detector.pipeline

import com.qrsecurity.detector.ml.ControladorAlerta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Todos los posibles estados del pipeline (expuestos a la UI via
 * [Pipeline.estado]).
 */
sealed class Estado {
    /** El pipeline esta arrancando (cargando modelo, etc.). */
    data object Inicializando : Estado()

    /** Esperando que se escanee un codigo QR. */
    data object Escaneando : Estado()

    /**
     * Bug F fix: dedup check completado, inference en progreso.
     *
     * Estado intermedio entre [Escaneando] (extraccion + dedup) y
     * [ResultadoListo] (inference completada). Permite que la UI navegue
     * a AnalisisScreen SOLO cuando hay inference real en vuelo — no cuando
     * el dedup check todavia no ha terminado (caso URL duplicada donde el
     * pipeline emitira [UrlDuplicada] sin pasar por [Analizando]).
     */
    data object Analizando : Estado()

    /**
     * Deduplicacion (cache + log): todas las URLs del QR escaneado ya estaban
     * en el cache maestro `urls_catalogo` (escaneadas antes). El pipeline
     * NO persiste un nuevo escaneo ni re-ejecuta inferencia — se cortocircuita
     * y deja que la UI (NavGuardian) pregunte al usuario si desea reescanear.
     *
     * Si el usuario confirma, se llama a `Pipeline.analizar` con `forzar=true`,
     * que ignora el cache, re-ejecuta inferencia y produce un [ResultadoListo]
     * (INSERTANDO un nuevo escaneo en el log append-only `escaneos` + UPSERT
     * del cache en la misma transaccion).
     *
     * @property resultado El [ResultadoAnalisis.ResultadoUrl] peor del QR
     *  (primaria + urlsAdicionales), para que el diálogo reutilice el
     *  render de nivel/probabilidad que ya existe en la UI.
     * @property urlsLimpiaConsultadas Las URLs (limpias) cuyo cache hit
     *  triggered el estado — para mostrar "N URL(s) ya escaneada(s)" y para
     *  que la UI decida el mensaje (single vs multi-URL).
     * @property vecesEscaneadaMaxima El max `vecesEscaneada` entre las URLs
     *  consultadas (info para el diálogo: "escaneada X veces").
     * @property ultimoEscaneoMillis El `ultimoEscaneoMillis` del peor resultado
     *  (para "última vez escaneada hace ...").
     */
    data class UrlDuplicada(
        val resultado: ResultadoAnalisis.ResultadoUrl,
        val urlsLimpiaConsultadas: List<String>,
        val vecesEscaneadaMaxima: Int,
        val ultimoEscaneoMillis: Long
    ) : Estado()

    /**
     * Inferencia completada y un resultado esta listo.
     *
     * [idLocal]: UUID del escaneo persistido en Room cuando el QR contenia
     * una URL. Es `null` cuando el QR NO contenia una URL (path
     * [ResultadoAnalisis.NoUrl], que no persiste). La UI ([AnalisisScreen])
     * usa este id para navegar a DetalleUrl con el id exacto en vez de un
     * match heuristico en el Flow `historial` — ese Flow sufre race con la
     * emision de Room tras INSERT, lo que producía navegacion con id
     * invalido → DetalleUrl "NoEncontrado".
     */
    data class ResultadoListo(
        val resultado: ResultadoAnalisis,
        val idLocal: String? = null
    ) : Estado()

    /** Ocurrio un error durante el analisis. */
    data class Error(val mensaje: String) : Estado()
}

/**
 * Resultado del analisis del pipeline. Ya sea un [ResultadoUrl] (con
 * clasificacion de alerta) o un [NoUrl] cuando el contenido QR no era una
 * URL.
 *
 * Bug C2 (fix): cuando el QR contiene multiples URLs, [ResultadoUrl] modela
 * la **peor** URL (por [ControladorAlerta.NivelAlerta] ordinal:
 * MALICIOSO > SOSPECHOSO > SEGURO), y expone en [ResultadoUrl.urlsAdicionales]
 * la lista completa de URLs analizadas para fines de forense/UI.
 *
 * Cache/Room serializan solo el primario (`urlOriginal`, `urlLimpia`,
 * `probabilidad`, `nivelAlerta`, `delegado`) — `urlsAdicionales` NO se
 * persiste (son derivables re-analizando la URL cruda). Esto evita una
 * migracion Room y mantiene backward-compat con la cache existente.
 */
sealed class ResultadoAnalisis {

    /**
     * Resultado de inferencia para una URL extraida del codigo QR.
     *
     * @property urlOriginal URL cruda extraida (antes de limpiar).
     * @property urlLimpia URL despues de limpiar (protocolo + www quitados).
     * @property probabilidad Probabilidad sigmoid en [0, 1].
     * @property nivelAlerta [ControladorAlerta.NivelAlerta] discreto
     *  (SEGURO/SOSPECHOSO/MALICIOSO).
     * @property delegado El delegado de hardware usado (``"NNAPI"``, ``"GPU"``,
     *  o ``"CPU"``). Bug M8 (fix): en cache hit, este valor se reconstruye
     *  desde el delegado **original** que efectivamente ejecuto la inferencia,
     *  no desde el delegado actual — previene reportes inconsistentes si el
     *  usuario cambia la preferencia entre escaneos.
     * @property urlsAdicionales Resto de URLs encontradas en el mismo codigo
     *  QR, ordenadas de peor a mejor veredicto. Vacio si el QR contenia una
     *  sola URL.
     */
    data class ResultadoUrl(
        val urlOriginal: String,
        val urlLimpia: String,
        val probabilidad: Float,
        val nivelAlerta: ControladorAlerta.NivelAlerta,
        val delegado: String,
        val urlsAdicionales: List<ResultadoUrl> = emptyList()
    ) : ResultadoAnalisis()

    /**
     * El contenido QR no era una URL — no se realizo inferencia.
     *
     * @property valorCrudo Payload original decodificado del QR.
     * @property tipoContenido ``"texto"``, ``"wifi"``, etc.
     */
    data class NoUrl(
        val valorCrudo: String,
        val tipoContenido: String
    ) : ResultadoAnalisis()
}

/**
 * DTO de persistencia (SavedStateHandle de [PipelineViewModel]) para
 * [ResultadoAnalisis.ResultadoUrl] — solo los campos primarios;
 * `urlsAdicionales` es derivable re-analizando.
 *
 * Reemplaza la serializacion pipe-delimited con escape manual `%7C` —
 * kotlinx.serialization ya vive en el classpath.
 */
@Serializable
data class ResultadoUrlDto(
    @SerialName("url_original") val urlOriginal: String,
    @SerialName("url_limpia") val urlLimpia: String,
    val probabilidad: Float,
    @SerialName("nivel_alerta") val nivelAlerta: String,
    val delegado: String = "",
)

fun ResultadoUrlDto.aDominio(): ResultadoAnalisis.ResultadoUrl =
    ResultadoAnalisis.ResultadoUrl(
        urlOriginal = urlOriginal,
        urlLimpia = urlLimpia,
        probabilidad = probabilidad,
        nivelAlerta = ControladorAlerta.NivelAlerta.valueOf(nivelAlerta),
        delegado = delegado
    )

fun ResultadoAnalisis.ResultadoUrl.aDto(): ResultadoUrlDto = ResultadoUrlDto(
    urlOriginal = urlOriginal,
    urlLimpia = urlLimpia,
    probabilidad = probabilidad,
    nivelAlerta = nivelAlerta.name,
    delegado = delegado
)
