package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Estados de pantalla compartidos (S5 — descomposición de ControlesComunes.kt,
 * que mezclaba 4 concerns en 782 líneas): glass pill de back, estados de
 * carga/no-encontrado/vacío y el indicador de sync. Componentes de botones en
 * [Botones.kt], componentes de autenticación en [ControlesAuth.kt].
 */

/**
 * Boton de retroceso estilo "glass pill" — Row con esquinas redondeadas 50,
 * fondo CyberGlass, icono ArrowBack + texto "Volver".
 */
@Composable
internal fun GlassPillBackButton(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RadioBorde.pill)
            .background(CyberGlass)
            .clickable(onClick = onBack)
            .padding(horizontal = Espaciado.md, vertical = Espaciado.sm),
        horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Volver",
            tint = CyberTextoSecundario,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Text(
            text = "Volver",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoSecundario
        )
    }
}

/** Estado "Cargando" — Box fillMaxSize centrado con spinner tint CyberCyan. */
@Composable
internal fun ContenidoCargandoComun() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = CyberCyan)
    }
}

/**
 * Estado "No encontrado" — Box centrado + mensaje + boton "Volver".
 *
 * @param mensaje Texto del header (`titleMedium`). Default "No encontrado".
 * @param onBack Callback del boton "Volver".
 */
@Composable
internal fun ContenidoNoEncontradoComun(
    mensaje: String = "No encontrado",
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
        ) {
            Text(
                text = mensaje,
                style = MaterialTheme.typography.titleMedium,
                color = CyberTextoSecundario
            )
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(RadioBorde.lg)
            ) {
                Text(text = "Volver", color = CyberTextoPrincipal)
            }
        }
    }
}

/**
 * Estado vacío unificado — icono en círculo glass + título + descripción
 * opcional. Sobrecarga SIN call-to-action.
 */
@Composable
internal fun EstadoVacio(
    icono: ImageVector,
    titulo: String,
    descripcion: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    EstadoVacioCuerpo(icono, titulo, descripcion, cta = null, iconoBoton = null, modifier)
}

/**
 * Estado vacío CON call-to-action. M20 (auditoría frontend): el `onClick`
 * es obligatorio en esta sobrecarga — antes existía un default `onClick = {}`
 * que permitía un CTA visible que no hacía nada silenciosamente.
 */
@Composable
internal fun EstadoVacio(
    icono: ImageVector,
    titulo: String,
    descripcion: String? = null,
    textoBoton: String,
    iconoBoton: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    EstadoVacioCuerpo(
        icono, titulo, descripcion,
        cta = textoBoton to onClick,
        iconoBoton = iconoBoton,
        modifier = modifier
    )
}

@Composable
private fun EstadoVacioCuerpo(
    icono: ImageVector,
    titulo: String,
    descripcion: String?,
    cta: Pair<String, () -> Unit>?,
    iconoBoton: ImageVector?,
    modifier: Modifier
) {
    Column(
        modifier = modifier.padding(vertical = Espaciado.giganteM),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Box(
            modifier = Modifier
                .size(TamanosIcono.grande)
                .clip(RadioBorde.full)
                .background(CyberGlass),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icono,
                contentDescription = null,
                tint = CyberTextoSecundario,
                modifier = Modifier.size(TamanosIcono.mediano)
            )
        }
        Text(
            text = titulo,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = CyberTextoPrincipal,
            textAlign = TextAlign.Center
        )
        if (descripcion != null) {
            Text(
                text = descripcion,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario,
                textAlign = TextAlign.Center
            )
        }
        if (cta != null) {
            BotonCyber(
                texto = cta.first,
                onClick = cta.second,
                icono = iconoBoton
            )
        }
    }
}

/**
 * Indicador de sincronización unificado — pill glass con icono Sync + texto.
 *
 * @param texto Texto junto al icono (default "Sincronizando…").
 */
@Composable
internal fun EstadoSincronizacion(
    texto: String = "Sincronizando…"
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.xs),
        modifier = Modifier
            .background(CyberGlassAlto, RoundedCornerShape(RadioBorde.lg))
            .padding(horizontal = Espaciado.md, vertical = Espaciado.sm)
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = "Sincronizando",
            tint = CyberCyan,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Text(
            text = texto,
            style = MaterialTheme.typography.labelSmall,
            color = CyberTextoSecundario
        )
    }
}
