package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.R
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberRojoFondo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val estadoScroll = rememberScrollState()
    // Bug A5/A6 fix: repositorio offline-first en vez de cliente directo.
    val db = remember { BaseDatosSeguridad.get(context) }
    val backend = remember { ClienteBackend(ClienteBackend.BASE_POR_DEFECTO) }
    val json = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    val repoUrls = remember { RepositorioUrlsBloqueadas(db, backend, json) }
    val mediadorSync = remember { MediadorSincronizacion(context) }
    var bloqueando by rememberSaveable { mutableStateOf(false) }
    var bloqueadaOk by rememberSaveable { mutableStateOf<Boolean?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(estadoScroll)
                .background(CyberFondo)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
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
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberRojoFondo.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Bug 15 fix: el hero icon ya es Warning; usar Report en la
                    // card para diferenciar visualmente y evitar redundancia.
                    Icon(Icons.Filled.Report, contentDescription = null, tint = CyberRojo, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NO RECOMENDAMOS ABRIR ESTE ENLACE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberRojo
                    )
                }
                Text(
                    // Bug 15B fix: el motor actual es aleatorio determinista (no
                    // CANINE-S). Texto generico coincide con la realidad.
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
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberRojo,
                contentColor = CyberFondo
            )
        ) {
            Icon(Icons.Filled.Report, contentDescription = "Reportar")
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_report_url), fontWeight = FontWeight.Bold)
        }

        // ── Boton Bloquear URL (offline-first Room + pending_ops) ──
        Button(
            onClick = {
                if (bloqueando) return@Button
                bloqueando = true
                bloqueadaOk = null
                scope.launch {
                    try {
                        // Bug A5, A6 fix: offline-first. Antes se llamaba
                        // `cliente.bloquearUrl(token, url, razon)` directamente
                        // al backend, lo que fallaba silenciosamente sin red
                        // (A5) y no mostraba el error al usuario (A6). Ahora
                        // escribimos en Room + encola op CREATE en pending_ops;
                        // el SyncWorker lo envia al backend cuando haya red.
                        // Estado local garantizado aunque no haya conexion.
                        repoUrls.bloquearLocal(
                            url = resultado.urlLimpia,
                            razon = "Malicioso (probabilidad ${(resultado.probabilidad * 100).toInt()}%)"
                        )
                        // Dispara sync: si hay red, el worker envia el op; si
                        // no, queda encolado y se reintentara cuando vuelva la
                        // red. La UI no espera al backend en ningun caso.
                        mediadorSync.dispararSyncUnica()
                        bloqueadaOk = true
                        onMensaje(TipoMensaje.EXITO, "URL bloqueada")
                    } catch (e: Exception) {
                        // H3 fix: NO tragar CancellationException en un scope
                        // cancelado (rotacion, pop, onDispose). Antes el catch
                        // generico `Exception` capturaba CancellationException y
                        // continuaba ejecutando side-effects (snackbar, mutating
                        // state) en un scope ya muerto, lo que producia
                        // estado zombie / crashes secundarias. Re-thrown para
                        // que el parent CancelScope lo gestione correctamente.
                        if (e is CancellationException) throw e
                        // Bug A6 fix: error visible via Snackbar global.
                        bloqueadaOk = false
                        onMensaje(TipoMensaje.ERROR, "No se pudo bloquear la URL: ${e.message ?: "error desconocido"}")
                    } finally {
                        bloqueando = false
                    }
                }
            },
            enabled = !bloqueando,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberRojo,
                contentColor = CyberFondo,
                disabledContainerColor = CyberRojo.copy(alpha = 0.4f)
            )
        ) {
            if (bloqueando) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = CyberFondo
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.action_block_in_progress), fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Filled.Block, contentDescription = "Bloquear")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (bloqueadaOk) {
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
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Block, contentDescription = null, tint = CyberCyan)
            Spacer(modifier = Modifier.width(8.dp))
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
