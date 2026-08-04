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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * Bug 2 fix (optimizacion cache): Pantalla de Reescaneos — pagina nueva
 * (no dentro de DetalleEscaneoScreen) que lista las versiones anteriores
 * de una URL.
 *
 * **Patron reactivo** (igual que [PantallaHistorial]):
 *  - Room es la fuente de verdad. El Flow `reescaneos` del ViewModel se
 *    suscribe via `stateIn(WhileSubscribed(5_000), emptyList())` — Room
 *    emite la lista cacheada en <1ms, **sin spinner de carga**.
 *  - Al navegar fuera y volver (nav bar), el Flow sigue vivo (mientras
 *    haya suscriptores activos o dentro del window de 5s), asi que la UI
 *    muestra los datos cacheados instantaneamente — **no vuelve a
 *    consultar**. Esta es la optimizacion pedida: las pantallas ya
 *    cargadas se cachean como el historial.
 *  - Sync pull incremental es **fire-and-forget**: corre en background
 *    sin bloquear la UI. Si inserta nuevos reescaneos en Room, el Flow
 *    re-emite automaticamente y la UI se actualiza sin que el usuario
 *    haga nada.
 *  - **Paginacion en la UI**: se cargan TODOS los reescaneos via Flow
 *    (como el historial carga todas las URLs), y la UI muestra solo los
 *    primeros [visibleCount] via `LazyColumn.take(visibleCount)`.
 *    `LazyColumn` virtualiza, asi que cargar 1000 items es igual de
 *    eficiente que 10. El boton "Ver mas" aumenta [visibleCount] en
 *    [PAGINA_UI] (10). [visibleCount] se preserva al rotar/recrear via
 *    `rememberSaveable`.
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
    // Bug 2 cache fix: el viewModel viene del padre (NavGuardian), no de
    // hiltViewModel() scoped al entry de la ruta. Asi persiste al navegar
    // fuera y volver — el StateFlow retiene la lista cacheada.
    viewModel: ReescaneosViewModel
) {
    // Establecer coordenadas en el ViewModel al montar la pantalla (una
    // sola vez por (urlLimpia, idActual)). El ViewModel usa flatMapLatest:
    // al cambiar las coordenadas, cancela el Flow viejo y subscribe el
    // nuevo — sin re-carga manual ni spinner.
    LaunchedEffect(urlLimpia, idActual) {
        viewModel.cargarReescaneos(urlLimpia, idActual)
    }

    val lista by viewModel.reescaneos.collectAsStateWithLifecycle()
    val totalReescaneos by viewModel.totalReescaneos.collectAsStateWithLifecycle()
    val syncEnCurso by viewModel.syncEnCurso.collectAsStateWithLifecycle()

    // ── Paginacion UI (patron "Ver mas") ──
    // visibleCount se reinicia cuando cambia urlLimpia (el usuario ve los
    // reescaneos de otra URL). Se preserva al rotar/recrearse la Activity
    // via rememberSaveable — al volver de la nav bar, el usuario ve el
    // mismo numero de items que antes de irse.
    var visibleCount by rememberSaveable(urlLimpia) { mutableIntStateOf(PAGINA_UI) }

    // Asegurar que visibleCount nunca excede el total disponible.
    LaunchedEffect(lista.size) {
        if (visibleCount > lista.size) {
            visibleCount = lista.size.coerceAtLeast(PAGINA_UI)
        }
    }

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

            // ── Contenido reactivo (sin spinner de carga) ──
            // Room emite la lista cacheada en <1ms. Si esta vacio y el sync
            // esta corriendo, mostramos un banner sutil (no bloqueante). Si
            // esta vacio y no hay sync, mostramos empty state.
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
                // ── Lista de reescaneos reactiva ──
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Espaciado.sm),
                    contentPadding = PaddingValues(bottom = Espaciado.gigante)
                ) {
                    // Banner sutil de sync (NO bloqueante — la lista ya
                    // esta visible con datos cacheados).
                    if (syncEnCurso) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = Espaciado.sm),
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
                    // Mostrar solo los primeros `visibleCount` reescaneos.
                    // LazyColumn virtualiza, asi que aunque `lista` tenga
                    // 100 items, solo se compondran los visibles + cache.
                    val visibles = lista.take(visibleCount)
                    items(visibles, key = { it.id }) { reescaneo ->
                        TarjetaReescaneoPublica(
                            reescaneo = reescaneo,
                            onVerDetalle = onVerDetalle
                        )
                    }
                    // ── Boton "Ver mas" (paginacion UI) ──
                    // Aumenta visibleCount en PAGINA_UI. El numero de
                    // "restantes" es totalReescaneos - visibleCount (no
                    // lista.size, porque lista puede ser menor si el Flow
                    // todavia no ha emitido todos).
                    val hayMas = visibleCount < totalReescaneos
                    if (hayMas) {
                        item {
                            OutlinedButton(
                                onClick = {
                                    visibleCount += PAGINA_UI
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Espaciado.md),
                                shape = RoundedCornerShape(RadioBorde.lg)
                            ) {
                                Text(
                                    text = "Ver mas (${totalReescaneos - visibleCount} restantes)",
                                    color = CyberCyan,
                                    fontWeight = FontWeight.Bold
                                )
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
 * Tamano de pagina visual (paginacion en UI). Igual que el historial.
 * Aumentar en bloques de PAGINA_UI cuando el usuario presiona "Ver mas".
 */
private const val PAGINA_UI = 10

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
