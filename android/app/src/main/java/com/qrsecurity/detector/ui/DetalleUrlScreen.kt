package com.qrsecurity.detector.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.Espaciado

/**
 * Pantalla de Detalle de URL (Pencil frame ZEXEp).
 *
 * Fusiona DetalleEscaneo + ResultadoMalicioso. Muestra: URL, probabilidad,
 * nivel de amenaza (gauge + veredicto), "Ver analisis anteriores" y botones
 * de accion (Bloquear/Desbloquear/Compartir/Abrir). Wire a
 * [DetalleUrlViewModel].
 *
 * NOTA: El desbloqueo de URL es manual y permanente (no existe desbloqueo
 * temporal). Tras confirmar el desbloqueo se muestra [ModalDesbloqueoOk].
 *
 * Rediseño F3: UI premium dark-glassmorphism con URL card prominente,
 * gauge 140dp centrado con border glow, botones seccionados en grid 2-col,
 * y "Ver versiones" como glass card.
 *
 * Descomposicion del archivo:
 *  - [DetalleUrlTarjetas]: tarjetas (URL, veredicto, versiones) + chip de estado.
 *  - [DetalleUrlAcciones]: botones de accion (Abrir/Bloquear/Desbloquear/Eliminar).
 *  - [ModalDesbloqueo], [ModalDesbloqueoConfirmarOk], [ModalEliminarUrl]: modales.
 *
 * P1: los 4 modales se modelan con [ModalDetalleUrl] (sealed interface) en
 * lugar de 4 booleanos mutuamente excluyentes — fuerza estado legal (solo
 * uno visible a la vez) y manejo exhaustivo en el `when`.
 *
 * @param id Id del escaneo (nav argument).
 * @param onVerAnalisisAnteriores Callback con (urlLimpia, idActual).
 * @param onMensaje Callback para mostrar snackbars.
 * @param viewModel VM de detalle (Hilt, scoped al NavBackStackEntry).
 * @param onBack Callback para volver atras.
 */

/**
 * Modal activo en la pantalla de DetalleUrl. Solo UNO puede estar visible
 * a la vez (los 4 modales son mutuamente excluyentes por UX). El estado
 * [Ninguno] representa el estado sin modal — reemplaza los 4 booleanos
 * independientes previos (que admitian combinaciones imposibles como
 * "ConfirmarDesbloqueo + EliminarUrl visibles a la vez").
 */
sealed interface ModalDetalleUrl {
    data object Ninguno : ModalDetalleUrl
    data object ConfirmarDesbloqueo : ModalDetalleUrl
    data object OkDesbloqueo : ModalDetalleUrl
    data object ConfirmarBloqueo : ModalDetalleUrl
    data object EliminarUrl : ModalDetalleUrl
    /** Audit fix S1: confirmación antes de abrir una URL no-SEGURO desbloqueada. */
    data object ConfirmarAbrirEnlace : ModalDetalleUrl
}

/**
 * Saver para [ModalDetalleUrl]: los `data object` no son Bundle-saveables,
 * por lo que [rememberSaveable] lanza `IllegalArgumentException` al
 * intentar persistir el [MutableState] (crash FATAL en main al abrir Detalle
 * tras escanear QR). Serializamos cada variante a su nombre estable.
 */
private val ModalDetalleUrlSaver: Saver<ModalDetalleUrl, String> = Saver(
    save = { state ->
        when (state) {
            ModalDetalleUrl.Ninguno -> "Ninguno"
            ModalDetalleUrl.ConfirmarDesbloqueo -> "ConfirmarDesbloqueo"
            ModalDetalleUrl.OkDesbloqueo -> "OkDesbloqueo"
            ModalDetalleUrl.ConfirmarBloqueo -> "ConfirmarBloqueo"
            ModalDetalleUrl.EliminarUrl -> "EliminarUrl"
            ModalDetalleUrl.ConfirmarAbrirEnlace -> "ConfirmarAbrirEnlace"
        }
    },
    restore = { name ->
        when (name) {
            "ConfirmarDesbloqueo" -> ModalDetalleUrl.ConfirmarDesbloqueo
            "OkDesbloqueo" -> ModalDetalleUrl.OkDesbloqueo
            "ConfirmarBloqueo" -> ModalDetalleUrl.ConfirmarBloqueo
            "EliminarUrl" -> ModalDetalleUrl.EliminarUrl
            "ConfirmarAbrirEnlace" -> ModalDetalleUrl.ConfirmarAbrirEnlace
            else -> ModalDetalleUrl.Ninguno
        }
    }
)

