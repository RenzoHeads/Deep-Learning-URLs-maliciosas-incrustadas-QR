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
    var urlSospechosa by rememberSaveable { mutableStateOf(urlPrevia) }
    val categoriaFija = "Phishing"
    var descripcion by rememberSaveable { mutableStateOf("") }
    val estadoScroll = rememberScrollState()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categorias by viewModel.categorias.collectAsStateWithLifecycle()

    var syncDisparada by remember { mutableStateOf(false) }
    val estadoSync by viewModel.estadoSync.collectAsStateWithLifecycle(initialValue = emptyList())

    DenunciarEfectos(
        estadoSync = estadoSync,
        categorias = categorias,
        categoriaFija = categoriaFija,
        syncState = EstadoSyncDisparada(syncDisparada) { syncDisparada = it },
        uiState = uiState,
        viewModel = viewModel,
        callbacks = CallbacksResultado(onMensaje, onExito)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .verticalScroll(estadoScroll)
            .padding(horizontal = Espaciado.xl, vertical = Espaciado.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        EncabezadoDenunciar()

        CampoUrlSospechosa(
            urlSospechosa = urlSospechosa,
            onUrlChanged = { urlSospechosa = it }
        )

        TarjetaCategoriaFija(categoriaFija = categoriaFija)

        CampoDescripcion(
            descripcion = descripcion,
            onDescripcionChanged = { descripcion = it }
        )

        Spacer(modifier = Modifier.height(Espaciado.sm))

        BotonEnviarDenuncia(
            enviando = uiState.enviando,
            urlSospechosa = urlSospechosa,
            idCategoria = uiState.idCategoriaPhishing,
            descripcion = descripcion,
            onMensaje = onMensaje,
            onAction = { viewModel.onAction(it) }
        )

        BotonCancelarDenuncia(onCancelar = onCancelar)
    }
}

/**
 * S107 fix: agrupa el flag de sync disparada y su callback en un unico
 * parametro para reducir el numero de parametros de DenunciarEfectos.
 */
private data class EstadoSyncDisparada(
    val disparada: Boolean,
    val onChanged: (Boolean) -> Unit
)

/**
 * S107 fix: agrupa los callbacks de resultado en un unico parametro.
 */
private data class CallbacksResultado(
    val onMensaje: (TipoMensaje, String) -> Unit,
    val onExito: () -> Unit
)

/**
 * S3776 fix: todos los LaunchedEffects extraidos a esta funcion para reducir
 * la Cognitive Complexity de PantallaDenunciar de 21 a <= 15.
 * S107 fix: parametros agrupados via EstadoSyncDisparada y CallbacksResultado.
 */
@Composable
private fun DenunciarEfectos(
    estadoSync: List<WorkInfo>,
    categorias: List<CategoriaDenunciaEntity>,
    categoriaFija: String,
    syncState: EstadoSyncDisparada,
    uiState: DenunciarUiState,
    viewModel: DenunciarViewModel,
    callbacks: CallbacksResultado
) {
    LaunchedEffect(estadoSync) {
        val estados = estadoSync.map { it.state }
        if (WorkInfo.State.SUCCEEDED in estados ||
            WorkInfo.State.FAILED in estados ||
            WorkInfo.State.CANCELLED in estados
        ) {
            syncState.onChanged(false)
        }
    }
    LaunchedEffect(categorias) {
        viewModel.resolverCategoriaPhishing(categoriaFija)
        if (categorias.isEmpty() && !syncState.disparada) {
            syncState.onChanged(true)
            viewModel.dispararSyncCategorias()
        }
    }
    LaunchedEffect(uiState.exito, uiState.error) {
        if (uiState.exito) {
            callbacks.onMensaje(TipoMensaje.EXITO, "Denuncia enviada")
            viewModel.consumirEvento()
            callbacks.onExito()
        }
        uiState.error?.let { msg ->
            callbacks.onMensaje(TipoMensaje.ERROR, msg)
            viewModel.consumirEvento()
        }
    }
}

@Composable
private fun EncabezadoDenunciar() {
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
}

@Composable
private fun CampoUrlSospechosa(
    urlSospechosa: String,
    onUrlChanged: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "URL SOSPECHOSA",
            style = MaterialTheme.typography.labelMedium,
            color = CyberTextoSecundario
        )
        Spacer(modifier = Modifier.height(Espaciado.xs))
        OutlinedTextField(
            value = urlSospechosa,
            onValueChange = onUrlChanged,
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
}

@Composable
private fun TarjetaCategoriaFija(categoriaFija: String) {
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
}

@Composable
private fun CampoDescripcion(
    descripcion: String,
    onDescripcionChanged: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "DESCRIPCION (OPCIONAL)",
            style = MaterialTheme.typography.labelMedium,
            color = CyberTextoSecundario
        )
        Spacer(modifier = Modifier.height(Espaciado.xs))
        OutlinedTextField(
            value = descripcion,
            onValueChange = onDescripcionChanged,
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
}

/**
 * S1874 fix: java.net.URL esta deprecated en Kotlin/Android.
 * Reemplazado por java.net.URI que NO esta deprecated.
 */
private fun esUrlHttpHttps(url: String): Boolean {
    return runCatching {
        val uri = java.net.URI(url.trim())
        val scheme = uri.scheme
        scheme == "http" || scheme == "https"
    }.getOrDefault(false)
}

@Composable
private fun BotonEnviarDenuncia(
    enviando: Boolean,
    urlSospechosa: String,
    idCategoria: Int,
    descripcion: String,
    onMensaje: (TipoMensaje, String) -> Unit,
    onAction: (DenunciarAction) -> Unit
) {
    Button(
        onClick = {
            if (enviando) return@Button
            if (urlSospechosa.isBlank()) {
                onMensaje(TipoMensaje.ERROR, "Ingresa la URL sospechosa")
                return@Button
            }
            if (!esUrlHttpHttps(urlSospechosa)) {
                onMensaje(TipoMensaje.ERROR, "La URL debe comenzar con http:// o https://")
                return@Button
            }
            onAction(
                DenunciarAction.EnviarDenuncia(
                    url = urlSospechosa,
                    idCategoria = idCategoria,
                    descripcion = descripcion
                )
            )
        },
        enabled = !enviando,
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
            text = if (enviando) stringResource(R.string.action_sending) else stringResource(R.string.action_send_report),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BotonCancelarDenuncia(onCancelar: () -> Unit) {
    TextButton(
        onClick = onCancelar,
        modifier = Modifier.fillMaxWidth().testTag("btn_cancelar_denuncia")
    ) {
        Text(stringResource(R.string.action_cancel), color = CyberTextoSecundario)
    }
}
