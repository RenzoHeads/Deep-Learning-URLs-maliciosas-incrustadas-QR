package com.qrsecurity.detector.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qrsecurity.detector.ui.theme.CyberRojo

/**
 * Modal de confirmacion para "Eliminar esta version" — variante de
 * [ModalEliminarUrl] con texto adaptado:
 *  - Titulo: "¿Eliminar esta versión?" (vs "¿Eliminar esta URL?")
 *  - Body: "Se eliminará esta versión del historial." (vs "todos los análisis")
 *  - Consecuencias: "La acción no se puede deshacer" (sin alusion a reescaneos
 *    porque aqui NO los borramos — solo esta version).
 *
 * Layout reaprovechado via [PlantillaModalConfirmacion].
 */
@Composable
internal fun ModalEliminarVersion(
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlantillaModalConfirmacion(
        titulo = "¿Eliminar esta versión?",
        cuerpo = "Se eliminará esta versión del historial. Las demás versiones de esta URL se conservarán.",
        consecuencias = listOf("La acción no se puede deshacer"),
        textoBoton = "Eliminar versión",
        colorBoton = CyberRojo,
        iconoBoton = Icons.Filled.Delete,
        onConfirmar = onConfirmar,
        onCancelar = onCancelar,
        modifier = modifier
    )
}
