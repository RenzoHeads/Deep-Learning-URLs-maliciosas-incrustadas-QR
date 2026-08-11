package com.qrsecurity.detector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.CyberAmbar
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pantalla de Analisis Anteriores / Versiones anteriores de una URL
 * (Pencil frame Lb1HV).
 *
 * F3.3: implementacion del layout de Pencil Lb1HV. La firma NO debe cambiar.
 *
 * Muestra la lista de versiones anteriores de una URL (reescaneos) con
 * notas de analisis. Wire a [AnalisisAnterioresViewModel].
 *
 * Rediseño F3: timeline visual con rail vertical + dots de color por
 * veredicto, versiones con badge V1/V2, % probabilidad, y empty state
 * rico con ilustración vectorial.
 *
 * BUG #2 fix (audit): migrado de `Column { verticalScroll { forEachIndexed {} } }`
 * a `LazyColumn { itemsIndexed(...) }` con `key = { it.id }`. La version
 * eager instanciaba todos los composables de la lista en el main thread —
 * con miles de reescaneos de una misma URL (caso extremo en datasets
 * grandes) esto podia causar ANR y riesgo de OOM. LazyColumn virtualiza:
 * solo compone los items visibles + pequeno buffer.
 *
 * @param urlLimpia URL limpia del escaneo principal (nav argument).
 * @param idActual Id del escaneo principal (nav argument).
 * @param onVolver Callback para volver a la pantalla anterior.
 * @param onEscanear Callback para reanalizar la URL.
 * @param viewModel VM de analisis anteriores (compartido a nivel NavGuardian).
 */
@Composable
fun PantallaAnalisisAnteriores(
    urlLimpia: String,
    idActual: String,
    onVolver: () -> Unit,
    onEscanear: () -> Unit,
    onVerDetalle: (String) -> Unit = {},
    viewModel: AnalisisAnterioresViewModel
) {
    LaunchedEffect(urlLimpia, idActual) {
        viewModel.cargarAnalisisAnteriores(urlLimpia, idActual)
    }

    val estado by viewModel.estadoAnalisisAnteriores.collectAsStateWithLifecycle()
    val syncEnCurso by viewModel.syncEnCurso.collectAsStateWithLifecycle()

    val dataCoincide = estado.url == urlLimpia && estado.id == idActual
    val listaOrdenada = remember(estado.lista) {
        estado.lista.sortedByDescending { it.creadoEnMillis }
    }
    val total = estado.total

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Espaciado.lg,
            vertical = Espaciado.lg
        ),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ─── Glass Pill Back Button ───
        item(key = "back_row") {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(CyberGlass)
                    .clickable(onClick = onVolver)
                    .padding(horizontal = Espaciado.md, vertical = Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = CyberTextoSecundario,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Text(
                    text = "Volver",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )
            }
        }

        // ─── Header ───
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Versiones del análisis",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextoPrincipal,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Espaciado.xs)
                    ) {
                        if (syncEnCurso) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = CyberCyan,
                                strokeWidth = 2.dp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(CyberGlassAlto, RoundedCornerShape(RadioBorde.lg))
                                .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
                        ) {
                            Text(
                                text = "$total análisis",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTextoSecundario
                            )
                        }
                    }
                }
                Text(
                    text = "Historial de análisis de esta URL",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )
            }
        }

        // ─── URL Summary Card ───
        item(key = "url_summary") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(RadioBorde.xxl))
                    .background(CyberGlass)
                    .border(
                        width = 1.dp,
                        color = CyberGlassBorde,
                        shape = RoundedCornerShape(RadioBorde.xxl)
                    )
                    .padding(Espaciado.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(TamanosIcono.mediano)
                        .clip(CircleShape)
                        .background(CyberGlassAlto),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCode2,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(TamanosIcono.estandar)
                    )
                }
                Text(
                    text = urlLimpia,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = CyberTextoPrincipal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ─── History Section ───
        item(key = "history_label") {
            Text(
                text = "Historial",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = CyberTextoSecundario
            )
        }

        when {
            !dataCoincide -> {
                item(key = "loading") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Espaciado.gigante),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Espaciado.md)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = CyberCyan,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Cargando...",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextoSecundario
                        )
                    }
                }
            }
            listaOrdenada.isEmpty() -> {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Espaciado.gigante),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
                    ) {
                        // Ilustración: círculo glass con History icon
                        Box(
                            modifier = Modifier
                                .size(TamanosIcono.heroContenedor)
                                .clip(CircleShape)
                                .background(CyberGlass),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = CyberTextoSecundario,
                                modifier = Modifier.size(TamanosIcono.grande)
                            )
                        }
                        Text(
                            text = "No hay análisis anteriores",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = CyberTextoPrincipal
                        )
                        Text(
                            text = "Esta URL solo tiene una versión.\nEscanea de nuevo para crear un historial.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextoSecundario,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
            else -> {
                // BUG #2 fix: cada entrada es un item individual del LazyColumn
                // con key=id → virtualizacion completa. La version eager instanciaba
                // todos los composables EntradaLineaTiempo en el main thread.
                itemsIndexed(
                    items = listaOrdenada,
                    key = { _, escaneo -> escaneo.id }
                ) { index, escaneo ->
                    val version = total - index
                    val esUltimo = index == listaOrdenada.lastIndex
                    EntradaLineaTiempo(
                        escaneo = escaneo,
                        version = version,
                        esUltimo = esUltimo,
                        onClick = { onVerDetalle(escaneo.id) }
                    )
                }
            }
        }

        // ─── CTA Section ───
        item(key = "cta") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                OutlinedButton(
                    onClick = onEscanear,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TamanosToque.boton),
                    shape = RoundedCornerShape(RadioBorde.lg),
                    border = BorderStroke(Elevacion.sutil, CyberCyan),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = CyberGlass,
                        contentColor = CyberCyan
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(TamanosIcono.estandar)
                    )
                    Spacer(modifier = Modifier.size(Espaciado.sm))
                    Text(
                        text = "Reanalizar ahora",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Un escaneo nuevo tarda ~0,3 s",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTextoSecundario
                )
            }
        }
    }
}

