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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.ui.theme.CyberAmbar
import com.qrsecurity.detector.ui.theme.CyberAmbarFondo
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
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
 * Modal de confirmacion de BLOQUEO de URL maliciosa.
 *
 * Espejo de [ModalDesbloqueoConfirmar] pero para la accion inversa (bloquear
 * manualmente una URL MALICIOSA desde DetalleUrlScreen). El auto-bloqueo
 * sucede automaticamente al escanear URL MALICIOSO (ver [com.qrsecurity.detector.pipeline.Pipeline.registrarEscaneoLocal]);
 * este modal cubre el caso de que el usuario haya desbloqueado antes y quiera
 * volver a bloquear sin reescanear.
 *
 * @param onConfirmar Callback al confirmar el bloqueo manual.
 * @param onCancelar Callback al cancelar.
 * @param modifier Modifier opcional.
 */
@Composable
fun ModalBloqueoConfirmar(
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
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
                .border(
                    width = 1.dp,
                    color = CyberGlassBorde,
                    shape = RoundedCornerShape(RadioBorde.xl)
                )
                .padding(Espaciado.xxl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ─── Step Indicator ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(CyberRojo)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(CyberGlassBorde)
                )
            }
            Text(
                text = "PASO 1 DE 2",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Title ───
            Text(
                text = "¿Bloquear esta URL?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Body ───
            Text(
                text = "Esta URL ha sido detectada como maliciosa.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Consecuencias List ───
            Column(
                verticalArrangement = Arrangement.spacedBy(Espaciado.md),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilaRiesgo("No se podrá abrir en el navegador")
                FilaRiesgo("Permanecerá bloqueada hasta que la desbloquees")
            }

            Spacer(modifier = Modifier.height(Espaciado.xs))

            // ─── Block Button ───
            Button(
                onClick = onConfirmar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberRojo,
                    contentColor = CyberFondo
                )
            ) {
                Text(
                    text = "Bloquear",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // ─── Cancel Button ───
            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CyberGlass,
                    contentColor = CyberTextoSecundario
                ),
                border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent)
            ) {
                Text(
                    text = "Cancelar",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Modal de confirmacion de desbloqueo de URL (Pencil frame WMtvW).
 *
 * F3.4: implementacion del layout de Pencil WMtvW. La firma NO debe cambiar.
 *
 * NOTA: El frame WMtvW original describia un desbloqueo de duracion limitada
 * y una nota de re-bloqueo automatico. Por decision de usuario, la
 * funcionalidad de desbloqueo temporal NO existe: el desbloqueo es permanente
 * hasta que el usuario vuelva a bloquear la URL. Toda referencia a desbloqueo
 * temporal se elimino de copy y notas.
 *
 * @param onConfirmar Callback al confirmar el desbloqueo manual.
 * @param onCancelar Callback al cancelar.
 * @param modifier Modifier opcional.
 */
@Composable
fun ModalDesbloqueoConfirmar(
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
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
                .border(
                    width = 1.dp,
                    color = CyberGlassBorde,
                    shape = RoundedCornerShape(RadioBorde.xl)
                )
                .padding(Espaciado.xxl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ─── Step Indicator ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(CyberCyan)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(CyberGlassBorde)
                )
            }
            Text(
                text = "PASO 1 DE 2",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Title ───
            Text(
                text = "¿Desbloquear esta URL?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Body ───
            Text(
                text = "SeguridadQR se desactivará para esta URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Risks List ───
            Column(
                verticalArrangement = Arrangement.spacedBy(Espaciado.md),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilaRiesgo("El destino podría ser malicioso")
                FilaRiesgo("No se analizará hasta que la desbloquees")
            }

            Spacer(modifier = Modifier.height(Espaciado.xs))

            // ─── Unlock Button ───
            Button(
                onClick = onConfirmar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyan,
                    contentColor = CyberFondo
                )
            ) {
                Text(
                    text = "Desbloquear",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // ─── Cancel Button ───
            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CyberGlass,
                    contentColor = CyberTextoSecundario
                ),
                border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent)
            ) {
                Text(
                    text = "Cancelar",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun FilaRiesgo(texto: String) {
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

/**
 * Modal de confirmacion de desbloqueo exitoso (Pencil frame Tw2qk).
 *
 * F3.4: implementacion del layout de Pencil Tw2qk. La firma NO debe cambiar.
 *
 * NOTA: El frame Tw2qk original incluia una nota de re-bloqueo automatico.
 * Por decision de usuario, esta nota se elimina (no existe funcionalidad
 * de re-bloqueo temporal automatico).
 *
 * @param onCerrar Callback al cerrar el modal.
 * @param modifier Modifier opcional.
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
                .border(
                    width = 1.dp,
                    color = CyberGlassBorde,
                    shape = RoundedCornerShape(RadioBorde.xl)
                )
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

            // ─── Title ───
            Text(
                text = "URL desbloqueada",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Body ───
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

            // ─── Got It Button ───
            Button(
                onClick = onCerrar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
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

/**
 * Modal de confirmacion de eliminacion de URL del historial.
 *
 * Elimina TODOS los escaneos (ultima version + reescaneos) de la URL
 * del historial local y encola DELETEs al backend via SyncWorker.
 * Es una accion destructiva e irreversible — por eso requiere confirmacion
 * explicita del usuario.
 *
 * Sigue el mismo patron visual que [ModalBloqueoConfirmar] y
 * [ModalDesbloqueoConfirmar] (overlay + card con step indicator, titulo,
 * cuerpo, lista de consecuencias, boton de accion rojo, boton de cancelar).
 *
 * @param onConfirmar Callback al confirmar la eliminacion.
 * @param onCancelar Callback al cancelar.
 * @param modifier Modifier opcional.
 */
@Composable
fun ModalEliminarUrl(
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
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
                .border(
                    width = 1.dp,
                    color = CyberGlassBorde,
                    shape = RoundedCornerShape(RadioBorde.xl)
                )
                .padding(Espaciado.xxl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ─── Step Indicator ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(CyberRojo)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(CyberGlassBorde)
                )
            }
            Text(
                text = "PASO 1 DE 2",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Title ───
            Text(
                text = "¿Eliminar esta URL?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Body ───
            Text(
                text = "Se eliminarán todos los análisis de esta URL del historial.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // ─── Consecuencias List ───
            Column(
                verticalArrangement = Arrangement.spacedBy(Espaciado.md),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilaRiesgo("Se borrarán todos los reescaneos de esta URL")
                FilaRiesgo("La acción no se puede deshacer")
            }

            Spacer(modifier = Modifier.height(Espaciado.xs))

            // ─── Delete Button ───
            Button(
                onClick = onConfirmar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberRojo,
                    contentColor = CyberFondo
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Spacer(modifier = Modifier.size(Espaciado.sm))
                Text(
                    text = "Eliminar",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // ─── Cancel Button ───
            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CyberGlass,
                    contentColor = CyberTextoSecundario
                ),
                border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent)
            ) {
                Text(
                    text = "Cancelar",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
