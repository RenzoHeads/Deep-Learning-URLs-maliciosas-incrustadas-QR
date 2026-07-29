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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.R
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.ui.TipoMensaje
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pantalla de URLs Bloqueadas — cyber-sentinel design (offline-first).
 *
 * Lista URLs bloqueadas desde Room via [RepositorioUrlsBloqueadas.observarTodos]
 * (Flow reactivo). Cada tarjeta tiene borde rojo, icono Block, y boton
 * "Desbloquear" que llama a [RepositorioUrlsBloqueadas.desbloquearLocal]
 * (borra de Room + encola DELETE en outbox). El SyncWorker sincroniza en
 * background cuando hay red.
 */
@Composable
fun PantallaBloqueadas(
    datosViewModel: DatosTabsViewModel,
    onEscanear: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Performance fix: los Flows de Room se hospedan en DatosTabsViewModel
    // (scoped al NavGuardian, fuera del NavHost) para que no se cancelen al
    // cambiar de tab. El VM se pasa como parametro desde NavGuardian para
    // garantizar que Historial y Bloqueadas compartan la misma instancia.
    val mediadorSync = remember { MediadorSincronizacion(context) }

    // StateFlow persistente desde el ViewModel compartido — never null.
    val urlsBloqueadas by datosViewModel.urlsBloqueadas.collectAsStateWithLifecycle()
    val repoUrls = datosViewModel.repoUrls
    var urlDesbloquear by remember { mutableStateOf<UrlBloqueadaEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(CyberFondo)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            // ── Top AppBar ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = CyberRojo,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "URLs BLOQUEADAS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan
                    )
                }
            }

            Text(
                text = "Estas URLs fueron detectadas como maliciosas y bloqueadas.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Performance fix: urlsBloqueadas nunca es null ahora (el StateFlow
            // del ViewModel siempre tiene emptyList como valor inicial).
            // No hay spinner de carga — la lista aparece instantaneamente.
            val lista = urlsBloqueadas
            if (lista.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null,
                        tint = CyberCyan.copy(alpha = 0.3f),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No hay URLs bloqueadas",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextoPrincipal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Escanea codigos QR para detectar amenazas",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextoSecundario,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)
                ) {
                    items(lista, key = { it.id }) { urlBloq ->
                        TarjetaUrlBloqueada(
                            url = urlBloq.url,
                            fechaMillis = urlBloq.creadoEnMillis,
                            razon = urlBloq.razon,
                            onDesbloquear = {
                                urlDesbloquear = urlBloq
                            }
                        )
                    }
                }
            }
        }

        // ── FAB ──
        FloatingActionButton(
            onClick = onEscanear,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = CyberCyan,
            contentColor = CyberFondo
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = "Escanear")
        }

        // Dialogo de confirmacion antes de desbloquear URL.
        urlDesbloquear?.let { urlBloq ->
            DialogoConfirmacion(
                titulo = "Desbloquear URL",
                mensaje = "¿Estas seguro de que quieres desbloquear \"${urlBloq.url.take(60)}${if (urlBloq.url.length > 60) "..." else ""}\"? Quedara fuera de tu lista de URLs bloqueadas.",
                textoConfirmar = "Desbloquear",
                colorConfirmar = CyberRojo,
                onConfirmar = {
                    val aDesbloquear = urlBloq
                    urlDesbloquear = null
                    scope.launch {
                        // Offline-first: borra local + encola DELETE en outbox.
                        // El SyncWorker lo envia al backend cuando haya red.
                        try {
                            repoUrls.desbloquearLocal(aDesbloquear.id)
                            mediadorSync.dispararSyncUnica()
                            onMensaje(TipoMensaje.EXITO, "URL desbloqueada")
                        } catch (e: Exception) {
                            onMensaje(TipoMensaje.ERROR, "No se pudo desbloquear: ${e.message ?: "error"}")
                        }
                    }
                },
                onCancelar = { urlDesbloquear = null }
            )
        }
    }
}

@Composable
private fun TarjetaUrlBloqueada(
    url: String,
    fechaMillis: Long,
    razon: String?,
    onDesbloquear: () -> Unit
) {
    // Formatear fecha desde epoch millis a "yyyy-MM-dd".
    val fechaStr = remember(fechaMillis) {
        Instant.ofEpochMilli(fechaMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CyberGlass),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlassBorde)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberRojo.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Block, contentDescription = null, tint = CyberRojo, modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelLarge,
                        color = CyberRojo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Bloqueada $fechaStr" +
                            (if (!razon.isNullOrBlank()) " • $razon" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextoSecundario,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDesbloquear, shape = RoundedCornerShape(8.dp)) {
                    Text(stringResource(R.string.action_unblock), color = CyberCyan, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
