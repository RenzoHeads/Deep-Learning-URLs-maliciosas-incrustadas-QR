package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.R
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberRojoFondo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Pantalla de Resultado Malicioso — cyber-sentinel design.
 *
 * Muestra:
 *  - Icono Warning rojo con glow radial rojo
 *  - Titulo "Enlace Malicioso"
 *  - Probabilidad de amenaza (barra + porcentaje)
 *  - Tarjeta glass roja con URL detectada + advertencia
 *  - Botones: Denunciar URL, Ver URLs bloqueadas, Copiar, Compartir, Escanear otro
 *  - NO hay boton "Abrir enlace" — no se recomienda abrir
 *
 * Bug A5, A6 fix: el boton "Bloquear URL" ahora es offline-first: escribe
 * en Room + encola un op CREATE en `pending_ops`, y dispara sync via
 * WorkManager. Antes llamaba `ClienteBackend.bloquearUrl` directamente y
 * fallaba silenciosamente si no habia red (A5) sin mostrar el error (A6).
 * Ahora el bloqueo siempre se registra localmente y los errores del Room
 * se muestran via Snackbar.
 *
 * Inyeccion: [ResultadoMaliciosoViewModel] recibe el repositorio y el
 * mediador de sync via Hilt (@HiltViewModel). La Screen recoge el UiState
 * reactivamente (collectAsStateWithLifecycle) y despacha acciones via
 * onAction (UDF).
 *
 * @param onDenunciar Recibe la URL detectada (`resultado.urlOriginal`) para que
 *  NavGuardian la inyecte como `urlPrevia` en la pantalla Denunciar. Antes era
 *  `() -> Unit` y la URL se perdia al navegar (Bug 11).
 */
@Composable
fun PantallaResultadoMalicioso(
    resultado: Pipeline.ResultadoAnalisis.ResultadoUrl,
    onEscanearOtro: () -> Unit,
    onDenunciar: (String) -> Unit,
    onVerBloqueadas: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: ResultadoMaliciosoViewModel = hiltViewModel()
) {
    val estadoScroll = rememberScrollState()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ResultadoMaliciosoEfectos(uiState = uiState, viewModel = viewModel, onMensaje = onMensaje)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(estadoScroll)
                .background(CyberFondo)
                .padding(horizontal = Espaciado.xl, vertical = Espaciado.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xl)
        ) {
        // ── Barra superior (shared component) ──
        BarraSuperiorResultado(
            titulo = "Resultado del analisis",
            onCerrar = onEscanearOtro
        )

        // ── Icono Warning con glow rojo (shared component) ──
        IconoGlowCircular(
            icono = Icons.Filled.Warning,
            colorGlow = CyberRojo,
            contentDescription = "Malicioso"
        )

        // ── Titulo + probabilidad ──
        Text(
            text = "Enlace Malicioso",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CyberRojo
        )

        Text(
            text = "Probabilidad de amenaza: %.1f%%".format(resultado.probabilidad * 100f),
            style = MaterialTheme.typography.bodyLarge,
            color = CyberTextoSecundario
        )

        // ── Tarjeta glass roja con advertencia ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RadioBorde.xl),
            colors = CardDefaults.cardColors(containerColor = CyberRojoFondo.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(Espaciado.xl), verticalArrangement = Arrangement.spacedBy(Espaciado.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Report, contentDescription = null, tint = CyberRojo, modifier = Modifier.size(Espaciado.xl))
                    Spacer(modifier = Modifier.width(Espaciado.sm))
                    Text(
                        text = "NO RECOMENDAMOS ABRIR ESTE ENLACE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberRojo
                    )
                }
                Text(
                    text = "El sistema de deteccion identifico indicadores de phishing en esta URL.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoPrincipal
                )
            }
        }

        // ── Tarjeta glass con detalles de URL (shared component) ──
        TarjetaUrlDetectada(
            urlTexto = resultado.urlOriginal,
            colorUrl = CyberRojo,
            delegado = resultado.delegado
        )

        // ── Boton Denunciar URL ──
        Button(
            onClick = { onDenunciar(resultado.urlOriginal) },
            modifier = Modifier.fillMaxWidth().height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberRojo,
                contentColor = CyberFondo
            )
        ) {
            Icon(Icons.Filled.Report, contentDescription = "Reportar")
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(stringResource(R.string.action_report_url), fontWeight = FontWeight.Bold)
        }

        // ── Boton Bloquear URL (offline-first Room + pending_ops) ──
        Button(
            onClick = {
                if (uiState.bloqueando) return@Button
                viewModel.onAction(
                    ResultadoMaliciosoAction.BloquearUrl(
                        urlLimpia = resultado.urlLimpia,
                        probabilidad = resultado.probabilidad
                    )
                )
            },
            enabled = !uiState.bloqueando,
            modifier = Modifier.fillMaxWidth().height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberRojo,
                contentColor = CyberFondo,
                disabledContainerColor = CyberRojo.copy(alpha = 0.38f)
            )
        ) {
            if (uiState.bloqueando) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Espaciado.xl),
                    strokeWidth = Elevacion.flotante,
                    color = CyberFondo
                )
                Spacer(modifier = Modifier.width(Espaciado.md))
                Text(stringResource(R.string.action_block_in_progress), fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Filled.Block, contentDescription = "Bloquear")
                Spacer(modifier = Modifier.width(Espaciado.sm))
                Text(
                    text = when (uiState.bloqueadaOk) {
                        true -> "URL bloqueada"
                        else -> "Bloquear URL"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Boton Ver URLs bloqueadas ──
        OutlinedButton(
            onClick = onVerBloqueadas,
            modifier = Modifier.fillMaxWidth().height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg)
        ) {
            Icon(Icons.Filled.Block, contentDescription = null, tint = CyberCyan)
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(stringResource(R.string.action_view_blocked), color = CyberCyan)
        }

        // ── Botones secundarios: Copiar + Compartir (shared component) ──
        FilaCopiarCompartir(
            urlTexto = resultado.urlOriginal,
            onMensaje = onMensaje
        )

        // ── Boton Escanear otro (shared component) ──
        BotonEscanearOtro(onEscanearOtro = onEscanearOtro)
        }

    }
}

/**
 * S3776 fix: LaunchedEffects extraidos a esta funcion para reducir
 * la Cognitive Complexity de PantallaResultadoMalicioso de 16 a <= 15.
 */
@Composable
private fun ResultadoMaliciosoEfectos(
    uiState: ResultadoMaliciosoUiState,
    viewModel: ResultadoMaliciosoViewModel,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            onMensaje(TipoMensaje.ERROR, msg)
            viewModel.consumirError()
        }
    }
    LaunchedEffect(uiState.bloqueadaOk) {
        if (uiState.bloqueadaOk == true) {
            onMensaje(TipoMensaje.EXITO, "URL bloqueada")
            viewModel.consumirBloqueoOk()
        }
    }
}
