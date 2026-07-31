package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import com.qrsecurity.detector.R
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.TipoMensaje
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class FiltroHistorial(val etiqueta: String) {
    TODOS("Todos"),
    SEGUROS("Seguros"),
    MALICIOSOS("Maliciosos")
}

/**
 * Constantes de tiempo compartidas por la UI de historial.
 */
private object ConstantesHistorial {
    /** Umbral de "ultimos 7 dias" en milisegundos, auto-documentado via TimeUnit. */
    val SIETE_DIAS_MS: Long = java.util.concurrent.TimeUnit.DAYS.toMillis(7)
}

/**
 * Estadisticas del historial provistas por el backend (`GET /estadisticas`).
 */
data class EstadisticasHistorial(
    val totalEscaneos: Int,
    val amenazas: Int,
    val ultimos7Dias: Int
)

/**
 * Pantalla de Historial — cyber-sentinel design (offline-first).
 *
 *  - Top app bar con logo QR GUARDIAN + icono filtro
 *  - Stats row (3 tarjetas glass: total / amenazas / 7 dias) — datos de Room
 *  - Filter chips horizontales (Todos / Seguros / Maliciosos)
 *  - Lista de entradas con tarjetas glass, borde lateral color-coded
 *  - FAB cyan para escanear
 *
 * Offline-first: Room es la fuente de verdad. La UI observa Flows del
 * [RepositorioEscaneos] (observarTodos / observarSeguros / observarMaliciosos)
 * + Flows de estadisticas (observarTotal / observarAmenazas / observarUltimos7Dias).
 * La pantalla no hace llamadas directas al backend — el [SyncWorker] sincroniza
 * en background. La UI se actualiza reactivamente cuando Room cambia.
 */
