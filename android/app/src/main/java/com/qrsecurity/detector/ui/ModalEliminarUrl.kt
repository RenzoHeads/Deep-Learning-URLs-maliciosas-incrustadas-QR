package com.qrsecurity.detector.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qrsecurity.detector.ui.theme.CyberRojo

/**
 * Modal de confirmacion de eliminacion de URL del historial.
 *
 * Elimina TODOS los escaneos (ultima version + reescaneos) de la URL
 * del historial local y encola DELETEs al backend via SyncWorker.
 * Accion destructiva e irreversible — requiere confirmacion explicita.
 *
 * Layout reaprovechado via [PlantillaModalConfirmacion].
 */
@Composable
fun ModalEliminarUrl(
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlantillaModalConfirmacion(
        titulo = "¿Eliminar esta URL?",
        cuerpo = "Se eliminarán todos los análisis de esta URL del historial.",
        consecuencias = listOf(
            "Se borrarán todos los reescaneos de esta URL",
            "La acción no se puede deshacer"
        ),
        textoBoton = "Eliminar",
        colorBoton = CyberRojo,
        iconoBoton = Icons.Filled.Delete,
        onConfirmar = onConfirmar,
        onCancelar = onCancelar,
        modifier = modifier
    )
}