@Composable
fun PantallaDetalleUrl(
    id: String,
    onVerAnalisisAnteriores: (urlLimpia: String, idActual: String) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: DetalleUrlViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val contexto = LocalContext.current

    // P1: estado del modal en un solo state tipado. Solo un modal activo por
    // vez. rememberSaveable (audit fix P1): sobrevive rotacion/process death
    // — antes un `remember` cerraba el modal abierto al rotar.
    // Saver custom: los `data object` de [ModalDetalleUrl] NO son
    // Bundle-saveables (crash FATAL IllegalArgumentException en main al
    // entrar a DetalleUrl tras escanear QR). Usamos [ModalDetalleUrlSaver]
    // para mapear cada variante a un String.
    var modalActiva by rememberSaveable(stateSaver = ModalDetalleUrlSaver) {
        mutableStateOf<ModalDetalleUrl>(ModalDetalleUrl.Ninguno)
    }

    // Audit fix B5: bandera del desbloqueo pendiente. El modal de exito
    // (OkDesbloqueo) ya NO se muestra optimista al confirmar — se muestra
    // solo cuando el VM emite el mensaje EXITO de desbloqueo. Antes, si
    // desbloquearUrl fallaba, convivian el modal "URL desbloqueada" y el
    // snackbar "Error al desbloquear URL".
    // U9: rememberSaveable — con remember, una rotacion entre la
    // confirmacion y el EXITO del VM reseteaba la bandera y el modal de
    // exito nunca se abria (solo quedaba el snackbar).
    var desbloqueoPendiente by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(id) { viewModel.cargarEscaneo(id) }

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.mensaje.collect { mensaje ->
                // Audit fix B5: el modal de exito del desbloqueo se abre
                // SOLO cuando el VM confirma el EXITO (no al confirmar el
                // dialogo). Cualquier ERROR reinicia la bandera.
                if (desbloqueoPendiente) {
                    if (mensaje.tipo == TipoMensaje.EXITO) {
                        desbloqueoPendiente = false
                        modalActiva = ModalDetalleUrl.OkDesbloqueo
                    } else if (mensaje.tipo == TipoMensaje.ERROR) {
                        desbloqueoPendiente = false
                    }
                }
                onMensaje(mensaje.tipo, mensaje.texto)
            }
        }
    }

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.eliminarCompletado.collect { onBack() }
        }
    }

    // Audit fix B4: back del sistema desactivado mientras hay un modal
    // abierto — cierra el modal, no la pantalla entera.
    BackHandler(enabled = modalActiva == ModalDetalleUrl.Ninguno, onBack = onBack)

    // P2: hoist del urlLimpia una sola vez por recomposicion. Invariant:
    // si un modal esta activo, uiState DEBE ser Cargado (los modales solo
    // se disparan desde ContenidoDetalle, que requiere Cargado). El silent
    // fallback `?.let` no oculta un bug — cubre el edge case de race donde
    // uiState cambia a NoEncontrado/Cargando mientras el modal esta abierto
    // (p.ej. eliminado concurrente desde otro flujo): en ese caso cerramos
    // el modal sin disparar la accion, que es el comportamiento seguro.
    val urlLimpiaActual = (uiState as? DetalleUrlUiState.Cargado)?.escaneo?.urlLimpia

    Box(modifier = Modifier.fillMaxSize().background(CyberFondo)) {
        when (val estado = uiState) {
            is DetalleUrlUiState.Cargando -> ContenidoCargandoComun()
            is DetalleUrlUiState.NoEncontrado -> ContenidoNoEncontradoComun(
                mensaje = "Escaneo no encontrado",
                onBack = onBack
            )
            is DetalleUrlUiState.Cargado -> ContenidoDetalle(
                estado = estado,
                contexto = contexto,
                onBack = onBack,
                onVerAnalisisAnteriores = onVerAnalisisAnteriores,
                onSolicitarDesbloqueo = { modalActiva = ModalDetalleUrl.ConfirmarDesbloqueo },
                onSolicitarBloqueo = { modalActiva = ModalDetalleUrl.ConfirmarBloqueo },
                onSolicitarEliminar = { modalActiva = ModalDetalleUrl.EliminarUrl },
                onAbrirEnlace = { onInvalida ->
                    // Audit fix S1: nivel SEGURO → abre directo. Cualquier
                    // otro nivel (SOSPECHOSO/MALICIOSO desbloqueada) pide
                    // confirmación explícita ANTES de abrir el navegador —
                    // antes el botón abría sin advertencia en el momento del
                    // tap.
                    if (estado.escaneo.nivelAlertaEnum == NivelAlerta.SEGURO) {
                        val url = urlParaAbrir(estado.escaneo.urlOriginal, estado.escaneo.urlLimpia)
                        if (url == null) onInvalida() else abrirEnNavegador(contexto, url)
                    } else {
                        modalActiva = ModalDetalleUrl.ConfirmarAbrirEnlace
                    }
                },
                onMensaje = onMensaje
            )
        }
    }

    // P1: manejo exhaustivo del modal activo — el `when` forcea cobertura de
    // todos los casos y admite solo UNO activo a la vez.
    when (modalActiva) {
        ModalDetalleUrl.Ninguno -> Unit
        ModalDetalleUrl.ConfirmarDesbloqueo -> ModalDesbloqueoConfirmar(
            onConfirmar = {
                // Audit fix B5: cerrar el dialogo y marcar pendiente — el
                // modal OkDesbloqueo lo abre el colector de mensajes cuando
                // el VM confirma el exito real del desbloqueo.
                modalActiva = ModalDetalleUrl.Ninguno
                if (urlLimpiaActual != null) {
                    desbloqueoPendiente = true
                    viewModel.onAction(DetalleUrlAction.DesbloquearUrl(urlLimpiaActual))
                }
            },
            onCancelar = { modalActiva = ModalDetalleUrl.Ninguno }
        )
        ModalDetalleUrl.OkDesbloqueo -> ModalDesbloqueoOk(
            onCerrar = { modalActiva = ModalDetalleUrl.Ninguno }
        )
        ModalDetalleUrl.ConfirmarBloqueo -> ModalBloqueoConfirmar(
            onConfirmar = {
                modalActiva = ModalDetalleUrl.Ninguno
                urlLimpiaActual?.let {
                    viewModel.onAction(DetalleUrlAction.BloquearUrl(it, RepositorioUrlsBloqueadas.RAZON_MALICIOSA))
                }
            },
            onCancelar = { modalActiva = ModalDetalleUrl.Ninguno }
        )
        ModalDetalleUrl.EliminarUrl -> ModalEliminarUrl(
            onConfirmar = {
                modalActiva = ModalDetalleUrl.Ninguno
                urlLimpiaActual?.let { viewModel.onAction(DetalleUrlAction.EliminarUrl(it)) }
            },
            onCancelar = { modalActiva = ModalDetalleUrl.Ninguno }
        )
        ModalDetalleUrl.ConfirmarAbrirEnlace -> {
            // Audit fix S1: advertencia en el momento de abrir una URL que
            // NO fue clasificada como SEGURO (y está desbloqueada).
            // Refactor: cast unico `as? Cargado` — antes se repetia en el
            // cuerpo del modal (linea 224) y en onConfirmar (linea 235).
            val cargado = uiState as? DetalleUrlUiState.Cargado
            PlantillaModalConfirmacion(
                titulo = "Abrir enlace de riesgo",
                cuerpo = "Este enlace fue clasificado como " +
                    (cargado?.escaneo?.nivelAlertaEnum
                        ?: NivelAlerta.SOSPECHOSO).etiquetaAmenaza.lowercase() +
                    ". Ábrelo solo si confías en la fuente.",
                consecuencias = listOf(
                    "El contenido puede ser phishing o fraude",
                    "Podría intentar robarte credenciales o datos personales"
                ),
                textoBoton = "Abrir de todas formas",
                colorBoton = CyberRojo,
                onConfirmar = {
                    modalActiva = ModalDetalleUrl.Ninguno
                    val url = cargado?.let {
                        urlParaAbrir(it.escaneo.urlOriginal, it.escaneo.urlLimpia)
                    }
                    if (url == null) {
                        onMensaje(TipoMensaje.ERROR, "Enlace con esquema no permitido")
                    } else {
                        abrirEnNavegador(contexto, url)
                    }
                },
                onCancelar = { modalActiva = ModalDetalleUrl.Ninguno }
            )
        }
    }
}

