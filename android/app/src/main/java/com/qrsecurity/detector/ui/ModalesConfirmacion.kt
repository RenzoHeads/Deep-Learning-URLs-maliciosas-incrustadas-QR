package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.qrsecurity.detector.ui.theme.CyberAmbar
import com.qrsecurity.detector.ui.theme.CyberAmbarFondo
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.PencilSuccessTint
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Modales de confirmacion de la pantalla de Detalle.
 *
 * Consolidacion (auditoria frontend v2, E1): los 5 modales vivian dispersos
 * en 3 archivos con nombres que mentian — `ModalDesbloqueo.kt` contenia el
 * modal de BLOQUEO, `ModalDesbloqueoConfirmarOk.kt` mezclaba dos modales y
 * `ModalEliminarUrl.kt` tambien alojaba el de version. Se unifican aqui:
 *  - [ModalBloqueoConfirmar], [ModalDesbloqueoConfirmar],
 *    [ModalEliminarUrl] y [ModalEliminarVersion] son envoltorios delgados
 *    de [PlantillaModalConfirmacion] (difieren solo en textos/color/icono).
 *  - [ModalDesbloqueoOk] es el modal de exito (shape distinto, usa
 *    [ContenedorModalCyber] directo).
 *
 * El desbloqueo es manual y permanente (no existe desbloqueo temporal).
 * Tras confirmar el desbloqueo se muestra [ModalDesbloqueoOk].
 */

/**
 * Modal de confirmacion de BLOQUEO de URL maliciosa.
 *
 * Espejo de [ModalDesbloqueoConfirmar] para la accion inversa (bloquear
 * manualmente una URL MALICIOSA desde DetalleUrlScreen). El auto-bloqueo
 * sucede automaticamente al escanear URL MALICIOSO; este modal cubre el
 * caso de que el usuario haya desbloqueado antes y quiera volver a bloquear.
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
 * Modal de confirmacion de DESBLOQUEO de URL. Usa [PlantillaModalConfirmacion].
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
    ContenedorModalCyber(modifier = modifier) {
        // ─── Success Icon ───
        Box(
            modifier = Modifier
                .size(TamanosIcono.grande)
                .clip(RadioBorde.full)
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

        BotonCyber(
            texto = "Listo",
            onClick = onCerrar
        )
    }
}

/**
 * Modal de confirmacion de eliminacion de URL del historial.
 *
 * Elimina TODOS los escaneos (ultima version + reescaneos) de la URL
 * del historial local y encola DELETEs al backend via SyncWorker.
 * Accion destructiva e irreversible — requiere confirmacion explicita.
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

/**
 * Modal de confirmacion para "Eliminar esta version" — variante de
 * [ModalEliminarUrl] con texto adaptado (solo borra ESTA version, no la
 * cascada por URL).
 */
@Composable
fun ModalEliminarVersion(
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