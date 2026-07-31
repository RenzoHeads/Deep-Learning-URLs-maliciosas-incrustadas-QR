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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.R
import com.qrsecurity.detector.datos.local.entidades.CategoriaDenunciaEntity
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque
import androidx.work.WorkInfo

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
 * Inyeccion: [DenunciarViewModel] recibe los repositorios y el mediador de
 * sync via Hilt (@HiltViewModel). La Screen recoge el UiState reactivamente
 * (collectAsStateWithLifecycle) y despacha acciones via onAction (UDF).
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
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: DenunciarViewModel = hiltViewModel()
) {
    // Bug D3 fix: rememberSaveable para que el formulario sobreviva a
    // rotacion. Antes, al rotar el dispositivo, el texto que el usuario
    // habia ingresado en urlSospechosa y descripcion se perdia porque
    // remember se reinicia en cada cambio de configuracion.
    var urlSospechosa by rememberSaveable { mutableStateOf(urlPrevia) }
    // Constraint del proyecto: por ahora, cada denuncia se clasifica como Phishing.
    val categoriaFija = "Phishing"
    var descripcion by rememberSaveable { mutableStateOf("") }
    val estadoScroll = rememberScrollState()

    // UiState reactivo via Hilt ViewModel (patron NowInAndroid UDF).
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categorias by viewModel.categorias.collectAsStateWithLifecycle()

    // M2 fix: dedup flag — sin el, cada emision del Flow de categorias (Room
    // inserta filas nuevas, el SyncWorker las trae) re-dispara el side-effect
    // de sync.
    // Bug D3-P1 (fix Lote H): si la sync fallaba, `syncDisparada` se quedaba en
    // `true` permanentemente. Observamos el WorkInfo.State del one-shot sync;
    // cuando pasa a FAILED/CANCELLED, reseteamos para permitir reintentar.
    var syncDisparada by remember { mutableStateOf(false) }
    val estadoSync by viewModel.estadoSync.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(estadoSync) {
        val estados = estadoSync.map { it.state }
        if (WorkInfo.State.SUCCEEDED in estados ||
            WorkInfo.State.FAILED in estados ||
            WorkInfo.State.CANCELLED in estados
        ) {
            syncDisparada = false
        }
    }
    LaunchedEffect(categorias) {
        viewModel.resolverCategoriaPhishing(categoriaFija)
        if (categorias.isEmpty() && !syncDisparada) {
            syncDisparada = true
            viewModel.dispararSyncCategorias()
        }
    }

    // Recoger exito/error del UiState y disparar callbacks (UDF).
    LaunchedEffect(uiState.exito, uiState.error) {
        if (uiState.exito) {
            onMensaje(TipoMensaje.EXITO, "Denuncia enviada")
            viewModel.consumirEvento()
            onExito()
        }
        uiState.error?.let { msg ->
            onMensaje(TipoMensaje.ERROR, msg)
            viewModel.consumirEvento()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .verticalScroll(estadoScroll)
            .padding(horizontal = Espaciado.xl, vertical = Espaciado.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ── Icono + Titulo ──
        Box(
            modifier = Modifier
                .size(Espaciado.gigante)
                .clip(RoundedCornerShape(RadioBorde.xxl))
                .background(CyberRojo.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Report,
                contentDescription = null,
                tint = CyberRojo,
                modifier = Modifier.size(TamanosIcono.mediano)
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
            Spacer(modifier = Modifier.height(Espaciado.xs))
            OutlinedTextField(
                value = urlSospechosa,
                onValueChange = { urlSospechosa = it },
                modifier = Modifier.fillMaxWidth().testTag("campo_url_sospechosa"),
                placeholder = { Text(stringResource(R.string.placeholder_url_suspicious)) },
                textStyle = MaterialTheme.typography.bodyLarge,
                singleLine = true,
                shape = RoundedCornerShape(RadioBorde.lg),
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
            Spacer(modifier = Modifier.height(Espaciado.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(RadioBorde.lg))
                    .background(CyberCyan.copy(alpha = 0.12f))
                    .padding(horizontal = Espaciado.lg, vertical = Espaciado.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                Icon(
                    imageVector = Icons.Filled.Report,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(Espaciado.xl)
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
            Spacer(modifier = Modifier.height(Espaciado.xs))
            OutlinedTextField(
                value = descripcion,
                onValueChange = { descripcion = it },
                modifier = Modifier.fillMaxWidth().height(Espaciado.hero).testTag("campo_descripcion"),
                placeholder = { Text(stringResource(R.string.placeholder_description)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CyberTextoPrincipal,
                    unfocusedTextColor = CyberTextoPrincipal,
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = CyberTextoSecundario.copy(alpha = 0.3f),
                    cursorColor = CyberCyan
                )
            )
        }

        Spacer(modifier = Modifier.height(Espaciado.sm))

        // ── Boton Enviar denuncia ──
        Button(
            onClick = {
                if (uiState.enviando) return@Button
                if (urlSospechosa.isBlank()) {
                    onMensaje(TipoMensaje.ERROR, "Ingresa la URL sospechosa")
                    return@Button
                }
                // Bug L2 fix: validar que la URL tenga un scheme HTTP/HTTPS.
                // Antes solo se verificaba isBlank() — "asdf",
                // "javascript:alert(1)", "ftp://foo" pasaban y se persistian
                // a Room como reporte Phishing. DetalleEscaneoScreen.kt
                // valida scheme==http/https; replicamos aqui para
                // consistencia.
                val schemeValida = runCatching {
                    val u = java.net.URL(urlSospechosa.trim())
                    u.protocol == "http" || u.protocol == "https"
                }.getOrDefault(false)
                if (!schemeValida) {
                    onMensaje(TipoMensaje.ERROR, "La URL debe comenzar con http:// o https://")
                    return@Button
                }
                viewModel.onAction(
                    DenunciarAction.EnviarDenuncia(
                        url = urlSospechosa,
                        idCategoria = uiState.idCategoriaPhishing,
                        descripcion = descripcion
                    )
                )
            },
            enabled = !uiState.enviando,
            modifier = Modifier.fillMaxWidth().height(TamanosToque.boton).testTag("btn_enviar_denuncia"),
            shape = RoundedCornerShape(RadioBorde.xl),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = CyberFondo,
                disabledContainerColor = CyberCyan.copy(alpha = 0.38f),
                disabledContentColor = CyberFondo
            )
        ) {
            Icon(Icons.Filled.Report, contentDescription = null)
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(
                text = if (uiState.enviando) stringResource(R.string.action_sending) else stringResource(R.string.action_send_report),
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
