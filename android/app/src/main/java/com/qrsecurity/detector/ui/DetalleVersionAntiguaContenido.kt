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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Contenido principal del detalle de version antigua — extraido a archivo
 * separado para mantener [PantallaDetalleVersionAntigua] bajo 250 LOC.
 *
 * Layout: glass pill back, title, URL card, date card, verdict card, delete button.
 *
 * Reusa componentes compartidos con DetalleUrl:
 *  - [TarjetaUrl] (URL card) con `urlBloqueada = false` (en version antigua
 *    el chip solo muestra el nivelAlerta historico, no el estado de bloqueo
 *    actual de la URL — preserva el comportamiento del antiguo ChipNivelAlerta).
 *  - [TarjetaVeredicto] (gauge + amenaza) — misma visualizacion que DetalleUrl.
 *
 * Componente unico (no aparece en DetalleUrl): Date card — diferencia visual
 * clave entre versiones del mismo escaneo.
 */

@Composable
internal fun ContenidoDetalleVersionAntigua(
    escaneo: EscaneoEntity,
    onBack: () -> Unit,
    onSolicitarEliminar: () -> Unit
) {
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

        // ─── URL Card (shared) ───
        // urlBloqueada=false:el chip muestra solo el nivelAlerta historico,
        // no el estado de bloqueo actual (preserva el comportamiento del
        // antiguo ChipNivelAlerta que no tenia flag urlBloqueada).
        TarjetaUrl(escaneo = escaneo, urlBloqueada = false)

        // ─── Date Card ───
        // Diferenciador clave entre versiones: DetalleUrl no lo muestra
        // porque para la ultima version no es informativo.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RadioBorde.xxl))
                .background(CyberGlass)
                .border(width = 1.dp, color = CyberGlassBorde, shape = RoundedCornerShape(RadioBorde.xxl))
                .padding(Espaciado.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
        ) {
            Box(
                modifier = Modifier
                    .size(TamanosIcono.mediano)
                    .clip(CircleShape)
                    .background(CyberGlassAlto),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
                Text(
                    text = "Fecha del escaneo",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = CyberTextoSecundario
                )
                Text(
                    text = formatoFechaEscaneo(escaneo.creadoEnMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = CyberTextoPrincipal
                )
            }
        }

        // ─── Verdict Card (shared) ───
        TarjetaVeredicto(escaneo = escaneo)

        // ─── Delete Button (destructivo) ───
        // Unica accion disponible en esta pantalla. Elimina SOLO esta
        // version (por id), no todas las versiones de la URL.
        Spacer(modifier = Modifier.height(Espaciado.lg))
        Button(
            onClick = onSolicitarEliminar,
            modifier = Modifier.fillMaxWidth().height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberRojo,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.size(Espaciado.sm))
            Text(
                text = "Eliminar esta versión",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
