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
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.R
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.HttpBackendException
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.CategoriaDenunciaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioCategorias
import com.qrsecurity.detector.datos.repositorios.RepositorioDenuncias
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.ui.TipoMensaje
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Pantalla Denunciar URL — cyber-sentinel design (offline-first).
 *
 * Formulario para reportar URLs maliciosas:
 *  - Campo URL sospechosa (pre-llenada si viene de ResultadoMalicioso)
 *  - Categoria FIJA: Phishing (constraint del proyecto)
 *  - Textarea descripcion
 *  - Boton Enviar denuncia (cyan glow) — llama [RepositorioDenuncias.crearLocal]
 *    (write-through Room + outbox) + dispara sync unica via WorkManager.
 *  - Boton Cancelar
 *
 * Offline-first: la denuncia se guarda localmente en Room y se encola un op
 * CREATE en `pending_ops`. El [SyncWorker] lo envia al backend cuando haya
 * red. La categoria se lee desde Room via [RepositorioCategorias.observarTodas]
 * (Flow reactivo); si Room esta vacio (primera vez), se dispara una sync para
 * poblarla desde el backend.
 *
 * @param urlPrevia URL pre-llenada cuando se llega desde ResultadoMalicioso (vacía en otro caso).
 * @param onExito Callback tras envio exitoso (NavGuardian hace popBackStack).
 * @param onCancelar Callback al pulsar Cancelar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaDenunciar(
    urlPrevia: String = "",
    onExito: () -> Unit,
    onCancelar: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlSospechosa by remember { mutableStateOf(urlPrevia) }
    // Constraint del proyecto: "TODO ES PHISHING NADA MAS, por ahora de categorias".
    // La categoria es fija; no hay dropdown.
    val categoriaFija = "Phishing"
    // Offline-first: el id de la categoria Phishing se resuelve desde Room
    // (Flow reactivo). Si Room esta vacio (primera ejecucion), se dispara
    // una sync de categorias; cuando el SyncWorker llene la tabla, el Flow
    // emite de nuevo y se actualiza idCategoriaPhishing. Fallback a 1 si
    // no hay datos locales.
    var idCategoriaPhishing by remember { mutableStateOf(1) }
    var descripcion by remember { mutableStateOf("") }
    var enviando by remember { mutableStateOf(false) }
    val estadoScroll = rememberScrollState()

    // Offline-first: repositorios leen/escriben Room, no al backend directo.
    val db = remember { BaseDatosSeguridad.get(context) }
    val backend = remember { ClienteBackend(ClienteBackend.BASE_POR_DEFECTO) }
    val json = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    val repoDenuncias = remember { RepositorioDenuncias(db, backend, json) }
    val repoCategorias = remember { RepositorioCategorias(db, backend) }
    val mediadorSync = remember { MediadorSincronizacion(context) }

    // P3-G: collectAsStateWithLifecycle para el estado reactive de categorias.
    // El side-effect (trigger sync si Room esta vacio) se mueve a un
    // LaunchedEffect(categorias) que observa el State producido por el Flow.
    val categorias by repoCategorias.observarTodas()
        .collectAsStateWithLifecycle(initialValue = emptyList<CategoriaDenunciaEntity>())
    // M2 fix: dedup flag — sin el, cada emision del Flow de categorias (Room
    // inserta filas nuevas, el SyncWorker las trae) re-dispara el side-effect
    // de sync. Con esto, la sincronizacion se dispara solo la primera vez que
    // observamos categorias vacias.
    //
    // Bug D3-P1 (fix Lote H): si la sync fallaba (red caida, backend 500,
    // etc.), `syncDisparada` se quedaba en `true` permanentemente. Categorias
    // seguia vacia, pero el `&& !syncDisparada` en el `LaunchedEffect` abajo
    // impedia re-disparar la sync. Como [MediadorSincronizacion.dispararSyncUnica]
    // usa [androidx.work.ExistingWorkPolicy.KEEP], un segundo encolo cay en
    // vacio (WorkManager descarto duplicados bajo el mismo uniqueName). El
    // usuario tenia que reiniciar la app o navegar away-and-back (re-creando
    // `PantallaDenunciar` y reseteando `syncDisparada = false` por
    // `remember { mutableStateOf(false) }`) para que se reintente. En la
    // practica, si el primer intento fallaba y el usuario esperaba, no habia
    // mecanismo de reintentar desde la UI.
    //
    // Fix: observamos el [androidx.work.WorkInfo.State] del one-shot sync via
    // [WorkManager.getWorkInfosForUniqueWorkFlow]. Cuando el state pasa a
    // FAILED o CANCELLED, reseteamos `syncDisparada = false`. El siguiente
    // `LaunchedEffect(categorias)` cycle vera `categorias.isEmpty() &&
    // !syncDisparada == true` y re-disparara la sync. Tambien reseteamos si el
    // state pasa a SUCCEEDED (cleanup) — pero ese caso es moot porque
    // categorias se poblara y el `if` short-circuitara por `!categorias
    // .isEmpty()`.
    var syncDisparada by remember { mutableStateOf(false) }
    val estadoSync by remember {
        androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(com.qrsecurity.detector.datos.sync.SyncWorker.NOMBRE_TRABAJO)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(estadoSync) {
        // Reset del flag de dedup cuando el one-shot sync termina (exito,
        // fallo o cancelado). Exito raramente tiene efecto: el siguiente
        // PULL polulara Room y `categorias` dejara de estar vacia, asi que
        // el `if` en el LaunchedEffect de abajo short-circuitara. El caso
        // importante es FAILED/CANCELLED: permite reintentar.
        val estados = estadoSync.map { it.state }
        if (androidx.work.WorkInfo.State.SUCCEEDED in estados ||
            androidx.work.WorkInfo.State.FAILED in estados ||
            androidx.work.WorkInfo.State.CANCELLED in estados
        ) {
            syncDisparada = false
        }
    }
    LaunchedEffect(categorias) {
        categorias.firstOrNull { it.nombre.equals(categoriaFija, ignoreCase = true) }
            ?.let { idCategoriaPhishing = it.id }
        if (categorias.isEmpty() && !syncDisparada) {
            syncDisparada = true
            mediadorSync.dispararSyncUnica()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .verticalScroll(estadoScroll)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Icono + Titulo ──
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CyberRojo.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Report,
                contentDescription = null,
                tint = CyberRojo,
                modifier = Modifier.size(40.dp)
            )
        }

        Text(
            text = "Denunciar URL Maliciosa",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal
        )

        Text(
            text = "Reporta URLs maliciosas para ayudar a proteger a otros usuarios.",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoSecundario,
            textAlign = TextAlign.Center
        )

        // ── Campo URL sospechosa ──
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "URL SOSPECHOSA",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = urlSospechosa,
                onValueChange = { urlSospechosa = it },
                modifier = Modifier.fillMaxWidth().testTag("campo_url_sospechosa"),
                placeholder = { Text(stringResource(R.string.placeholder_url_suspicious)) },
                textStyle = MaterialTheme.typography.bodyLarge,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CyberTextoPrincipal,
                    unfocusedTextColor = CyberTextoPrincipal,
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = CyberTextoSecundario.copy(alpha = 0.3f),
                    cursorColor = CyberCyan
                )
            )
        }

        // ── Categoria FIJA (Phishing) — sin dropdown ──
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "CATEGORIA",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Etiqueta estatica cyan mostrando Phishing (unica categoria por ahora).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CyberCyan.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Report,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = categoriaFija,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = CyberCyan
                )
            }
        }

        // ── Textarea Descripcion ──
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "DESCRIPCION (OPCIONAL)",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = descripcion,
                onValueChange = { descripcion = it },
                modifier = Modifier.fillMaxWidth().height(120.dp).testTag("campo_descripcion"),
                placeholder = { Text(stringResource(R.string.placeholder_description)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CyberTextoPrincipal,
                    unfocusedTextColor = CyberTextoPrincipal,
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = CyberTextoSecundario.copy(alpha = 0.3f),
                    cursorColor = CyberCyan
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Boton Enviar denuncia ──
        Button(
            onClick = {
                if (enviando) return@Button
                if (urlSospechosa.isBlank()) {
                    onMensaje(TipoMensaje.ERROR, "Ingresa la URL sospechosa")
                    return@Button
                }
                enviando = true
                enviarDenuncia(
                    scope = scope,
                    url = urlSospechosa,
                    idCategoria = idCategoriaPhishing,
                    descripcion = descripcion,
                    repoDenuncias = repoDenuncias,
                    mediadorSync = mediadorSync,
                    onMensaje = onMensaje,
                    onExito = onExito,
                    onEnviando = { enviando = it }
                )
            },
            enabled = !enviando,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("btn_enviar_denuncia"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = CyberFondo,
                disabledContainerColor = CyberCyan.copy(alpha = 0.4f),
                disabledContentColor = CyberFondo
            )
        ) {
            Icon(Icons.Filled.Report, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (enviando) stringResource(R.string.action_sending) else stringResource(R.string.action_send_report),
                fontWeight = FontWeight.Bold
            )
        }

        // ── Boton Cancelar ──
        TextButton(
            onClick = onCancelar,
            modifier = Modifier.fillMaxWidth().testTag("btn_cancelar_denuncia")
        ) {
            Text(stringResource(R.string.action_cancel), color = CyberTextoSecundario)
        }
    }
}