@Composable
fun PantallaHistorial(
    datosViewModel: DatosTabsViewModel,
    onEscanear: () -> Unit,
    // Bug DETAIL-1 fix: callback para navegar al detalle del escaneo
    // cuando el usuario toca una tarjeta del historial.
    onVerDetalle: (String) -> Unit = {},
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val scope = rememberCoroutineScope()

    // Performance fix: los Flows de Room se hospedan en DatosTabsViewModel
    // (scoped al NavGuardian, fuera del NavHost) para que no se cancelen al
    // cambiar de tab. El VM se pasa como parametro desde NavGuardian para
    // garantizar que Historial y Bloqueadas compartan la misma instancia.
    // Hilt: repoEscaneos y mediadorSync se inyectan via DatosTabsViewModel.
    val repoEscaneos = datosViewModel.repoEscaneos
    val mediadorSync = datosViewModel.mediadorSync

    var filtroActual by remember { mutableStateOf(FiltroHistorial.TODOS) }

    // Flows persistentes desde el ViewModel compartido — no se cancelan
    // al cambiar de tab. initialValue nunca es null (emptyList/0), asi
    // que nunca muestra el spinner de carga al volver.
    val historial by when (filtroActual) {
        FiltroHistorial.TODOS -> datosViewModel.historialTodos
        FiltroHistorial.SEGUROS -> datosViewModel.historialSeguros
        FiltroHistorial.MALICIOSOS -> datosViewModel.historialMaliciosos
    }.collectAsStateWithLifecycle()
    val totalEscaneos by datosViewModel.totalEscaneos.collectAsStateWithLifecycle()
    val amenazas by datosViewModel.amenazas.collectAsStateWithLifecycle()
    val ultimos7Dias by datosViewModel.ultimos7Dias.collectAsStateWithLifecycle()
    var escaneoEliminar by remember { mutableStateOf<EscaneoEntity?>(null) }

    val estadisticas = EstadisticasHistorial(
        totalEscaneos = totalEscaneos,
        amenazas = amenazas,
        ultimos7Dias = ultimos7Dias
    )

    Box(modifier = Modifier.fillMaxSize().background(CyberFondo)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = Espaciado.lg)
        ) {
            // ── Top AppBar ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Espaciado.lg, bottom = Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(TamanosIcono.estandar)
                    )
                    Spacer(modifier = Modifier.width(Espaciado.sm))
                    Text(
                        text = "QR GUARDIAN",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan,
                        modifier = Modifier.testTag("titulo_qr_guardian")
                    )
                }
                Icon(
                    imageVector = Icons.Filled.FilterList,
                    contentDescription = null,
                    tint = CyberTextoSecundario
                )
            }

            // ── Titulo + descripcion ──
            Text(
                text = "Historial de escaneos",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal,
                modifier = Modifier.testTag("titulo_historial_escaneos")
            )
            Text(
                text = "Tus escaneos se guardan localmente y se sincronizan con el servidor.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario
            )

            Spacer(modifier = Modifier.height(Espaciado.lg))

            // ── Stats row (3 tarjetas glass) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                TarjetaEstadistica(
                    etiqueta = "Total",
                    valor = estadisticas.totalEscaneos.toString(),
                    modifier = Modifier.weight(1f),
                    colorAcento = CyberCyan
                )
                TarjetaEstadistica(
                    etiqueta = "Amenazas",
                    valor = estadisticas.amenazas.toString(),
                    modifier = Modifier.weight(1f),
                    colorAcento = CyberRojo
                )
                TarjetaEstadistica(
                    etiqueta = "7 dias",
                    valor = estadisticas.ultimos7Dias.toString(),
                    modifier = Modifier.weight(1f),
                    colorAcento = CyberTextoSecundario
                )
            }

            Spacer(modifier = Modifier.height(Espaciado.lg))

            // ── Filter chips ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                FiltroHistorial.entries.forEach { filtro ->
                    ChipFiltro(
                        etiqueta = filtro.etiqueta,
                        seleccionado = filtroActual == filtro,
                        onClick = { filtroActual = filtro }
                    )
                }
            }

            Spacer(modifier = Modifier.height(Espaciado.lg))

            // ── Estado: vacio / lista ──
            // Performance fix: historial nunca es null ahora (el StateFlow
            // del ViewModel siempre tiene emptyList como valor inicial).
            // no hay spinner de carga — la lista aparece instantaneamente.
            val lista = historial
            if (lista.isEmpty()) {
                EstadoVacio(totalEscaneos = totalEscaneos)
            } else {
                ListaHistorial(
                    lista = lista,
                    onEliminar = { escaneoEliminar = it },
                    onVerDetalle = onVerDetalle
                )
            }
        }

        // ── FAB ──
        FloatingActionButton(
            onClick = onEscanear,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Espaciado.xxl),
            containerColor = CyberCyan,
            contentColor = CyberFondo
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = "Escanear")
        }

        // Dialogo de confirmacion antes de eliminar un escaneo.
        escaneoEliminar?.let { escaneo ->
            DialogoConfirmacion(
                titulo = "Eliminar escaneo",
                mensaje = "¿Estas seguro de que quieres eliminar el escaneo de \"${escaneo.urlLimpia.take(60)}${if (escaneo.urlLimpia.length > 60) "..." else ""}\"? Esta accion se sincronizara con el servidor.",
                textoConfirmar = "Eliminar",
                colorConfirmar = CyberRojo,
                onConfirmar = {
                    val aEliminar = escaneo
                    // Bug D6 fix: NO cerrar el dialogo antes de que Room
                    // confirme. Antes se seteaba escaneoEliminar = null aqui
                    // y si eliminarLocal fallaba, el usuario veia el
                    // snackbar de error pero el dialogo ya estaba cerrado
                    // — no podia reintentar. Ahora se cierra solo tras
                    // exito (dentro del try).
                    scope.launch {
                        // Offline-first: borra local + encola DELETE en outbox.
                        // El SyncWorker lo envia al backend cuando haya red.
                        try {
                            repoEscaneos.eliminarLocal(aEliminar.id)
                            mediadorSync.dispararSyncUnica()
                            escaneoEliminar = null
                            onMensaje(TipoMensaje.EXITO, "Escaneo eliminado")
                        } catch (e: Exception) {
                            onMensaje(TipoMensaje.ERROR, "No se pudo eliminar: ${e.message ?: "error"}")
                            // Dialogo queda abierto para reintentar.
                        }
                    }
                },
                onCancelar = { escaneoEliminar = null }
            )
        }
    }
}

@Composable
private fun EstadoVacio(totalEscaneos: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = CyberCyan.copy(alpha = 0.3f),
            modifier = Modifier.size(TamanosIcono.grande)
        )
        Spacer(modifier = Modifier.height(Espaciado.lg))
        Text(
            text = if (totalEscaneos == 0) "Aun no hay escaneos"
                   else "No hay entradas para este filtro",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal
        )
        Spacer(modifier = Modifier.height(Espaciado.xs))
        Text(
            text = if (totalEscaneos == 0) "Escanea un codigo QR para comenzar"
                   else "Prueba con otro filtro",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoSecundario,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ListaHistorial(
    lista: List<EscaneoEntity>,
    onEliminar: (EscaneoEntity) -> Unit,
    onVerDetalle: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Espaciado.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Espaciado.gigante)
    ) {
        items(lista, key = { it.id }) { escaneo ->
            TarjetaHistorial(
                escaneo = escaneo,
                onEliminar = { onEliminar(escaneo) },
                onVerDetalle = { onVerDetalle(escaneo.id) }
            )
        }
    }
}

