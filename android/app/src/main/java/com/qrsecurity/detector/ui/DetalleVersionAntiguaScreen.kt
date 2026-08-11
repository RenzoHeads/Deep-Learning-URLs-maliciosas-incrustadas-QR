package com.qrsecurity.detector.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.CyberAmbar
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.PencilOverlay
import com.qrsecurity.detector.ui.theme.PencilModalFondo
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla dedicada para visualizar UNA version historica especifica de
 * una URL (NO la ultima version).
 *
 * Diferencia vs [PantallaDetalleUrl]:
 *  - NO muestra el boton "Ver versiones de este analisis" (ese callback
 *    inicia el loop DetalleUrl → AnalisisAnteriores → DetalleUrl → ...).
 *    Por eso esta pantalla es "hoja" del arbol de navegacion: se llega
 *    DESDE [PantallaAnalisisAnteriores] via `onVerDetalle(id)` y solo se
 *    puede volver atras (popBackStack) o eliminar la version.
 *  - NO muestra botones de Bloquear/Desbloquear (esos aplican a la URL
 *    como entidad, no a una version individual — el usuario debe manejar
 *    el bloqueo desde [PantallaDetalleUrl] si quiere bloquear/desbloquear
 *    la URL entera).
 *  - NO muestra el boton "Abrir enlace" / "Compartir" (esos son acciones
 *    que el usuario tomaria sobre la URL actual, no sobre un escaneo
 *    historico). Abrir enlace es mas util en DetalleUrl (ultima version).
 *  - Muestra la fecha del escaneo (clave para distinguir versiones →
 *    DetalleUrl no la muestra porque para la ultima version no es
 *    informativa).
 *  - Boton unico: "Eliminar esta version" → elimina el escaneo
 *    individual por id (NO cascada por urlLimpia como hace
 *    [DetalleUrlAction.EliminarUrl]).
 *
 * @param id UUID del escaneo (version historica) a visualizar.
 * @param onBack Callback para volver atras (popBackStack a
 *   [PantallaAnalisisAnteriores]).
 * @param onMensaje Callback para snackbars.
 * @param ViewModel de detalle de version antigua (Hilt, inyectado).
 */
@Composable
fun PantallaDetalleVersionAntigua(
    id: String,
    onBack: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: DetalleVersionAntiguaViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Carga el escaneo al entrar (y si el id cambia por reutilizacion de VM).
    LaunchedEffect(id) {
        viewModel.cargarEscaneo(id)
    }

    // Eventos one-shot del VM (snackbars).
    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.mensaje.collect { mensaje ->
                onMensaje(mensaje.tipo, mensaje.texto)
            }
        }
    }

    // Evento one-shot: version eliminada → navegar atras a AnalisisAnteriores.
    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.eliminarCompletado.collect {
                onBack()
            }
        }
    }

    // Hardware back → onBack.
    BackHandler(onBack = onBack)

    // Modal de confirmacion de eliminacion de version.
    var modalEliminarVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
    ) {
        when (val estado = uiState) {
            is DetalleVersionAntiguaUiState.Cargando ->
                ContenidoCargandoVersionAntigua()
            is DetalleVersionAntiguaUiState.NoEncontrado ->
                ContenidoNoEncontradoVersionAntigua(onBack = onBack)
            is DetalleVersionAntiguaUiState.Cargado ->
                ContenidoDetalleVersionAntigua(
                    escaneo = estado.escaneo,
                    onBack = onBack,
                    onSolicitarEliminar = { modalEliminarVisible = true }
                )
        }
    }

    if (modalEliminarVisible) {
        ModalEliminarVersion(
            onConfirmar = {
                modalEliminarVisible = false
                viewModel.eliminarVersion(id)
            },
            onCancelar = { modalEliminarVisible = false }
        )
    }
}

/**
 * Estado Cargando — spinner con color cyan, mismo patron que
 * [DetalleUrlScreen.ContenidoCargando] (privado ahi, replicado aqui
 * para evitar acoplamiento entre pantallas con responsabilidades
 * distintas).
 */
@Composable
private fun ContenidoCargandoVersionAntigua() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = CyberCyan)
    }
}

/**
 * Estado NoEncontrado — muestra mensaje + boton "Volver". Se puede
 * llegar aqui si el usuario elimina esta version desde otra pantalla
 * mientras la tiene abierta (race condition que el Flow refleja
 * reactivamente).
 */
