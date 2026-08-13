package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.ui.theme.CyberAmbar
import com.qrsecurity.detector.ui.theme.CyberAmbarFondo
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.PencilModalFondo
import com.qrsecurity.detector.ui.theme.PencilOverlay
import com.qrsecurity.detector.ui.theme.PencilSuccessTint
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Modales de desbloqueo (confirmacion + OK).
 *
 * - [ModalDesbloqueoConfirmar]: confirmacion previa — usa [PlantillaModalConfirmacion].
 * - [ModalDesbloqueoOk]: confirmacion de exito — shape distinto (icon verde
 *   CheckCircle + chip de advertencia + 1 boton "Listo"); NO usa la plantilla.
 *
 * NOTA: El desbloqueo es permanente hasta que el usuario vuelva a bloquear
 * la URL. No existe funcionalidad de desbloqueo temporal.
 */
@Composable
fun ModalDesbloqueoConfirmar(
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlantillaModalConfirmacion(
        titulo = "¿Desbloquear esta URL?",
        cuerpo = "SeguridadQR se desactivará para esta URL.",
        consecuencias = listOf(
            "El destino podría ser malicioso",
            "No se analizará hasta que la desbloquees"
        ),
        textoBoton = "Desbloquear",
        colorBoton = CyberCyan,
        onConfirmar = onConfirmar,
        onCancelar = onCancelar,
        modifier = modifier
    )
}

/**
 * Modal de confirmacion de desbloqueo exitoso (Pencil frame Tw2qk).
 *
 * NOTA: El frame original incluia una nota de re-bloqueo automatico. Por
 * decision de usuario, esta nota se elimina (no existe re-bloqueo temporal).
 */
@Composable
fun ModalDesbloqueoOk(
    onCerrar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PencilOverlay),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Espaciado.xxl)
                .clip(RoundedCornerShape(RadioBorde.xl))
                .background(PencilModalFondo)
                .border(width = 1.dp, color = CyberGlassBorde, shape = RoundedCornerShape(RadioBorde.xl))
                .padding(Espaciado.xxl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ─── Success Icon ───
            Box(
                modifier = Modifier
                    .size(TamanosIcono.grande)
                    .clip(CircleShape)
                    .background(PencilSuccessTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = CyberVerdeAlerta,
                    modifier = Modifier.size(TamanosIcono.mediano)
                )
            }

            Text(
                text = "URL desbloqueada",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "SeguridadQR está desactivado para esta URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Risk Chip ───
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(RadioBorde.sm))
                    .background(CyberAmbarFondo)
                    .padding(horizontal = Espaciado.md, vertical = Espaciado.sm),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = CyberAmbar,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Text(
                    text = "Advertencia · sigue siendo riesgosa",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberAmbar
                )
            }

            Spacer(modifier = Modifier.height(Espaciado.xs))

            Button(
                onClick = onCerrar,
                modifier = Modifier.fillMaxWidth().height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyan,
                    contentColor = CyberFondo
                )
            ) {
                Text(
                    text = "Listo",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
