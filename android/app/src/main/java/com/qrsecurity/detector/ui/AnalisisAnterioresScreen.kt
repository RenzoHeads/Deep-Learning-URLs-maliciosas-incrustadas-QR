package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.qrsecurity.detector.ui.theme.Borde
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * RC4 — filas placeholder que se muestran mientras el header no coincide
 * (primera carga o swap de URL) o el refresh inicial del Paging no entrega
 * items. Un bloque de alto ESTABLE (mismo número de filas y misma geometría
 * que las reales) evita el salto de layout y el parpadeo que producían el
 * spinner central y la fila única.
 */
private const val FILAS_PLACEHOLDER_CARGA = 8

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
 * @param datosViewModel VM compartido de tabs — fuente única de
 *   `syncEnCurso` (F4.6: elimina la doble observación Eagerly del WorkInfo
 *   Flow que mantenía el VM propio).
 */
@Composable
fun PantallaAnalisisAnteriores(
    urlLimpia: String,
    idActual: String,
    onVolver: () -> Unit,
    onEscanear: () -> Unit,
    onVerDetalle: (String) -> Unit = {},
    viewModel: AnalisisAnterioresViewModel,
    datosViewModel: DatosTabsViewModel
) {
    LaunchedEffect(urlLimpia, idActual) {
        viewModel.cargarAnalisisAnteriores(urlLimpia, idActual)
    }

    val estado by viewModel.estadoAnalisisAnteriores.collectAsStateWithLifecycle()
    val syncEnCurso by datosViewModel.syncEnCurso.collectAsStateWithLifecycle()
    val versiones = viewModel.versiones.collectAsLazyPagingItems()

    // S1: el estado de la cabecera es sellado — solo un Cargado cuya etiqueta
    // (url, id) coincide con la pantalla actual aporta el total; un Cargado
    // de la URL anterior (swap durante flatMapLatest) o Cargando no pintan.
    val cargado = estado as? EstadoAnalisisAnteriores.Cargado
    val dataCoincide = cargado != null &&
        cargado.url == urlLimpia && cargado.id == idActual
    val total = if (dataCoincide) cargado!!.total else 0
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
                                modifier = Modifier.size(TamanosIcono.chico),
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
                                // RC4: "—" mientras la etiqueta (url, id) del
                                // header no coincide (primera carga o swap de
                                // URL) — antes pintaba "0 análisis", que se leía
                                // como un total real que luego "crecía".
                                text = if (dataCoincide) "$total análisis" else "— análisis",
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
                        width = Borde.fino,
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
                        .clip(RadioBorde.full)
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
                    // Auditoría UI 2: misma política que TarjetaUrl — la URL es
                    // la información principal; hasta 4 líneas antes del
                    // ellipsis en vez de mutilarla a las 2.
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ─── History Section ───
        item(key = "history_label") {
            // Auditoría UI 2: la lista contiene solo versiones anteriores (el
            // DAO excluye la vigente) — el label lo dice explícitamente para
            // que no se confunda con "todas las versiones incluida la actual".
            Text(
                text = "Versiones anteriores",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = CyberTextoSecundario
            )
        }

        // RC4: mientras el header no coincide (primera carga o swap de URL —
        // S1) o el refresh inicial del Paging aún no entrega items, se pinta
        // un bloque ESTABLE de placeholders con la misma geometría de las
        // filas reales — antes eran un spinner central y una fila única
        // respectivamente, y la transición a datos producía salto de layout
        // y parpadeo. El empty-state solo aparece cuando la etiqueta coincide
        // Y el refresh terminó sin filas.
        when {
            !dataCoincide -> {
                items(
                    count = FILAS_PLACEHOLDER_CARGA,
                    key = { indice -> "ph_carga_$indice" },
                    contentType = { "entrada_linea_tiempo" }
                ) { FilaPlaceholderVersion() }
            }
            versiones.loadState.refresh is LoadState.Loading && versiones.itemCount == 0 -> {
                items(
                    count = FILAS_PLACEHOLDER_CARGA,
                    key = { indice -> "ph_refresh_$indice" },
                    contentType = { "entrada_linea_tiempo" }
                ) { FilaPlaceholderVersion() }
            }
            versiones.itemCount == 0 -> {
                item(key = "empty") {
                    EstadoVacio(
                        icono = Icons.Filled.History,
                        titulo = "No hay análisis anteriores",
                        descripcion = "Esta URL solo tiene una versión.\nEscanea de nuevo para crear un historial.",
                    )
                }
            }
            else -> {
                // v10 — Paging 3: `items(count)` con lookup por indice. Con
                // enablePlaceholders el indice es ABSOLUTO (incluye huecos
                // no cargados), de modo que el badge `version = total - index`
                // y `esUltimo` son correctos aunque la fila se cargue tarde.
                // Las filas null son placeholders ligeros mientras Paging
                // trae la pagina — memoria acotada a la ventana de scroll,
                // no al total de versiones de la URL.
                //
                // T3 — deriva transitoria de la numeracion durante backfill:
                // `version = total - indice` depende de `total` (COUNT del
                // header). Mientras el sync inicial sigue escribiendo versiones
                // MAS VIEJAS que todas (backfill DESC), `total` crece pero el
                // indice de las filas ya visibles no cambia (orden DESC: las
                // viejas se anclan al FINAL). La etiqueta de una fila visible i
                // pasa de `total0 - i` a `totalN - i` — el numero puede saltar
                // bajo los ojos del usuario mientras dura el backfill. Decisión
                // consciente: NO se re-ancla la fórmula (cambio de UI no
                // justificado); la deriva es acotada a la ventana del sync
                // inicial. Ver test
                // AnalisisAnterioresViewModelTest.`numeracion_total_menos_indice_semantica_fijada_deriva_durante_backfill`.
                items(
                    count = versiones.itemCount,
                    key = { indice -> versiones[indice]?.id ?: "ph_$indice" },
                    contentType = { "entrada_linea_tiempo" }
                ) { indice ->
                    val escaneo = versiones[indice]
                    if (escaneo == null) {
                        FilaPlaceholderVersion()
                    } else {
                        EntradaLineaTiempo(
                            escaneo = escaneo,
                            version = total - indice,
                            esUltimo = indice == total - 1,
                            onClick = { onVerDetalle(escaneo.id) }
                        )
                    }
                }
                if (versiones.loadState.append is LoadState.Loading) {
                    item(key = "append_loading") {
                        FilaPlaceholderVersion()
                    }
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
                // Auditoría UI 2: receta unificada — antes este OutlinedButton
                // duplicaba a mano la geometría de BotonCyber (56dp, radio lg,
                // borde fino cyan). Ahora es la variante outline del sistema.
                BotonCyberOutline(
                    texto = "Reescanear ahora",
                    onClick = onEscanear,
                    icono = Icons.Filled.Refresh
                )
                Text(
                    text = "Un escaneo nuevo tarda ~0,3 s",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTextoSecundario
                )
            }
        }
    }
}

/**
 * Placeholder de fila para los huecos de Paging (enablePlaceholders=true):
 * mientras Paging trae la pagina correspondiente al hueco, se pinta una
 * fila ligera con la misma geometria de vidrio que [EntradaLineaTiempo].
 */
@Composable
private fun FilaPlaceholderVersion() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.lg))
            .background(CyberGlass)
            .border(
                width = Borde.fino,
                color = CyberGlassBorde,
                shape = RoundedCornerShape(RadioBorde.lg)
            )
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