@Composable
private fun ContenidoNoEncontradoVersionAntigua(onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
        ) {
            Text(
                text = "Versión no encontrada",
                style = MaterialTheme.typography.titleMedium,
                color = CyberTextoSecundario
            )
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(RadioBorde.lg)
            ) {
                Text(text = "Volver", color = CyberTextoPrincipal)
            }
        }
    }
}

/**
 * Contenido principal del detalle de version antigua.
 *
 * Layout (de arriba a abajo):
 *  1. Back Row (Volver)
 *  2. Title: "Detalle del análisis"
 *  3. URL Row (QR icon + URL truncada + Chip nivel alerta)
 *  4. Fecha del escaneo (diferenciador entre versiones)
 *  5. Tarjeta de veredicto (gauge + amenaza label), estilo Cyber
 *  6. Boton "Eliminar esta versión" (rojo)
 */
@Composable
private fun ContenidoDetalleVersionAntigua(
    escaneo: EscaneoEntity,
    onBack: () -> Unit,
    onSolicitarEliminar: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ─── Back Row ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(vertical = Espaciado.xs),
            horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
            verticalAlignment = Alignment.CenterVertically
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

        // ─── Title Block ───
        Text(
            text = "Detalle del análisis",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal
        )

        // ─── URL Row ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.QrCode,
                contentDescription = null,
                tint = CyberTextoSecundario,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Text(
                text = escaneo.urlOriginal.ifBlank { escaneo.urlLimpia },
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoPrincipal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ChipNivelAlerta(nivelAlerta = escaneo.nivelAlerta)
        }

        // ─── Fecha del escaneo ───
        // Diferenciador clave entre versiones: DetalleUrl no lo muestra
        // porque para la ultima version no es informativo. Aqui es
        // critico para que el usuario sepa QUE version esta viendo.
        FormatoFechaEscaneo(creadoEnMillis = escaneo.creadoEnMillis)

        // ─── Verdict Card (gauge + amenaza) ───
        TarjetaVeredictoVersionAntigua(escaneo = escaneo)

        // ─── Eliminar button ───
        // Unica accion disponible en esta pantalla. Elimina SOLO esta
        // version (por id), no todas las versiones de la URL.
        Spacer(modifier = Modifier.height(Espaciado.lg))
        Button(
            onClick = onSolicitarEliminar,
            modifier = Modifier
                .fillMaxWidth()
                .height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberRojo,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.size(Espaciado.sm))
            Text(
                text = "Eliminar esta versión",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Chip de nivel de alerta - subset de [DetalleUrlScreen.ChipEstadoUrl]
 * (privada ahi) SIN mostrar el estado "BLOQUEADA" (eso requiere
 * `urlBloqueada`, que no forma parte del estado de version antigua).
 *
 * Para una version antigua, lo unico significativo a mostrar como chip
 * es el nivel de amenaza que el modelo inferio en el momento del escaneo
 * (que es lo que diferencia esta version de otras versiones de la
 * misma URL).
 */
@Composable
private fun ChipNivelAlerta(nivelAlerta: String) {
    val (texto, colorFondo, colorTexto) = when (nivelAlerta) {
        "MALICIOSO" -> Triple("MALICIOSO", CyberRojo.copy(alpha = 0.18f), CyberRojo)
        "SOSPECHOSO" -> Triple("SOSPECHOSO", CyberAmbar.copy(alpha = 0.18f), CyberAmbar)
        else -> Triple("SEGURO", CyberVerdeAlerta.copy(alpha = 0.18f), CyberVerdeAlerta)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(RadioBorde.sm))
            .background(colorFondo)
            .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
    ) {
        Text(
            text = texto,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = colorTexto
        )
    }
}

/**
 * Formato de fecha del escaneo - muestra la etiqueta "Fecha del escaneo"
 * con el timestamp formateado. Usado para diferenciar visualmente entre
 * versiones del mismo escaneo (mismo urlLimpia, distintos ids y fechas).
 *
 * Formato: "Fecha del escaneo: dd MMM yyyy, HH:mm"
 * Ej: "Fecha del escaneo: 11 ago 2026, 14:30"
 */
@Composable
private fun FormatoFechaEscaneo(creadoEnMillis: Long) {
    val formato = remember {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("es"))
    }
    val fechaTexto = remember(creadoEnMillis) {
        formato.format(Date(creadoEnMillis))
    }
    Text(
        text = "Fecha del escaneo: $fechaTexto",
        style = MaterialTheme.typography.bodySmall,
        color = CyberTextoSecundario
    )
}

