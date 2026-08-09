package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pantalla de Historial (Pencil frame fvsVa).
 *
 * F3.3: implementacion del layout de Pencil fvsVa. La firma NO debe cambiar.
 *
 * Muestra la lista de escaneos con filtros (Todas, Seguras, Sospechosas,
 * Bloqueadas). Al tocar un item, navega a DETALLE_URL/{id} via [onVerDetalle].
 * Wire a [DatosTabsViewModel].
 *
 * @param datosViewModel VM compartido con los Flows de historial.
 * @param onEscanear Callback para navegar a la pantalla de analisis.
 * @param onVerDetalle Callback con el id del escaneo (navega a DETALLE_URL).
 * @param onMensaje Callback para mostrar snackbars.
 */
@Composable
fun PantallaHistorial(
    datosViewModel: DatosTabsViewModel,
    onEscanear: () -> Unit,
    onVerDetalle: (String) -> Unit = {},
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val historialTodos by datosViewModel.historialTodos.collectAsStateWithLifecycle()
    val urlsBloqueadas by datosViewModel.urlsBloqueadas.collectAsStateWithLifecycle()
    val syncEnCurso by datosViewModel.syncEnCurso.collectAsStateWithLifecycle()

    var busqueda by rememberSaveable { mutableStateOf("") }
    var filtroSeleccionado by rememberSaveable { mutableStateOf("TODAS") }

    val bloqueadasUrls = remember(urlsBloqueadas) {
        urlsBloqueadas.map { it.url }.toSet()
    }

    val totalTodos = historialTodos.size
    val totalSeguras = historialTodos.count { it.nivelAlerta == "SEGURO" }
    val totalSospechosas = historialTodos.count { it.nivelAlerta == "SOSPECHOSO" }
    val totalBloqueadas = historialTodos.count { it.urlLimpia in bloqueadasUrls }

    val filtradas = remember(historialTodos, filtroSeleccionado, busqueda, bloqueadasUrls) {
        val porFiltro: List<EscaneoEntity> = when (filtroSeleccionado) {
            "SEGURAS" -> historialTodos.filter { it.nivelAlerta == "SEGURO" }
            "SOSPECHOSAS" -> historialTodos.filter { it.nivelAlerta == "SOSPECHOSO" }
            "BLOQUEADAS" -> historialTodos.filter { it.urlLimpia in bloqueadasUrls }
            else -> historialTodos
        }
        if (busqueda.isBlank()) porFiltro
        else porFiltro.filter { it.urlLimpia.contains(busqueda, ignoreCase = true) }
    }

    val grupos = remember(filtradas) { agruparPorFecha(filtradas) }

    val segurosPct = if (totalTodos > 0) (100.0 * totalSeguras / totalTodos).toInt() else 0
    val escaneosFormateado = String.format(Locale.getDefault(), "%,d", totalTodos)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Espaciado.xxl, vertical = Espaciado.xxl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
        ) {
            // ─── Header ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
                    Text(
                        text = "Historial",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextoPrincipal
                    )
                    Text(
                        text = "Todos tus escaneos, con su veredicto",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextoSecundario
                    )
                }
                if (syncEnCurso) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Espaciado.xs),
                        modifier = Modifier
                            .background(CyberGlassAlto, RoundedCornerShape(RadioBorde.lg))
                            .padding(horizontal = Espaciado.md, vertical = Espaciado.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = "Sincronizando",
                            tint = CyberCyan,
                            modifier = Modifier.size(TamanosIcono.estandar)
                        )
                        Text(
                            text = "Sincronizando...",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberTextoSecundario
                        )
                    }
                }
            }

            // ─── Search Bar ───
            OutlinedTextField(
                value = busqueda,
                onValueChange = { busqueda = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar código o URL") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(TamanosIcono.estandar)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(RadioBorde.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CyberGlass,
                    unfocusedContainerColor = CyberGlass,
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    cursorColor = CyberCyan,
                    focusedTextColor = CyberTextoPrincipal,
                    unfocusedTextColor = CyberTextoPrincipal
                )
            )

            // ─── Filter Bar ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                FilterChip(
                    selected = filtroSeleccionado == "TODAS",
                    onClick = { filtroSeleccionado = "TODAS" },
                    label = { Text("Todas $totalTodos") },
                    colors = chipColoresFiltro()
                )
                FilterChip(
                    selected = filtroSeleccionado == "SEGURAS",
                    onClick = { filtroSeleccionado = "SEGURAS" },
                    label = { Text("Seguras $totalSeguras") },
                    colors = chipColoresFiltro()
                )
                FilterChip(
                    selected = filtroSeleccionado == "SOSPECHOSAS",
                    onClick = { filtroSeleccionado = "SOSPECHOSAS" },
                    label = { Text("Sospechosas $totalSospechosas") },
                    colors = chipColoresFiltro()
                )
                FilterChip(
                    selected = filtroSeleccionado == "BLOQUEADAS",
                    onClick = { filtroSeleccionado = "BLOQUEADAS" },
                    label = { Text("Bloqueadas $totalBloqueadas") },
                    colors = chipColoresFiltro()
                )
            }

            // ─── Summary Chips Row ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                ChipResumen("$escaneosFormateado escaneos", CyberCyan)
                ChipResumen("${urlsBloqueadas.size} bloqueados", CyberRojo)
                ChipResumen("$segurosPct% seguros", CyberVerdeAlerta)
            }

            // ─── List / Empty ───
            if (filtradas.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Espaciado.giganteM),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Espaciado.md)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SearchOff,
                        contentDescription = null,
                        tint = CyberTextoSecundario,
                        modifier = Modifier.size(TamanosIcono.mediano)
                    )
                    Text(
                        text = "No hay escaneos para mostrar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextoSecundario
                    )
                    Button(
                        onClick = onEscanear,
                        shape = RoundedCornerShape(RadioBorde.lg),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberCyan,
                            contentColor = CyberFondo
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(TamanosIcono.estandar)
                        )
                        Spacer(modifier = Modifier.width(Espaciado.sm))
                        Text("Escanear código")
                    }
                }
            } else {
                grupos.forEach { grupo ->
                    Column(verticalArrangement = Arrangement.spacedBy(Espaciado.sm)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = grupo.titulo,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyberTextoSecundario
                            )
                            Text(
                                text = "${grupo.escaneos.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTextoSecundario
                            )
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(RadioBorde.xl),
                            colors = CardDefaults.cardColors(containerColor = CyberGlass),
                            elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                grupo.escaneos.forEachIndexed { index, escaneo ->
                                    val bloqueada = escaneo.urlLimpia in bloqueadasUrls
                                    FilaEscaneo(
                                        escaneo = escaneo,
                                        bloqueada = bloqueada,
                                        onVerDetalle = onVerDetalle,
                                        onMensaje = onMensaje,
                                    )
                                    if (index < grupo.escaneos.lastIndex) {
                                        HorizontalDivider(
                                            color = CyberGlassBorde,
                                            thickness = 1.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ─── Unlock Hint ───
                Text(
                    text = "Para desbloquear una URL, abre su detalle y toca el candado",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextoSecundario,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Espaciado.md)
                )
            }
        }

        // ─── FAB ───
        FloatingActionButton(
            onClick = onEscanear,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Espaciado.xxl),
            containerColor = CyberCyan,
            contentColor = CyberFondo
        ) {
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = "Escanear código"
            )
        }
    }
}

