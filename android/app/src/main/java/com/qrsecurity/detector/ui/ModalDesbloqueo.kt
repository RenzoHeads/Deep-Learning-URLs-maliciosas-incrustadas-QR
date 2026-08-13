package com.qrsecurity.detector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Modal de confirmacion de BLOQUEO de URL maliciosa + helper compartido.
 *
 * [ModalBloqueoConfirmar] es espejo de [ModalDesbloqueoConfirmar] para la
 * accion inversa (bloquear manualmente una URL MALICIOSA desde
 * DetalleUrlScreen). El auto-bloqueo sucede automaticamente al escanear
 * URL MALICIOSO; este modal cubre el caso de que el usuario haya
 * desbloqueado antes y quiera volver a bloquear.
 *
 * [FilaRiesgo] se define aqui y se reutiliza en los 4 modales de
 * confirmacion via [PlantillaModalConfirmacion].
 *
 * Layout reaprovechado via [PlantillaModalConfirmacion].
 */
@Composable
fun ModalBloqueoConfirmar(
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlantillaModalConfirmacion(
        titulo = "¿Bloquear esta URL?",
        cuerpo = "Esta URL ha sido detectada como maliciosa.",
        consecuencias = listOf(
            "No se podrá abrir en el navegador",
            "Permanecerá bloqueada hasta que la desbloquees"
        ),
        textoBoton = "Bloquear",
        colorBoton = CyberRojo,
        onConfirmar = onConfirmar,
        onCancelar = onCancelar,
        modifier = modifier
    )
}

/**
 * Fila de riesgo compartida por los 4 modales de confirmacion via
 * [PlantillaModalConfirmacion] — Warning icon + texto. Marco visual
 * consistente para listar consecuencias de una accion destructiva.
 */
@Composable
internal fun FilaRiesgo(texto: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = CyberRojo,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Text(
            text = texto,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoPrincipal
        )
    }
}