private fun enviarDenuncia(
    scope: kotlinx.coroutines.CoroutineScope,
    url: String,
    idCategoria: Int,
    descripcion: String,
    repoDenuncias: RepositorioDenuncias,
    mediadorSync: MediadorSincronizacion,
    onMensaje: (TipoMensaje, String) -> Unit,
    onExito: () -> Unit,
    onEnviando: (Boolean) -> Unit
) {
    scope.launch {
        try {
            repoDenuncias.crearLocal(
                url = url.trim(),
                idCategoria = idCategoria,
                descripcion = descripcion.ifBlank { null }
            )
            mediadorSync.dispararSyncUnica()
            onMensaje(TipoMensaje.EXITO, "Denuncia enviada")
            onExito()
        } catch (e: HttpBackendException) {
            onMensaje(TipoMensaje.ERROR, construirMensajeErrorBackend(e))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            onMensaje(TipoMensaje.ERROR, "Error al guardar la denuncia: ${e.message ?: "error"}")
        } finally {
            onEnviando(false)
        }
    }
}

private fun construirMensajeErrorBackend(e: HttpBackendException): String = buildString {
    append("Error ")
    append(e.codigo)
    append(" del servidor")
    if (!e.cuerpo.isNullOrBlank()) {
        append(": ")
        append(e.cuerpo.take(200))
    }
}