@Composable
private fun TarjetaEstadistica(
    etiqueta: String,
    valor: String,
    modifier: Modifier = Modifier,
    colorAcento: androidx.compose.ui.graphics.Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(RadioBorde.xxl),
        colors = CardDefaults.cardColors(containerColor = CyberGlass)
    ) {
        Column(
            modifier = Modifier.padding(Espaciado.md),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = etiqueta.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextoSecundario
            )
            Text(
                text = valor,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colorAcento
            )
        }
    }
}

@Composable
private fun ChipFiltro(
    etiqueta: String,
    seleccionado: Boolean,
    onClick: () -> Unit
) {
    val colorFondo = if (seleccionado) CyberCyan else CyberGlass
    val colorTexto = if (seleccionado) CyberFondo else CyberTextoSecundario

    Box(
        modifier = Modifier
            .clip(RadioBorde.full)
            .background(colorFondo)
            .clickable(onClick = onClick)
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.sm)
    ) {
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.labelLarge,
            color = colorTexto
        )
    }
}

/**
 * Tarjeta de una entrada del historial. Acepta [EscaneoEntity] desde Room
 * (offline-first source of truth). Muestra el estado de sincronizacion
 * (pendiente / sincronizado) como indicador visual adicional.
 */
@Composable
private fun TarjetaHistorial(
    escaneo: EscaneoEntity,
    onEliminar: () -> Unit,
    // Bug DETAIL-1 fix: callback al tocar la tarjeta para abrir el detalle.
    onVerDetalle: () -> Unit = {}
) {
    val esMalicioso = escaneo.esMalicioso
    val colorIcono = if (esMalicioso) CyberRojo else CyberVerdeAlerta
    val icono = if (esMalicioso) Icons.Filled.Warning else Icons.Filled.CheckCircle

    // Extraer dominio de la URL (parte entre "://" y el primer "/").
    val dominio = remember(escaneo.urlLimpia) {
        val sinProtocolo = escaneo.urlLimpia.substringAfter("://", escaneo.urlLimpia)
        sinProtocolo.substringBefore("/")
    }

    // Formatear fecha desde epoch millis (Room) a "yyyy-MM-dd" para mostrar.
    val fechaStr = remember(escaneo.creadoEnMillis) {
        Instant.ofEpochMilli(escaneo.creadoEnMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Bug DETAIL-1 fix: tarjeta clicable para abrir el detalle del escaneo.
            .clickable(onClick = onVerDetalle),
        shape = RoundedCornerShape(RadioBorde.xxl),
        colors = CardDefaults.cardColors(containerColor = CyberGlass),
        border = androidx.compose.foundation.BorderStroke(Elevacion.sutil, CyberGlassBorde)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Espaciado.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(TamanosIcono.mediano)
                        .clip(RoundedCornerShape(RadioBorde.md))
                        .background(colorIcono.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icono, contentDescription = null, tint = colorIcono, modifier = Modifier.size(TamanosIcono.estandar))
                }
                Spacer(modifier = Modifier.width(Espaciado.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = escaneo.urlLimpia,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (esMalicioso) CyberRojo else CyberTextoPrincipal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$fechaStr • $dominio",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextoSecundario,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onEliminar) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Eliminar",
                    tint = CyberTextoSecundario
                )
            }
        }
    }
}


/**
 * Bug B5 fix: Dialogo de confirmacion reutilizable.
 * Pide confirmacion al usuario antes de acciones destructivas (eliminar,
 * desbloquear). Patron estandar de Material 3.
 */
@Composable
fun DialogoConfirmacion(
    titulo: String,
    mensaje: String,
    textoConfirmar: String,
    colorConfirmar: androidx.compose.ui.graphics.Color = CyberCyan,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(titulo, fontWeight = FontWeight.Bold, color = CyberTextoPrincipal) },
        text = { Text(mensaje, style = MaterialTheme.typography.bodyMedium, color = CyberTextoSecundario) },
        confirmButton = {
            TextButton(onClick = onConfirmar) {
                Text(textoConfirmar, color = colorConfirmar, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancelar) {
                Text(stringResource(R.string.action_cancel), color = CyberTextoSecundario)
            }
        },
        containerColor = CyberGlass,
        titleContentColor = CyberTextoPrincipal,
        textContentColor = CyberTextoSecundario
    )
}
