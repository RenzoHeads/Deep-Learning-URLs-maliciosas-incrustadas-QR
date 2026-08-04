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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlertaClaro
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bug 2 fix: Pantalla de Reescaneos — pagina nueva (no dentro de
 * DetalleEscaneoScreen) que lista las versiones anteriores de una URL.
 *
 * Sigue la misma logica que [PantallaHistorial]:
 *  - Offline-first: Room es la fuente de verdad.
 *  - Sync pull incremental: al montar la pantalla, dispara un sync
 *    one-shot para traer del backend cualquier reescaneo nuevo (hecho
 *    en [ReescaneosViewModel.cargarReescaneos]).
 *  - Paginacion local: la lista carga de a [ReescaneosViewModel].
 *    `tamanoPagina` items. El boton "Ver mas" dispara
 *    [ReescaneosAction.CargarMas] que lee la siguiente pagina desde
 *    Room (no del backend — el sync ya pobla Room).
 *  - Reactivo: si el sync inserta nuevos reescaneos en Room, el Flow
 *    del total re-emite y la UI actualiza el contador + refresca la
 *    pagina actual.
 *
 * @param urlLimpia URL limpia cuyo historial de versiones se muestra.
 * @param idActual Id del escaneo "principal" (la version mas reciente)
 *   — se excluye de la lista porque ya esta siendo vista por el
 *   usuario en DetalleEscaneoScreen.
 * @param onVolver Callback para volver a DetalleEscaneoScreen.
 * @param onVerDetalle Callback para navegar al detalle de un reescaneo
 *   (version anterior). Reusa la ruta `detalle_escaneo/{id}`.
 * @param onEscanear Callback para ir a la pantalla de escaneo (FAB).
 * @param viewModel Inyectado por Hilt.
 */
@Composable
fun PantallaReescaneos(
    urlLimpia: String,
    idActual: String,
    onVolver: () -> Unit,
    onVerDetalle: (String) -> Unit,
    onEscanear: () -> Unit,
    viewModel: ReescaneosViewModel = hiltViewModel()
) {
    // Cargar reescaneos al montar la pantalla, una sola vez por
    // (urlLimpia, idActual). El ViewModel ignora llamadas duplicadas
    // para la misma URL+id.
    LaunchedEffect(urlLimpia, idActual) {
        viewModel.cargarReescaneos(urlLimpia, idActual)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val totalReescaneos by viewModel.totalReescaneos.collectAsStateWithLifecycle()
    val syncEnCurso by viewModel.syncEnCurso.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(CyberFondo)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = Espaciado.lg)
        ) {
            // ── Top bar con boton volver + titulo ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Espaciado.lg, bottom = Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onVolver) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = CyberCyan
                    )
                }
                Spacer(modifier = Modifier.width(Espaciado.sm))
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Spacer(modifier = Modifier.width(Espaciado.sm))
                Text(
                    text = "OTRAS VERSIONES DE ESCANEO",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan
                )
            }

            // ── URL cuya lista de versiones se muestra ──
            Text(
                text = urlLimpia,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Espaciado.lg, end = Espaciado.lg, bottom = Espaciado.sm)
            )

            // ── Contenido segun estado ──
            when (val estado = uiState) {
                ReescaneosUiState.Cargando -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Espaciado.md)
                        ) {
                            CircularProgressIndicator(
                                color = CyberCyan,
                                strokeWidth = Elevacion.flotante
                            )
                            Text(
                                text = "Cargando versiones...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberTextoSecundario
                            )
                        }
                    }
                }
                ReescaneosUiState.NoAplica -> {
                    // No-op: estado intermedio.
                }
                is ReescaneosUiState.Cargado -> {
                    val lista = estado.reescaneos
                    if (lista.isEmpty() && !syncEnCurso) {
                        // ── Empty state ──
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = CyberCyan.copy(alpha = 0.3f),
                                modifier = Modifier.size(TamanosIcono.grande)
                            )
                            Spacer(modifier = Modifier.height(Espaciado.lg))
                            Text(
                                text = "No hay otras versiones",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = CyberTextoPrincipal
                            )
                            Spacer(modifier = Modifier.height(Espaciado.xs))
                            Text(
                                text = "Esta URL solo ha sido escaneada una vez.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberTextoSecundario,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        // ── Lista de reescaneos paginada ──
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(Espaciado.sm),
                            contentPadding = PaddingValues(bottom = Espaciado.gigante)
                        ) {
                            if (syncEnCurso && lista.isEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(Espaciado.md),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(Espaciado.xl),
                                            color = CyberCyan,
                                            strokeWidth = Elevacion.flotante
                                        )
                                        Spacer(modifier = Modifier.width(Espaciado.md))
                                        Text(
                                            text = "Sincronizando...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = CyberTextoSecundario
                                        )
                                    }
                                }
                            }
                            items(lista, key = { it.id }) { reescaneo ->
                                TarjetaReescaneoPublica(
                                    reescaneo = reescaneo,
                                    onVerDetalle = onVerDetalle
                                )
                            }
                            // ── Boton "Ver mas" (paginacion local) ──
                            val hayMas = lista.size < totalReescaneos
                            if (hayMas) {
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.onAction(ReescaneosAction.CargarMas)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = Espaciado.md),
                                        shape = RoundedCornerShape(RadioBorde.lg)
                                    ) {
                                        Text(
                                            text = "Ver mas (${totalReescaneos - lista.size} restantes)",
                                            color = CyberCyan,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── FAB escanear ──
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
    }
}

/**
 * Tarjeta publica de un reescaneo (version anterior de una URL).
 * Muestra URL, fecha y nivel de alerta. Tocar navega al detalle.
 *
 * Bug 2 fix: movida desde [DetalleEscaneoScreen] (donde era `private`)
 * a esta pantalla dedicada de reescaneos. Misma UI — cyber-sentinel
 * design con color-coded icono segun veredicto.
 */
@Composable
private fun TarjetaReescaneoPublica(
    reescaneo: EscaneoEntity,
    onVerDetalle: (String) -> Unit
) {
    val esMalicioso = reescaneo.esMalicioso
    val colorIcono = if (esMalicioso) CyberRojo else CyberVerdeAlertaClaro
    val icono = if (esMalicioso) Icons.Filled.Warning else Icons.Filled.CheckCircle

    val fechaStr = remember(reescaneo.creadoEnMillis) {
        Instant.ofEpochMilli(reescaneo.creadoEnMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.lg))
            .background(CyberGlass.copy(alpha = 0.5f))
            .clickable { onVerDetalle(reescaneo.id) }
            .padding(Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Icon(
            imageVector = icono,
            contentDescription = null,
            tint = colorIcono,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reescaneo.urlLimpia,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoPrincipal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$fechaStr • ${reescaneo.nivelAlerta.uppercase()} • " +
                    "${(reescaneo.probabilidad * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = CyberTextoSecundario,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = "Ver detalle",
            tint = CyberTextoSecundario,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
    }
}


