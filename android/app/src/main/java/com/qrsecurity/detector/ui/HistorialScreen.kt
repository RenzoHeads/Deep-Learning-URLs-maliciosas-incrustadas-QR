package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.Borde
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import java.util.Locale

/**
 * RC5 — filas placeholder que se muestran mientras el refresh inicial del
 * Paging aún no entrega items (re-entrada a la pantalla o recarga tras una
 * invalidación de Room) o durante la sync inicial con Room vacío. Pintar un
 * bloque estable con la misma geometría de las filas reales evita el
 * "parpadeo de vacío" (área de lista en blanco por 1-2 frames) antes de que
 * lleguen los datos cacheados.
 */
private const val FILAS_PLACEHOLDER_HISTORIAL = 8

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
 * ("Hoy", "Ayer", "Anteayer", y fechas concretas "dd/MM/yyyy") se preservan
 * como items individuales del LazyColumn — virtualizacion completa incluso
 * dentro de un mismo grupo (clave para usuarios con miles de URLs).
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
    val syncEnCurso by datosViewModel.syncEnCurso.collectAsStateWithLifecycle()
    val busqueda by datosViewModel.busquedaHistorial.collectAsStateWithLifecycle()
    val filtroSeleccionado by datosViewModel.filtroHistorial.collectAsStateWithLifecycle()
    val filas = datosViewModel.historialPaging.collectAsLazyPagingItems()

    // F4.3-b: el badge de bloqueo viaja en cada fila (EXISTS en SQL del
    // paging) — ya no se colecta el Set completo de URLs bloqueadas aquí.

    // V-1 fix: campos de historialUiState son nullable Int? — null = cargando
    // (la UI muestra "—"), 0 = realmente cero. Esto elimina el flash de
    // "0 escaneos" / "0% seguros" que aparecia antes de que Room emitiera
    // los datos reales.
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
                            color = CyberTextoPrincipal
                        )
                        Text(
                            text = "Todos tus escaneos, con su veredicto",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextoSecundario
                        )
                    }
                    if (syncEnCurso) {
                        EstadoSincronizacion()
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
                        selected = filtroSeleccionado == FiltroHistorial.TODAS,
                        onClick = { datosViewModel.actualizarFiltroHistorial(FiltroHistorial.TODAS) },
                        label = { Text("Todas ${totalTodos ?: "—"}") },
                        colors = chipColoresFiltro()
                    )
                    FilterChip(
                        selected = filtroSeleccionado == FiltroHistorial.SEGURAS,
                        onClick = { datosViewModel.actualizarFiltroHistorial(FiltroHistorial.SEGURAS) },
                        label = { Text("Seguras ${totalSeguras ?: "—"}") },
                        colors = chipColoresFiltro()
                    )
                    FilterChip(
                        selected = filtroSeleccionado == FiltroHistorial.SOSPECHOSAS,
                        onClick = { datosViewModel.actualizarFiltroHistorial(FiltroHistorial.SOSPECHOSAS) },
                        label = { Text("Sospechosas ${totalSospechosas ?: "—"}") },
                        colors = chipColoresFiltro()
                    )
                    FilterChip(
                        selected = filtroSeleccionado == FiltroHistorial.BLOQUEADAS,
                        onClick = { datosViewModel.actualizarFiltroHistorial(FiltroHistorial.BLOQUEADAS) },
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
                    ChipResumen(
                        // F4.3-b: mismo COUNT del chip de filtro "Bloqueadas"
                        // (excluye DELETEs pendientes) — antes venía del
                        // `.size` de la tabla completa de urls_bloqueadas.
                        "${totalBloqueadas ?: "—"} bloqueados",
                        CyberRojo
                    )
                    ChipResumen(
                        segurosPct?.let { "$it% seguros" } ?: "— seguros",
                        CyberVerdeAlerta
                    )
                }
            }

            // ─── List / Skeleton / Empty ───
            // V-1 fix: solo mostramos el estado vacio cuando los datos estan
            // cargados (totalTodos != null) y el refresh del Pager termino sin
            // filas. Cuando totalTodos es null (cargando) NO mostramos "No hay
            // escaneos" — evita flash del empty-state durante la carga.
            //
            // RC5: mientras el refresh inicial del Paging no entrega items, o
            // la sync inicial corre con Room vacío, se pinta un SKELETON de
            // filas (misma geometría que las reales) — antes el área quedaba
            // en blanco 1-2 frames con el hint visible, el "parpadeo de
            // vacío" reportado en cada re-entrada a la pantalla.
            if (filas.itemCount == 0 &&
                (filas.loadState.refresh is LoadState.Loading || syncEnCurso)
            ) {
                items(
                    count = FILAS_PLACEHOLDER_HISTORIAL,
                    key = { indice -> "ph_carga_$indice" },
                    contentType = { "fila_escaneo" }
                ) { FilaPlaceholderHistorial() }
            } else if (historialUiState.totalTodos != null &&
                filas.loadState.refresh is LoadState.NotLoading &&
                filas.itemCount == 0
            ) {
                item(key = "empty_state") {
                    EstadoVacio(
                        icono = Icons.Filled.SearchOff,
                        titulo = "No hay escaneos para mostrar",
                        descripcion = "Escanea un código QR para ver su análisis aquí.",
                        textoBoton = "Escanear código",
                        iconoBoton = Icons.Filled.QrCodeScanner,
                        onClick = onEscanear,
                    )
                }
            } else {
                // v10 — Paging 3: filas y cabeceras de fecha vienen del stream
                // paginado (insertSeparators del VM). Cada fila es un item
                // individual del LazyColumn con key=id → virtualizacion
                // completa; los headers comparten el LazyColumn con
                // contentType separado (F4.1 — pools de reutilización
                // distintos para "shapes" distintos).
                items(
                    count = filas.itemCount,
                    key = { indice ->
                        when (val fila = filas[indice]) {
                            is FilaHistorial.Entrada -> "e_${fila.escaneo.id}"
                            is FilaHistorial.Cabecera -> "h_${fila.titulo}"
                            null -> "ph_$indice"
                        }
                    },
                    contentType = { indice ->
                        when (filas[indice]) {
                            is FilaHistorial.Cabecera -> "grupo_header"
                            else -> "fila_escaneo"
                        }
                    }
                ) { indice ->
                    when (val fila = filas[indice]) {
                        is FilaHistorial.Cabecera -> {
                            // Auditoría UI 2: título + divisor — la sección
                            // temporal se lee como bloque delimitado.
                            Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
                                Text(
                                    text = fila.titulo,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTextoSecundario
                                )
                                HorizontalDivider(
                                    thickness = Borde.fino,
                                    color = CyberGlassBorde
                                )
                            }
                        }
                        is FilaHistorial.Entrada -> {
                            // F4.3-b: el flag de bloqueo llega calculado en
                            // SQL con la propia fila (EXISTS indexado).
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(RadioBorde.xl),
                                colors = CardDefaults.cardColors(containerColor = CyberGlass),
                                elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
                            ) {
                                FilaEscaneo(
                                    escaneo = fila.escaneo,
                                    bloqueada = fila.bloqueada,
                                    onVerDetalle = onVerDetalle,
                                )
                            }
                        }
                        null -> {
                            // Fila en carga (append en curso) — placeholder ligero.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Espaciado.xl),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(TamanosIcono.chico),
                                    color = CyberCyan,
                                    strokeWidth = 2.dp
                                )
                            }
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
                .clip(RadioBorde.full)
                .background(colorPunto)
        )
        Text(
            text = texto,
            style = MaterialTheme.typography.labelMedium,
            color = CyberTextoSecundario
        )
    }
}

/**
 * RC5 — fila skeleton del Historial: misma envoltura Card cyber-glass y
 * misma señal de carga ligera (spinner chico centrado) que usa la rama de
 * huecos de append, para que el bloque de carga y las filas reales
 * compartan lenguaje visual y alto aproximado.
 */
@Composable
private fun FilaPlaceholderHistorial() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadioBorde.xl),
        colors = CardDefaults.cardColors(containerColor = CyberGlass),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Espaciado.xl),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(TamanosIcono.chico),
                color = CyberCyan,
                strokeWidth = 2.dp
            )
        }
    }
}
