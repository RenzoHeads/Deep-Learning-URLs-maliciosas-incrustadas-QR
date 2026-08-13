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
import com.qrsecurity.detector.ui.theme.CyberFondo
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
}

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

    LaunchedEffect(id) { viewModel.cargarEscaneo(id) }

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.mensaje.collect { mensaje -> onMensaje(mensaje.tipo, mensaje.texto) }
        }
    }

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.eliminarCompletado.collect { onBack() }
        }
    }

    BackHandler(onBack = onBack)

    // P1: estado del modal en un solo state tipado. Solo un modal activo por vez.
    var modalActiva by remember { mutableStateOf<ModalDetalleUrl>(ModalDetalleUrl.Ninguno) }

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
                modalActiva = ModalDetalleUrl.OkDesbloqueo
                urlLimpiaActual?.let { viewModel.onAction(DetalleUrlAction.DesbloquearUrl(it)) }
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
                    viewModel.onAction(DetalleUrlAction.BloquearUrl(it, "Detectada como maliciosa"))
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