// ── Composables y helpers privados ──

@Composable
private fun chipColoresFiltro() = FilterChipDefaults.filterChipColors(
    containerColor = CyberGlass,
    labelColor = CyberTextoSecundario,
    selectedContainerColor = CyberCyan,
    selectedLabelColor = CyberFondo
)

@Composable
private fun ChipResumen(texto: String, colorPunto: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
        modifier = Modifier
            .background(CyberGlassAlto, RoundedCornerShape(RadioBorde.lg))
            .padding(horizontal = Espaciado.md, vertical = Espaciado.sm)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colorPunto)
        )
        Text(
            text = texto,
            style = MaterialTheme.typography.labelMedium,
            color = CyberTextoSecundario
        )
    }
}

@Composable
private fun FilaEscaneo(
    escaneo: EscaneoEntity,
    bloqueada: Boolean,
    onVerDetalle: (String) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    val (icono, color) = when (escaneo.nivelAlerta) {
        "SEGURO" -> Icons.Filled.CheckCircle to CyberVerdeAlerta
        "SOSPECHOSO" -> Icons.Filled.Warning to CyberAmbar
        else -> Icons.Filled.Block to CyberRojo
    }
    val etiqueta = if (bloqueada) {
        "Bloqueada"
    } else {
        when (escaneo.nivelAlerta) {
            "SEGURO" -> "Segura"
            "SOSPECHOSO" -> "Sospechosa"
            else -> "Maliciosa"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (bloqueada) onMensaje(TipoMensaje.INFO, "Abre el detalle para desbloquear esta URL")
                onVerDetalle(escaneo.id)
            }
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        // Status Tile
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icono,
                contentDescription = etiqueta,
                tint = color,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
        }
        // Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Text(
                text = escaneo.urlLimpia,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CyberTextoPrincipal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = etiqueta,
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextoSecundario
            )
        }
        // Time + Unlock Pill
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Text(
                text = tiempoRelativo(escaneo.creadoEnMillis),
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextoSecundario
            )
            if (bloqueada) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Espaciado.xs),
                    modifier = Modifier
                        .background(CyberGlassAlto, RoundedCornerShape(RadioBorde.sm))
                        .padding(horizontal = Espaciado.sm, vertical = Espaciado.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = "Desbloquear",
                        tint = CyberCyan,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

private data class GrupoHistorial(val titulo: String, val escaneos: List<EscaneoEntity>)

private fun agruparPorFecha(
    escaneos: List<EscaneoEntity>,
    ahora: Long = System.currentTimeMillis()
): List<GrupoHistorial> {
    val ordenados = escaneos.sortedByDescending { it.creadoEnMillis }
    val hoy = ordenados.filter { diasDeDiferencia(it.creadoEnMillis, ahora) == 0L }
    val ayer = ordenados.filter { diasDeDiferencia(it.creadoEnMillis, ahora) == 1L }
    val anteriores = ordenados.filter { diasDeDiferencia(it.creadoEnMillis, ahora) >= 2L }
    val resultado = mutableListOf<GrupoHistorial>()
    if (hoy.isNotEmpty()) resultado += GrupoHistorial("Hoy", hoy)
    if (ayer.isNotEmpty()) resultado += GrupoHistorial("Ayer", ayer)
    if (anteriores.isNotEmpty()) resultado += GrupoHistorial("Anteriores", anteriores)
    return resultado
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

private fun tiempoRelativo(millis: Long, ahora: Long = System.currentTimeMillis()): String {
    val delta = ahora - millis
    if (delta < 0) return "ahora"
    val dias = diasDeDiferencia(millis, ahora)
    if (dias <= 0L) {
        val minutos = TimeUnit.MILLISECONDS.toMinutes(delta)
        val horas = TimeUnit.MILLISECONDS.toHours(delta)
        return when {
            minutos < 1 -> "ahora"
            minutos < 60 -> "hace $minutos min"
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
