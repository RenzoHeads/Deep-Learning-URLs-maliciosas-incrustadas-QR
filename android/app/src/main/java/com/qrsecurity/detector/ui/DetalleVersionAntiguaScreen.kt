package com.qrsecurity.detector.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.ui.theme.CyberFondo

/**
 * Pantalla dedicada para visualizar UNA version historica especifica de
 * una URL (NO la ultima version).
 *
 * Diferencia vs [PantallaDetalleUrl]:
 *  - NO muestra el boton "Ver versiones de este analisis" (loop nav).
 *  - NO muestra botones de Bloquear/Desbloquear (esos aplican a la URL
 *    como entidad, no a una version individual).
 *  - NO muestra el boton "Abrir enlace" / "Compartir".
 *  - Muestra la fecha del escaneo (clave para distinguir versiones).
 *  - Boton unico: "Eliminar esta version" → elimina el escaneo
 *    individual por id (NO cascada por urlLimpia).
 *
 * Contenido principal en [DetalleVersionAntiguaContenido.kt], modal en
 * [DetalleVersionAntiguaModal.kt].
 *
 * @param id UUID del escaneo (version historica) a visualizar.
 * @param onBack Callback para volver atras (popBackStack a AnalisisAnteriores).
 * @param onMensaje Callback para snackbars.
 * @param viewModel VM de detalle de version antigua (Hilt, inyectado).
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

    LaunchedEffect(id) { viewModel.cargarEscaneo(id) }

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.mensaje.collect { mensaje ->
                onMensaje(mensaje.tipo, mensaje.texto)
            }
        }
    }

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.eliminarCompletado.collect { onBack() }
        }
    }

    BackHandler(onBack = onBack)

    var modalEliminarVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(CyberFondo)) {
        when (val estado = uiState) {
            is DetalleVersionAntiguaUiState.Cargando -> ContenidoCargandoComun()
            is DetalleVersionAntiguaUiState.NoEncontrado -> ContenidoNoEncontradoComun(
                mensaje = "Versión no encontrada",
                onBack = onBack
            )
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