@Composable
private fun ContenidoDetalle(
    estado: DetalleUrlUiState.Cargado,
    contexto: Context,
    onBack: () -> Unit,
    onVerAnalisisAnteriores: (String, String) -> Unit,
    onSolicitarDesbloqueo: () -> Unit,
    onSolicitarBloqueo: () -> Unit,
    onSolicitarEliminar: () -> Unit,
    onAbrirEnlace: (onInvalida: () -> Unit) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    val escaneo = estado.escaneo
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ─── Glass Pill Back Button ───
        GlassPillBackButton(onBack = onBack)

        // ─── Title Block ───
        Text(
            text = "Detalle del análisis",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal
        )

        // ─── URL Card ───
        TarjetaUrl(escaneo = escaneo, urlBloqueada = estado.urlBloqueada)

        // ─── Verdict Card ───
        TarjetaVeredicto(escaneo = escaneo)

        // ─── Actions ─── (solo en ultima version; los reescaneos son readOnly)
        if (estado.esUltimaVersion) {
            SeccionAcciones(
                estado = estado,
                contexto = contexto,
                onSolicitarDesbloqueo = onSolicitarDesbloqueo,
                onSolicitarBloqueo = onSolicitarBloqueo,
                onSolicitarEliminar = onSolicitarEliminar,
                onAbrirEnlace = onAbrirEnlace,
                onMensaje = onMensaje
            )
        }

        // ─── Versions Card ───
        if (estado.totalReescaneos > 0) {
            TarjetaVersiones(
                totalReescaneos = estado.totalReescaneos,
                onClick = { onVerAnalisisAnteriores(escaneo.urlLimpia, escaneo.id) }
            )
        }
    }
}