/**
 * Tarjeta de veredicto (gauge + amenaza label) - replica de
 * [DetalleUrlScreen.TarjetaVeredicto] (privada ahi), simplificada para
 * tomar directamente [EscaneoEntity] en lugar de [DetalleUrlUiState.Cargado]
 * (que incluye flags de bloqueo/esUltimaVersion/totalReescaneos no
 * necesarios aqui).
 *
 * Reusa [MedidorGauge] (publico en [Medidores.kt]) y los helpers
 * [colorPorNivel] / [etiquetaAmenazaPorNivel] / [subtituloPorNivel]
 * (internal en DetalleUrlScreen.kt, marcados internal para esta
 * reutilizacion).
 */
@Composable
private fun TarjetaVeredictoVersionAntigua(escaneo: EscaneoEntity) {
    val colorVeredicto = colorPorNivel(escaneo.nivelAlerta)
    // WAVE 14 fix (M1): Math.round en vez de .toInt() (que trunca hacia 0).
    val valorPct = Math.round(escaneo.probabilidad * 100f)
    val amenazaLabel = etiquetaAmenazaPorNivel(escaneo.nivelAlerta)
    val amenazaSubtitulo = subtituloPorNivel(escaneo.nivelAlerta)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.xl))
            .background(CyberGlass)
            .border(
                width = 1.dp,
                color = CyberGlassBorde,
                shape = RoundedCornerShape(RadioBorde.xl)
            )
            .padding(Espaciado.xl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Espaciado.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gauge
            MedidorGauge(
                progreso = escaneo.probabilidad,
                colorArco = colorVeredicto,
                colorTrack = CyberGlassBorde,
                valorTexto = valorPct.toString(),
                colorTexto = CyberTextoPrincipal,
                modifier = Modifier.size(TamanosIcono.heroContenedor)
            )
            // Verdict Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                Text(
                    text = amenazaLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextoPrincipal
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(colorVeredicto.copy(alpha = 0.16f))
                        .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
                ) {
                    Text(
                        text = amenazaSubtitulo,
                        style = MaterialTheme.typography.labelMedium,
                        color = colorVeredicto
                    )
                }
            }
        }
    }
}

/**
 * Modal de confirmacion para "Eliminar esta version". Variante de
 * [ModalEliminarUrl] (en ModalDesbloqueo.kt) con texto adaptado:
 *  - Titulo: "¿Eliminar esta versión?" (vs "¿Eliminar esta URL?")
 *  - Body: "Se eliminará esta versión del historial." (vs "Se
 *    eliminarán todos los análisis de esta URL")
 *  - Consecuencias: "Esta acción no se puede deshacer" (sin alusion a
 *    "reescaneos" porque aqui NO los borramos - solo esta version).
 *
 * Si el usuario confirma, [onConfirmar] invoca
 * [DetalleVersionAntiguaViewModel.eliminarVersion(id)] que dispara el
 * borrado individual + sync + invalida cache + emite
 * [DetalleVersionAntiguaViewModel.eliminarCompletado] → back.
 */
@Composable
private fun ModalEliminarVersion(
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PencilOverlay),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Espaciado.xxl)
                .clip(RoundedCornerShape(RadioBorde.xl))
                .background(PencilModalFondo)
                .border(
                    width = 1.dp,
                    color = CyberGlassBorde,
                    shape = RoundedCornerShape(RadioBorde.xl)
                )
                .padding(Espaciado.xxl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ─── Step Indicator ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(CyberRojo)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(CyberGlassBorde)
                )
            }
            Text(
                text = "PASO 1 DE 2",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Title ───
            Text(
                text = "¿Eliminar esta versión?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Body ───
            Text(
                text = "Se eliminará esta versión del historial. Las demás versiones de esta URL se conservarán.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Consecuencias ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(TamanosIcono.estandar)
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(CyberRojo.copy(alpha = 0.18f))
                )
                Text(
                    text = "La acción no se puede deshacer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(Espaciado.xs))

            // ─── Delete Button ───
            Button(
                onClick = onConfirmar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberRojo,
                    contentColor = CyberFondo
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Spacer(modifier = Modifier.size(Espaciado.sm))
                Text(
                    text = "Eliminar versión",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // ─── Cancel Button ───
            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CyberGlass,
                    contentColor = CyberTextoSecundario
                ),
                border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent)
            ) {
                Text(
                    text = "Cancelar",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
