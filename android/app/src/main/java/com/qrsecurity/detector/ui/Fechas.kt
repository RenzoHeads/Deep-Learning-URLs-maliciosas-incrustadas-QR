package com.qrsecurity.detector.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Helpers de formato de fecha compartidos por las pantallas de Detalle,
 * Analisis Anteriores e Historial.
 *
 * Consolidacion thermo-nuclear (Blockers 1+2) — antes estaban dispersos TRES
 * implementaciones del mismo algoritmo:
 *  - `formatoFechaEscaneo` en `DetalleVersionAntiguaScreen.kt`
 *  - `fechaRelativa` + `diasDeDiferencia` (antigua version) en
 *    `AnalisisAnterioresLineaTiempo.kt`
 *  - `tiempoRelativo` + `diasDeDiferenciaHistorial` en `HistorialScreen.kt` /
 *    `DatosTabsViewModel.kt` — duplicaban la logica de `fechaRelativa` /
 *    `diasDeDiferencia` con unicamente el extra de precision sub-dia
 *    ("hace N min" / "hace N h") y un seam de testing `ahora`.
 *
 * Unificar absorbe esos dos delta en la version canonica: `fechaRelativa`
 * ahora cubre sub-dia, y ambas funciones exponen `ahora` con default —
 * los callers de pantalla no cambian, los tests pueden inyectar "ahora".
 * Centralizar evita que las tres pantallas deriven en formatos distintos
 * (p.ej. "11 ago 2026" vs "11-Aug-2026") sin intencion.
 */

/**
 * Formato de fecha del escaneo — "dd MMM yyyy, HH:mm" (ej: "11 ago 2026, 14:30").
 * Usado para distinguir visualmente entre versiones del mismo escaneo.
 */
internal fun formatoFechaEscaneo(creadoEnMillis: Long): String {
    val formato = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("es"))
    return formato.format(Date(creadoEnMillis))
}

/** Hora del escaneo en formato "HH:mm" (24h, locale del dispositivo). */
internal fun formatoHora(millis: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

/**
 * Fecha relativa legible con precision sub-dia:
 *  - "ahora" / "hace N min" / "hace N h" si el escaneo ocurrio hoy
 *  - "ayer" si fue ayer
 *  - "hace N dias" (2..30), "hace N meses" (31..365), "hace N anos" (>365)
 *
 * La rama sub-dia reemplaza a `tiempoRelativo` (HistorialScreen) que solo
 * servia para timestamps finos de fila de historial. El timeline de Analisis
 * Anteriores tambien se beneficia: para reescaneos recientes (~min), el
 * texto "hace N min" es mas informativo que el antiguo "hoy".
 *
 * @param ahora Seam de testing; en runtime usa [System.currentTimeMillis].
 */
internal fun fechaRelativa(
    millis: Long,
    ahora: Long = System.currentTimeMillis()
): String {
    val dias = diasDeDiferencia(millis, ahora)
    if (dias <= 0L) {
        // Precision sub-dia — solo cuando el escaneo es de hoy.
        val delta = ahora - millis
        if (delta < 0L) return "ahora"
        val minutos = TimeUnit.MILLISECONDS.toMinutes(delta)
        val horas = TimeUnit.MILLISECONDS.toHours(delta)
        return when {
            minutos < 1L -> "ahora"
            minutos < 60L -> "hace $minutos min"
            else -> "hace $horas h"
        }
    }
    if (dias == 1L) return "ayer"
    if (dias in 2L..30L) return "hace $dias días"
    if (dias in 31L..365L) {
        val meses = (dias / 30).toInt()
        return "hace $meses ${if (meses == 1) "mes" else "meses"}"
    }
    val anos = (dias / 365).toInt()
    return "hace $anos ${if (anos == 1) "año" else "años"}"
}

/**
 * Diferencia en dias calendario entre [millis] y [ahora] (default = ahora).
 * Normaliza ambas a medianoche (descarta HOUR/MINUTE/SECOND/MILLISECOND)
 * para que la comparacion sea entre fechas, no instantes.
 *
 * `ahora` permite a los tests inyectar un reloj determinista. Antes este
 * seam era el unico diferencial vs `diasDeDiferenciaHistorial` (que vivia
 * en `DatosTabsViewModel.kt`); al exponerse aqui, ese helper desaparece.
 */
internal fun diasDeDiferencia(
    millis: Long,
    ahora: Long = System.currentTimeMillis()
): Long {
    val calAhora = Calendar.getInstance().apply {
        timeInMillis = ahora
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val calEnt = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return TimeUnit.MILLISECONDS.toDays(calAhora.timeInMillis - calEnt.timeInMillis)
}
