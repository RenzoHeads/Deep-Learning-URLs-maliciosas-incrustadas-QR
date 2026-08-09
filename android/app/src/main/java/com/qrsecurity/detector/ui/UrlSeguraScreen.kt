package com.qrsecurity.detector.ui

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Pantalla de URL Segura / Resultado seguro (Pencil frame svy5r).
 *
 * F3.5: implementacion real del layout de Pencil svy5r. La firma NO debe cambiar.
 *
 * Muestra el resultado de un escaneo benigno (SEGURO) con:
 *  - Back row "Volver" (vuelve a la pantalla de analisis/escaneo).
 *  - Title block "Detalle del analisis" + URL + chip "SEGURA" (verde).
 *  - Verdict card con gauge verde (puntuacion de seguridad = 1 - probabilidad),
 *    etiqueta "Sin amenazas" y subtitulo "Verificado hace X min".
 *  - Acciones: "Abrir enlace" (primario cyan) + "Compartir" (secundario).
 *  - Link "Ver detalle completo" (navega a DetalleUrlScreen con opciones de bloqueo).
 *
 * Wire a [DetalleUrlViewModel] (mismo VM que DetalleUrlScreen — Hilt, scoped al
 * NavBackStackEntry).
 *
 * @param id Id del escaneo (nav argument).
 * @param onEscanearOtro Callback para navegar a la pantalla de analisis
 *   (usado por el back row "Volver" — volver al escaneo).
 * @param onVerDetalle Callback para navegar al detalle completo (DetalleUrlScreen).
 * @param onMensaje Callback para mostrar snackbars.
 * @param viewModel VM de detalle (Hilt, scoped al NavBackStackEntry).
 */
@Composable
fun PantallaUrlSegura(
    id: String,
    onEscanearOtro: () -> Unit,
    onVerDetalle: (String) -> Unit = {},
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: DetalleUrlViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Carga el escaneo al entrar (y si el id cambia por reutilizacion de VM).
    LaunchedEffect(id) {
        viewModel.cargarEscaneo(id)
    }

    // Eventos one-shot del VM (snackbars) — mismo patron que PantallaDetalleUrl.
    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.mensaje.collect { mensaje ->
                onMensaje(mensaje.tipo, mensaje.texto)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
    ) {
        when (val estado = uiState) {
            is DetalleUrlUiState.Cargando -> ContenidoCargandoSeguro()
            is DetalleUrlUiState.NoEncontrado -> ContenidoNoEncontradoSeguro(
                onVolver = onEscanearOtro
            )
            is DetalleUrlUiState.Cargado -> ContenidoSeguro(
                estado = estado,
                onVolver = onEscanearOtro,
                onVerDetalle = onVerDetalle,
                onMensaje = onMensaje
            )
        }
    }
}

// ─── Estados ───

@Composable
private fun ContenidoCargandoSeguro() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = CyberVerdeAlerta)
    }
}

@Composable
private fun ContenidoNoEncontradoSeguro(onVolver: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
        ) {
            Text(
                text = "Escaneo no encontrado",
                style = MaterialTheme.typography.titleMedium,
                color = CyberTextoSecundario
            )
            OutlinedButton(
                onClick = onVolver,
                shape = RoundedCornerShape(RadioBorde.lg)
            ) {
                Text(text = "Volver", color = CyberTextoPrincipal)
            }
        }
    }
}

@Composable
private fun ContenidoSeguro(
    estado: DetalleUrlUiState.Cargado,
    onVolver: () -> Unit,
    onVerDetalle: (String) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    val escaneo = estado.escaneo
    val contexto = LocalContext.current
    val puntuacionSeguridad = ((1f - escaneo.probabilidad) * 100f).toInt().coerceIn(0, 100)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.xxl, vertical = Espaciado.xxl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ─── Back Row ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onVolver)
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

        // URL Row: QR icon + URL + chip SEGURA
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
                fontWeight = FontWeight.SemiBold,
                color = CyberTextoPrincipal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Chip "SEGURA" (verde)
            Box(
                modifier = Modifier
                    .clip(RadioBorde.full)
                    .background(CyberVerdeAlerta.copy(alpha = 0.12f))
                    .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
            ) {
                Text(
                    text = "SEGURA",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberVerdeAlerta
                )
            }
        }

        // ─── Verdict Card ───
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
                // Gauge verde (puntuacion de seguridad)
                MedidorGauge(
                    progreso = puntuacionSeguridad / 100f,
                    colorArco = CyberVerdeAlerta,
                    colorTrack = CyberGlassAlto,
                    valorTexto = puntuacionSeguridad.toString(),
                    colorTexto = CyberVerdeAlerta,
                    modifier = Modifier.size(TamanosIcono.heroContenedor)
                )
                // Verdict Info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
                ) {
                    Text(
                        text = "Sin amenazas",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextoPrincipal
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(RadioBorde.sm))
                            .background(CyberVerdeAlerta.copy(alpha = 0.12f))
                            .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
                    ) {
                        Text(
                            text = tiempoRelativo(escaneo.creadoEnMillis),
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberVerdeAlerta
                        )
                    }
                }
            }
        }

        // ─── Actions ───
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Espaciado.md)
        ) {
            // Primary Button: Abrir enlace
            Button(
                onClick = {
                    val url = urlParaAbrir(escaneo.urlOriginal, escaneo.urlLimpia)
                    if (url == null) {
                        onMensaje(TipoMensaje.ERROR, "Enlace con esquema no permitido")
                    } else {
                        abrirEnNavegador(contexto, url)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyan,
                    contentColor = CyberFondo
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(TamanosIcono.estandar)
                    )
                    Text(
                        text = "Abrir enlace",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(Espaciado.xs))
            Text(
                text = "Se abre en navegador protegido",
                style = MaterialTheme.typography.bodySmall,
                color = CyberTextoSecundario,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            // Secondary Button: Compartir
            OutlinedButton(
                onClick = {
                    compartirUrl(
                        contexto,
                        urlParaAbrir(escaneo.urlOriginal, escaneo.urlLimpia) ?: escaneo.urlLimpia
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CyberGlass,
                    contentColor = CyberTextoPrincipal
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlassBorde)
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Spacer(modifier = Modifier.size(Espaciado.sm))
                Text(
                    text = "Compartir",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ─── Ver detalle completo link ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onVerDetalle(escaneo.id) }
                .padding(vertical = Espaciado.sm),
            horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Ver detalle completo",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberCyan,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
        }
    }
}

// ─── Helpers ───

/**
 * Calcula un texto de tiempo relativo ("Verificado hace X min") desde
 * [creadoEnMillis] hasta ahora. Pencil svy5r muestra "Verificado hace 18 min".
 */
private fun tiempoRelativo(creadoEnMillis: Long): String {
    val ahora = System.currentTimeMillis()
    val diffMin = ((ahora - creadoEnMillis) / 60_000L).coerceAtLeast(0L)
    return when {
        diffMin < 1L -> "Verificado justo ahora"
        diffMin < 60L -> "Verificado hace $diffMin min"
        else -> {
            val horas = diffMin / 60L
            if (horas < 24L) "Verificado hace $horas h"
            else {
                val dias = horas / 24L
                "Verificado hace $dias d"
            }
        }
    }
}
