package com.qrsecurity.detector.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
 *
 * Audit A1 fix: antes cada invocacion construia un `SimpleDateFormat` (caro —
 * carga simbolos ICU) y `diasDeDiferencia` instanciaba 2 `Calendar` por
 * llamada. Durante un fling del Historial se crean decenas de formatters por
 * segundo → GC churn y frames perdidos. Ahora los formatters son `val`
 * top-level (`DateTimeFormatter` es thread-safe) y la aritmetica de dias usa
 * epoch-days sin instanciar Calendar. Misma API publica y output identico
 * (los tests existentes de `DatosTabsViewModelTest` computan el valor
 * esperado con la misma funcion, por lo que su output se preserva).
 */

/**
 * Formateadores thread-safe reutilizados entre invocaciones.
 *
 * `DateTimeFormatter` es inmutable una vez construido, por lo que compartir
 * un val top-level es seguro aun bajo concurrencia desde multiples
 * composable que recomponen en paralelo. Se usa `Locale.getDefault()` para
 * preservar el output previo del `SimpleDateFormat(..., Locale.getDefault())`
 * — los meses abreviados respetan el locale del dispositivo ("ago" en es,
 * "Aug" en en).
 *
 * Locale capturado al construir: si el usuario cambia de locale en runtime
 * (raro) el formatter no se actualiza hasta reiniciar el proceso. El
 * `SimpleDateFormat` anterior tenia el mismo comportamiento (locale fijado
 * en el constructor), asi que no es una regression.
 */
private val FORMATO_FECHA_ESCANEO: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.getDefault())
private val FORMATO_HORA: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val FORMATO_FECHA_CORTA: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())

/**
 * Formato de fecha del escaneo — "dd MMM yyyy, HH:mm" (ej: "11 ago 2026, 14:30").
 * Usado para distinguir visualmente entre versiones del mismo escaneo.
 *
 * Audit fix (locale): ambas funciones usan el locale del dispositivo —
 * antes esta fijaba `Locale("es")` mientras [formatoHora] usaba el default,
 * produciendo meses mezclados de idioma cuando el dispositivo no estaba
 * en espanol.
 */
internal fun formatoFechaEscaneo(creadoEnMillis: Long): String {
    val zoned = Instant.ofEpochMilli(creadoEnMillis).atZone(ZoneId.systemDefault())
    return FORMATO_FECHA_ESCANEO.format(zoned)
}

/** Hora del escaneo en formato "HH:mm" (24h, locale del dispositivo). */
internal fun formatoHora(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return FORMATO_HORA.format(zoned)
}

/**
 * Fecha calendario corta — "dd/MM/yyyy" (ej: "14/07/2026").
 * Usada como título de grupo del Historial y en los contexts donde la
 * dimensión temporal importa más que la relativa ("Anteriores" no comunica
 * nada concreto cuando el dato de fecha ya existe en la fila).
 */
internal fun formatoFechaCorta(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return FORMATO_FECHA_CORTA.format(zoned)
}

/**
 * Fecha + hora compacta — "dd/MM/yyyy · HH:mm" (ej: "14/07/2026 · 18:42").
 * Metadata de "Analizado:" en TarjetaUrl y en las entradas del timeline
 * de versiones anteriores.
 */
internal fun formatoFechaHoraCorta(millis: Long): String {
    return "${formatoFechaCorta(millis)} · ${formatoHora(millis)}"
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
 *
 * Audit A1 fix: antes instanciaba dos `Calendar` por invocacion. Ahora usa
 * aritmetica de epoch-days: convertir millis → LocalDate (cero asignaciones
 * de Calendar) y restar `toEpochDay()`. El calculo es O(1) por invocacion
 * y mantiene el comportamiento de la version Calendar:
 *  - Fechas futuras (reloj del device atrasado vs servidor) devuelven <= 0,
 *    igual que `Calendar` con HOUR_OF_DAY=0 (cubre el edge case "Hoy" de
 *    `agruparHistorialPorFecha`).
 *  - Mantiene la zona horaria del dispositivo via `ZoneId.systemDefault()`
 *    evaluado por llamada (respeta cambios de zona en runtime) — el JVM
 *    cachea el lookup, asi que esencialmente es gratis.
 */
internal fun diasDeDiferencia(
    millis: Long,
    ahora: Long = System.currentTimeMillis()
): Long {
    val fechaEscaneo = Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val fechaActual = Instant.ofEpochMilli(ahora)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return fechaActual.toEpochDay() - fechaEscaneo.toEpochDay()
}
