package com.qrsecurity.detector.ui

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
    val etiquetaLineaTiempo: String
) {
    MALICIOSO(
        id = "MALICIOSO",
        color = CyberRojo,
        etiquetaAmenaza = "Amenaza alta",
        subtituloAmenaza = "Phishing · smishing activo",
        etiquetaLineaTiempo = "Bloqueada"
    ),
    SOSPECHOSO(
        id = "SOSPECHOSO",
        color = CyberAmbar,
        etiquetaAmenaza = "Amenaza moderada",
        subtituloAmenaza = "Patrón sospechoso detectado",
        etiquetaLineaTiempo = "Sospechosa"
    ),
    SEGURO(
        id = "SEGURO",
        color = CyberVerdeAlerta,
        etiquetaAmenaza = "Sin amenazas",
        subtituloAmenaza = "Análisis completado",
        etiquetaLineaTiempo = "Segura"
    );

    companion object {
        /**
         * Resuelve el [NivelAlerta] desde el string crudo almacenado en
         * [EscaneoEntity.nivelAlerta]. Cae a [SEGURO] si el id no matchea
         * ninguno (no deberia ocurrir — el backend solo emite los 3 ids).
         */
        fun de(id: String): NivelAlerta = entries.firstOrNull { it.id == id } ?: SEGURO
    }
}

/** Acceso ergonomico al [NivelAlerta] tipado desde un [EscaneoEntity]. */
val EscaneoEntity.nivelAlertaEnum: NivelAlerta
    get() = NivelAlerta.de(nivelAlerta)

/**
 * Convierte la probabilidad cruda del modelo [0,1] a un entero [0,100]
 * redondeado (no truncado). Centraliza `Math.round(probabilidad * 100f)`
 * que aparecia duplicado en `DetalleUrlTarjetas`,
 * `DetalleVersionAntiguaContenido` y `AnalisisAnterioresLineaTiempo`.
 *
 * WAVE 14 fix (M1): `Math.round` en vez de `.toInt()` para evitar el truncado
 * hacia 0 (p.ej. prob 0.999f ahora muestra "100%" no "99%").
 */
fun probabilidadPct(probabilidad: Float): Int = Math.round(probabilidad * 100f)
