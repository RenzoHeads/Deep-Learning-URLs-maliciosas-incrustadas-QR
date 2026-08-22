package com.qrsecurity.detector.ui

import android.util.Log
import androidx.compose.ui.graphics.Color
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.CyberAmbar
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta

/**
 * Modelo de dominio tipado para el `nivelAlerta` de un [EscaneoEntity].
 *
 * Reemplaza los helpers sueltos `colorPorNivel` / `etiquetaAmenazaPorNivel` /
 * `subtituloPorNivel` (antes `internal` en `DetalleUrlScreen.kt`) y los `when`
 * inline re-implementados en `AnalisisAnterioresLineaTiempo.kt` (chip color +
 * etiqueta). Single source of truth para el mapeo `nivelAlerta: String →
 * (color, etiqueta, subtitulo)`.
 *
 * **Por que SOSPECHOSO mapea a [CyberAmbar]** (no [CyberCyan]): antes de la
 * consolidacion habia dos mappings contradictorios en el codebase:
 *  - `colorPorNivel` (viejo, en `DetalleUrlScreen`) devolvia `CyberCyan` para
 *    SOSPECHOSO → se usaba solo en el gauge del verdict card.
 *  - `ChipEstadoUrl` (`DetalleUrlTarjetas`) y `EntradaLineaTiempo`
 *    (`AnalisisAnterioresLineaTiempo`) usaban `CyberAmbar` para SOSPECHOSO.
 * El enum estandariza en `CyberAmbar` (2 de 3 callsites ya lo usaban) — esto
 * tambien armoniza el gauge del verdict card con el chip de estado adyacente.
 *
 * Pre-existente: `etiquetaLineaTiempo` usa "Bloqueada" para MALICIOSO como
 * atajo UX en el timeline. Esa etiqueta NO refleja el flag `urlBloqueada`
 * (que es una accion del usuario separada) — solo refleja el nivelAlerta.
 * Si se quiere precisar, separar la etiqueta del chip de bloqueo real; hoy
 * se preserva el mapping existente por compatibilidad visual.
 */
enum class NivelAlerta(
    val id: String,
    val color: Color,
    /** Etiqueta del verdict card (gauge). "Amenaza alta/moderada | Sin amenazas". */
    val etiquetaAmenaza: String,
    /** Subtitulo del verdict card, debajo del gauge. */
    val subtituloAmenaza: String,
    /** Etiqueta corta usada en el chip del timeline de Analisis Anteriores. */
    val etiquetaLineaTiempo: String,
    /** Etiqueta de la fila del historial (Maliciosa ≠ "Bloqueada" del timeline:
     *  aqui el flag urlBloqueada pinta su propia etiqueta aparte). */
    val etiquetaHistorial: String
) {
    MALICIOSO(
        id = "MALICIOSO",
        color = CyberRojo,
        etiquetaAmenaza = "Amenaza alta",
        subtituloAmenaza = "Phishing · smishing activo",
        etiquetaLineaTiempo = "Bloqueada",
        etiquetaHistorial = "Maliciosa"
    ),
    SOSPECHOSO(
        id = "SOSPECHOSO",
        color = CyberAmbar,
        etiquetaAmenaza = "Amenaza moderada",
        subtituloAmenaza = "Patrón sospechoso detectado",
        etiquetaLineaTiempo = "Sospechosa",
        etiquetaHistorial = "Sospechosa"
    ),
    SEGURO(
        id = "SEGURO",
        color = CyberVerdeAlerta,
        etiquetaAmenaza = "Sin amenazas",
        subtituloAmenaza = "Análisis completado",
        etiquetaLineaTiempo = "Segura",
        etiquetaHistorial = "Segura"
    );

    companion object {
        /**
         * Resuelve el [NivelAlerta] desde el string crudo almacenado en
         * [EscaneoEntity.nivelAlerta]. Cae a [SOSPECHOSO] si el id no
         * matchea ningun valor (no deberia ocurrir — el backend solo emite
         * los 3 ids) y emite [Log.w] para que el fallback sea observable en
         * logcat.
         *
         * Audit fix (fail-safe): antes caia a [SEGURO] ("Sin amenazas",
         * verde) — la direccion de fallo INSEGURA para una app de seguridad:
         * un id desconocido (backend nuevo con niveles extra, corrupcion)
         * mostraba la URL como inofensiva. Fallar hacia SOSPECHOSO
         * preserva la prudencia sin romper el UI.
         */
        fun de(id: String): NivelAlerta = entries.firstOrNull { it.id == id }
            ?: run {
                Log.w("NivelAlerta", "ID desconocido '$id' — fallback a SOSPECHOSO")
                SOSPECHOSO
            }
    }
}

/** Acceso ergonomico al [NivelAlerta] tipado desde un [EscaneoEntity]. */
val EscaneoEntity.nivelAlertaEnum: NivelAlerta
    get() = NivelAlerta.de(nivelAlerta)

/**
 * Convierte la probabilidad cruda del modelo [0,1] al porcentaje de SEGURIDAD
 * [0,100] redondeado — la métrica CANÓNICA de la app (ver [TarjetaVeredicto]:
 * "el número SIEMPRE significa % seguro, más alto = más seguro").
 *
 * Auditoría UI 2 (fix de convención): el timeline de versiones anteriores
 * mostraba el complemento crudo ("18% probabilidad" junto a un chip "Segura")
 * mientras el gauge del detalle de esa misma versión mostraba "82%" — dos
 * números distintos para el mismo escaneo. Ahora todas las superficies
 * derivan de esta única función.
 *
 * WAVE 14 fix (M1): `Math.round` en vez de `.toInt()` para evitar el truncado
 * hacia 0 (p.ej. prob 0.999f ahora muestra "100%" no "99%").
 */
fun pctSeguro(probabilidad: Float): Int =
    Math.round((1f - probabilidad.coerceIn(0f, 1f)) * 100f)

/**
 * Mensaje de usuario para un QR que no contiene una URL (o la contiene
 * demasiado larga). Unico punto de verdad (auditoria frontend v2, E2/B2):
 * antes el mismo if-else con el magic string "url_demasiado_larga" estaba
 * duplicado en HomeScreen y AnalisisScreen.
 *
 * U11: "url_demasiado_larga" antes decia "no contiene una URL" aunque el
 * payload SI era una URL — mensaje contradictorio.
 *
 * @param tipoContenido el discriminator de [ResultadoAnalisis.NoUrl]
 *   producido por el pipeline (p.ej. "url_demasiado_larga").
 */
internal fun mensajeNoUrl(tipoContenido: String): String =
    if (tipoContenido == "url_demasiado_larga") {
        "El QR contiene una URL demasiado larga (máximo 2048 caracteres)"
    } else {
        "El QR no contiene una URL"
    }
