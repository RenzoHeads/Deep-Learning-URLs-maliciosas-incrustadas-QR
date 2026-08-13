package com.qrsecurity.detector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Pantalla de Analisis Anteriores / Versiones anteriores de una URL
 * (Pencil frame Lb1HV).
 *
 * Muestra la lista de versiones anteriores de una URL (reescaneos) con
 * notas de analisis. Wire a [AnalisisAnterioresViewModel].
 *
 * Rediseño F3: timeline visual con rail vertical + dots de color por
 * veredicto, versiones con badge V1/V2, % probabilidad, y empty state
 * rico con ilustracion vectorial.
 *
 * BUG #2 fix: migrado de `Column { verticalScroll { forEachIndexed {} } }`
 * a `LazyColumn { itemsIndexed(...) }` con `key = { it.id }`. La version
 * eager instanciaba todos los composables de la lista en el main thread.
 *
 * Entrada del timeline en [AnalisisAnterioresLineaTiempo.kt].
 *
 * @param urlLimpia URL limpia del escaneo principal (nav argument).
 * @param idActual Id del escaneo principal (nav argument).
 * @param onVolver Callback para volver a la pantalla anterior.
 * @param onEscanear Callback para reanalizar la URL.
 * @param onVerDetalle Callback para ver el detalle de una version.
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
        contentPadding = PaddingValues(
            horizontal = Espaciado.lg,
            vertical = Espaciado.lg
        ),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ─── Glass Pill Back Button ───
        // Blocker 3 fix: tercer callsite del mismo boton Glass Pill Back que
        // tambien aparecia inline en DetalleUrlScreen + DetalleVersionAntigua.
        item(key = "back_row") {
            GlassPillBackButton(onBack = onVolver)
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
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            else -> {
                // BUG #2 fix: cada entrada es un item individual del LazyColumn
                // con key=id → virtualizacion completa.
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