// ── Composables y helpers privados ──

/**
 * Entrada del timeline — dot de color + rail vertical + glass card
 * con version badge (V1, V2...), % probabilidad, veredicto chip y
 * nota de análisis.
 */
@Composable
private fun EntradaLineaTiempo(
    escaneo: EscaneoEntity,
    version: Int,
    esUltimo: Boolean,
    onClick: () -> Unit = {}
) {
    val color = when (escaneo.nivelAlerta) {
        "SEGURO" -> CyberVerdeAlerta
        "SOSPECHOSO" -> CyberAmbar
        else -> CyberRojo
    }
    val etiqueta = when (escaneo.nivelAlerta) {
        "SEGURO" -> "Segura"
        "SOSPECHOSO" -> "Sospechosa"
        else -> "Bloqueada"
    }
    val valorPct = Math.round(escaneo.probabilidad * 100f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        // ─── Timeline Rail (dot + connector) ───
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            // Dot de color con glow sutil
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            // Connector vertical (no en el último item)
            if (!esUltimo) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(48.dp)
                        .background(CyberGlassBorde)
                )
            }
        }

        // ─── Analysis Card ───
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(RadioBorde.xl),
            colors = CardDefaults.cardColors(containerColor = CyberGlass),
            elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Espaciado.lg),
                verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                // Row 1: Version badge + verdict chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Version badge: "V1", "V2", etc.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(RadioBorde.sm))
                            .background(CyberGlassAlto)
                            .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
                    ) {
                        Text(
                            text = "V$version",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberTextoPrincipal
                        )
                    }
                    // Verdict chip
                    Box(
                        modifier = Modifier
                            .background(color.copy(alpha = 0.18f), RoundedCornerShape(RadioBorde.sm))
                            .padding(horizontal = Espaciado.sm, vertical = Espaciado.xs)
                    ) {
                        Text(
                            text = etiqueta,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                    }
                }

                // Row 2: % probabilidad + timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$valorPct% probabilidad",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CyberTextoPrincipal
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Espaciado.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = CyberTextoSecundario,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = fechaRelativa(escaneo.creadoEnMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberTextoSecundario
                        )
                    }
                }

                // Row 3:Hora exacta del análisis
                Text(
                    text = "Análisis refrescado a las ${formatoHora(escaneo.creadoEnMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTextoSecundario
                )

                // Row 4: Note (notasAnalisis si no es null ni vacio)
                val nota = escaneo.notasAnalisis
                if (!nota.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(RadioBorde.sm))
                            .background(CyberGlassAlto)
                            .padding(Espaciado.md)
                    ) {
                        Text(
                            text = nota,
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextoSecundario
                        )
                    }
                }
            }
        }
    }
}

private fun fechaRelativa(millis: Long, ahora: Long = System.currentTimeMillis()): String {
    val dias = diasDeDiferencia(millis, ahora)
    if (dias <= 0L) return "hoy"
    if (dias == 1L) return "ayer"
    if (dias in 2L..30L) return "hace $dias días"
    if (dias in 31L..365L) {
        val meses = (dias / 30).toInt()
        return "hace $meses ${if (meses == 1) "mes" else "meses"}"
    }
    val anos = (dias / 365).toInt()
    return "hace $anos ${if (anos == 1) "año" else "años"}"
}

private fun diasDeDiferencia(millis: Long, ahora: Long = System.currentTimeMillis()): Long {
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

private fun formatoHora(millis: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}
