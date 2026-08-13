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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.util.Locale

/**
 * Pantalla de Historial (Pencil frame fvsVa).
 *
 * F3.3: implementacion del layout de Pencil fvsVa. La firma NO debe cambiar.
 *
 * Muestra la lista de escaneos con filtros (Todas, Seguras, Sospechosas,
 * Bloqueadas). Al tocar un item, navega a DETALLE_URL/{id} via [onVerDetalle].
 * Wire a [DatosTabsViewModel].
 *
 * BUG #2 fix (audit): migrado de `Column { verticalScroll { forEach {} } }`
 * a `LazyColumn { itemsIndexed(...) }` con `key = { it.id }`. La version
 * eager instanciaba todos los composables de la lista en el main thread —
 * con miles de URLs en el historial esto causaba ANR (>5s en main thread) y
 * riesgo de OutOfMemoryError (~5-10 KB por composable × N filas, sin
 * recycling). LazyColumn virtualiza: solo compone los items visibles + un
 * pequeno buffer, y dispone los que salen del viewport.
 *
 * Cambio visual: cada fila de escaneo ahora se envuelve en su propio Card
 * con fondo cyber-glass y esquinas redondeadas (en vez de un solo Card por
 * grupo con todas las filas y divisores internos). Los headers de grupo
 * ("Hoy", "Ayer", "Anteriores") se preservan como items individuales del
 * LazyColumn — virtualizacion completa incluso dentro de un mismo grupo
 * (clave para usuarios con miles de URLs todas agrupadas en "Anteriores").
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
    val historialUiState by datosViewModel.historialUiState.collectAsStateWithLifecycle()
    val urlsBloqueadas by datosViewModel.urlsBloqueadas.collectAsStateWithLifecycle()
    val syncEnCurso by datosViewModel.syncEnCurso.collectAsStateWithLifecycle()
    val busqueda by datosViewModel.busquedaHistorial.collectAsStateWithLifecycle()
    val filtroSeleccionado by datosViewModel.filtroHistorial.collectAsStateWithLifecycle()

    val bloqueadasUrls = remember(urlsBloqueadas) {
        urlsBloqueadas.map { it.url }.toSet()
    }

    // V-1 fix: campos de historialUiState son nullable Int? — null = cargando
    // (la UI muestra "—"), 0 = realmente cero. Esto elimina el flash de
    // "0 escaneos" / "0% seguros" que aparecia antes de que Room emitiera
    // los datos reales.
    val grupos = historialUiState.grupos
    val totalTodos = historialUiState.totalTodos
    val totalSeguras = historialUiState.totalSeguras
    val totalSospechosas = historialUiState.totalSospechosas
    val totalBloqueadas = historialUiState.totalBloqueadas
    val segurosPct = historialUiState.segurosPct
    val escaneosFormateado = totalTodos?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "—"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Espaciado.lg,
                vertical = Espaciado.lg
            ),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
        ) {
            // ─── Header ───
            item(key = "header") {
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
            }

            // ─── Search Bar ───
            item(key = "search") {
                OutlinedTextField(
                    value = busqueda,
                    onValueChange = datosViewModel::actualizarBusquedaHistorial,
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
            }

            // ─── Filter Bar ───
            item(key = "filter_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
                ) {
                    FilterChip(
                        selected = filtroSeleccionado == "TODAS",
                        onClick = { datosViewModel.actualizarFiltroHistorial("TODAS") },
                        label = { Text("Todas ${totalTodos ?: "—"}") },
                        colors = chipColoresFiltro()
                    )
                    FilterChip(
                        selected = filtroSeleccionado == "SEGURAS",
                        onClick = { datosViewModel.actualizarFiltroHistorial("SEGURAS") },
                        label = { Text("Seguras ${totalSeguras ?: "—"}") },
                        colors = chipColoresFiltro()
                    )
                    FilterChip(
                        selected = filtroSeleccionado == "SOSPECHOSAS",
                        onClick = { datosViewModel.actualizarFiltroHistorial("SOSPECHOSAS") },
                        label = { Text("Sospechosas ${totalSospechosas ?: "—"}") },
                        colors = chipColoresFiltro()
                    )
                    FilterChip(
                        selected = filtroSeleccionado == "BLOQUEADAS",
                        onClick = { datosViewModel.actualizarFiltroHistorial("BLOQUEADAS") },
                        label = { Text("Bloqueadas ${totalBloqueadas ?: "—"}") },
                        colors = chipColoresFiltro()
                    )
                }
            }

            // ─── Summary Chips Row ───
            item(key = "summary_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
                ) {
                    ChipResumen("$escaneosFormateado escaneos", CyberCyan)
                    ChipResumen("${urlsBloqueadas.size} bloqueados", CyberRojo)
                    ChipResumen(
                        segurosPct?.let { "$it% seguros" } ?: "— seguros",
                        CyberVerdeAlerta
                    )
                }
            }

            // ─── List / Empty ───
            // V-1 fix: solo mostramos el estado vacio cuando los datos estan
            // cargados (totalTodos != null). Cuando totalTodos es null (cargando),
            // grupos es emptyList() pero NO mostramos "No hay escaneos" — evita
            // flash del empty-state durante el sub-frame de carga.
            if (historialUiState.totalTodos != null && grupos.isEmpty()) {
                item(key = "empty_state") {
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
                }
            } else {
                // BUG #2 fix: cada grupo se particiona en header + items individuales.
                // LazyColumn virtualiza los items individuales (solo compone los
                // visibles + buffer). Con miles de filas en "Anteriores", solo
                // las visibles en pantalla ocupan memoria/computation — el resto
                // se dispone al salir del viewport. `key = { it.id }` permite al
                // recycler identificar items movidos entre grupos/ordenes sin
                // recomponerlos desde cero (mantiene estado y animaciones).
                grupos.forEach { grupo ->
                    item(key = "grp_header_${grupo.titulo}") {
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
                    }
                    itemsIndexed(
                        items = grupo.escaneos,
                        key = { _, escaneo -> escaneo.id }
                    ) { _, escaneo ->
                        val bloqueada = escaneo.urlLimpia in bloqueadasUrls
                        // Cada fila obtiene su propio Card cyber-glass con esquinas
                        // redondeadas — reemplaza al Card unico por grupo que
                        // instanciaba todas las filas simultaneamente. Visual:
                        // lista de cards individuales agrupadas por header.
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(RadioBorde.xl),
                            colors = CardDefaults.cardColors(containerColor = CyberGlass),
                            elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
                        ) {
                            FilaEscaneo(
                                escaneo = escaneo,
                                bloqueada = bloqueada,
                                onVerDetalle = onVerDetalle,
                                onMensaje = onMensaje,
                            )
                        }
                    }
                }

                // ─── Unlock Hint ───
                item(key = "unlock_hint") {
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
        }

        // ─── FAB ───
        FloatingActionButton(
            onClick = onEscanear,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Espaciado.lg),
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
                .size(TamanosIcono.mediano)
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
                text = fechaRelativa(escaneo.creadoEnMillis),
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

