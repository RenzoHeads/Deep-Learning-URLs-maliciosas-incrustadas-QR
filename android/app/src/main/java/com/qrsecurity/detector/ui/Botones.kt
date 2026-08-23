package com.qrsecurity.detector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.ui.theme.Alphas
import com.qrsecurity.detector.ui.theme.Borde
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Botones y chips del design system (S5 — descomposición de
 * ControlesComunes.kt, que mezclaba 4 concerns en 782 líneas).
 *
 * [BotonCyber] absorbió a `BotonSubmit` (S5): el submit de auth era la misma
 * receta con un estado `procesando` y una tipografía divergente (labelLarge
 * SIN Bold frente al Bold del botón primario) — dos "botones primarios" del
 * mismo design system con look distinto.
 */

/**
 * Chip base del design system — receta única para chips de estado/nivel:
 * fondo Alphas.medio del color semántico, radio sm, padding md/xs y
 * labelMedium Bold.
 *
 * @param texto Etiqueta corta del chip.
 * @param color Color semántico (nivel de alerta o acento).
 */
@Composable
internal fun ChipNivel(
    texto: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(RadioBorde.sm))
            .background(color.copy(Alphas.medio))
            .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
    ) {
        Text(
            text = texto,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/**
 * Botón primario unificado del design system — full-width, 56dp mínimo,
 * radio lg, color de contenedor parametrizable (teal para acciones, rojo
 * para destructivas) y estado `procesando` (spinner en lugar del contenido,
 * botón deshabilitado).
 *
 * @param texto Label del botón (labelLarge Bold).
 * @param onClick Callback de pulsación.
 * @param icono Icono opcional antes del texto.
 * @param contenedor Color de fondo (default teal [CyberCyan]).
 * @param contenido Color de texto/icono/spinner (default [CyberFondo]).
 * @param habilitado Estado enabled del botón.
 * @param procesando Si true, muestra [CircularProgressIndicator] y deshabilita.
 * @param modifier Modifier externo (default fillMaxWidth).
 */
@Composable
internal fun BotonCyber(
    texto: String,
    onClick: () -> Unit,
    icono: ImageVector? = null,
    contenedor: Color = CyberCyan,
    contenido: Color = CyberFondo,
    habilitado: Boolean = true,
    procesando: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Button(
        onClick = onClick,
        enabled = habilitado && !procesando,
        modifier = modifier.heightIn(min = TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = contenedor,
            contentColor = contenido
        )
    ) {
        if (procesando) {
            CircularProgressIndicator(
                modifier = Modifier.size(TamanosIcono.estandar),
                color = contenido,
                strokeWidth = 2.dp
            )
        } else {
            if (icono != null) {
                Icon(
                    imageVector = icono,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Spacer(modifier = Modifier.width(Espaciado.sm))
            }
            Text(
                text = texto,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Variante outline de [BotonCyber] — mismo target táctil mínimo (56dp),
 * radio lg y labelLarge Bold, con borde fino y contenido en el color de
 * acento.
 *
 * M9 (auditoría frontend): `contenedor` y `borde` parametrizables permiten
 * cubrir las tres recetas OutlinedButton que DetalleUrlAcciones mantenía a
 * mano (Compartir/Eliminar/BotonToggleBloqueo diferían solo en
 * colores/icono/etiqueta).
 *
 * @param texto Label del botón (labelLarge Bold).
 * @param onClick Callback de pulsación.
 * @param icono Icono opcional antes del texto.
 * @param colorAcento Color del contenido (default teal [CyberCyan]).
 * @param contenedor Color de fondo (default [CyberGlass]).
 * @param borde Color del BorderStroke (default: el mismo [colorAcento]).
 * @param habilitado Estado enabled del botón.
 * @param modifier Modifier externo (default fillMaxWidth).
 */
@Composable
internal fun BotonCyberOutline(
    texto: String,
    onClick: () -> Unit,
    icono: ImageVector? = null,
    colorAcento: Color = CyberCyan,
    contenedor: Color = CyberGlass,
    borde: Color? = null,
    habilitado: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedButton(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.heightIn(min = TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.lg),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = contenedor,
            contentColor = colorAcento
        ),
        border = BorderStroke(Borde.fino, borde ?: colorAcento)
    ) {
        if (icono != null) {
            Icon(
                imageVector = icono,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
        }
        Text(
            text = texto,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}
