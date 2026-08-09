package com.qrsecurity.detector.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojoClaro
import com.qrsecurity.detector.ui.theme.CyberRojoFondo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberVerdeAlertaClaro
import com.qrsecurity.detector.ui.theme.CyberVerdeFondo

enum class TipoMensaje { EXITO, ERROR, INFO }

/**
 * Wrapper para mensajes de UI (snackbar). Tipo + texto.
 *
 * Compartido por todos los ViewModels que emiten snackbars via Channel.
 */
data class MensajeUi(
    val tipo: TipoMensaje,
    val texto: String
)

/**
 * Snackbar host global con styling cyber-sentinel.
 * Colorea el fondo segun TipoMensaje (actionLabel).
 */
@Composable
fun SnackbarHostCyber(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data: SnackbarData ->
        val tipo = data.visuals.actionLabel?.let {
            runCatching { TipoMensaje.valueOf(it) }.getOrNull()
        }
        val (bgColor, iconColor, icon) = when (tipo) {
            TipoMensaje.EXITO -> Triple(CyberVerdeFondo, CyberVerdeAlertaClaro, Icons.Filled.CheckCircle)
            TipoMensaje.ERROR -> Triple(CyberRojoFondo, CyberRojoClaro, Icons.Filled.Error)
            else -> Triple(CyberGlassAlto, CyberCyan, Icons.Filled.Info)
        }
        Snackbar(
            containerColor = bgColor,
            contentColor = CyberTextoPrincipal,
            shape = RoundedCornerShape(RadioBorde.lg),
            modifier = Modifier.border(
                width = Elevacion.sutil,
                color = CyberGlassBorde,
                shape = RoundedCornerShape(RadioBorde.lg)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = Espaciado.xs)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.padding(end = Espaciado.sm)
                )
                Text(text = data.visuals.message)
            }
        }
    }
}
