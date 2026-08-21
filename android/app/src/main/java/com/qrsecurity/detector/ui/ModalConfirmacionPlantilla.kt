package com.qrsecurity.detector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.ui.theme.Borde
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.PencilModalFondo
import com.qrsecurity.detector.ui.theme.PencilOverlay
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Plantilla unificada para los modales de confirmacion de accion
 * (bloqueo / desbloqueo / eliminacion de URL / eliminacion de version).
 *
 * Reemplaza ~700 LOC de boilerplate duplicado entre [ModalBloqueoConfirmar],
 * [ModalDesbloqueoConfirmar], [ModalEliminarUrl] y [ModalEliminarVersion]
 * que compartian la misma estructura (overlay scrim → container glass →
 * titulo → cuerpo → lista de consecuencias → boton confirmar → boton
 * cancelar) diferenciandose solo en textos, color del boton y, en algunos
 * casos, icono del boton.
 *
 * **Por que NO muestra "PASO 1 DE 2"**: hoy ninguno de los 4 modales tiene
 * un "PASO 2" posterior — el step indicator miente sobre el flujo. Si se
 * anade una segunda etapa (p.ej. confirmacion escalonada), introducir un
 * parametro `mostrarStepIndicator: Boolean = false` y disear el segundo
 * modal como call-site separado con el indicator en el segundo estado.
 *
 * Las consecuencias se renderizan con [FilaRiesgo] (Warning icon + texto),
 * definida en `ModalDesbloqueo.kt` y reutilizada por los 4 modales.
 *
 * [ModalDesbloqueoOk] NO usa esta plantilla — es un modal de exito (icon
 * verde + 1 boton "Listo"), diferente shape.
 */
/**
 * Contenedor visual unificado de TODOS los modales — overlay scrim + columna
 * glass centrada (radio xl, fondo [PencilModalFondo], hairline glass).
 *
 * Extraído de [PlantillaModalConfirmacion] para que los modales de éxito
 * (ej. [ModalDesbloqueoOk]) compartan exactamente el mismo contenedor en
 * vez de clonarlo a mano.
 *
 * @param modifier Modifier externo del overlay.
 * @param contenido Contenido de la columna glass (ColumnScope).
 */
@Composable
internal fun ContenedorModalCyber(
    modifier: Modifier = Modifier,
    contenido: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().background(PencilOverlay),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Espaciado.xxl)
                .clip(RoundedCornerShape(RadioBorde.xl))
                .background(PencilModalFondo)
                .border(
                    width = Borde.fino,
                    color = CyberGlassBorde,
                    shape = RoundedCornerShape(RadioBorde.xl)
                )
                .padding(Espaciado.xxl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = contenido
        )
    }
}

@Composable
internal fun PlantillaModalConfirmacion(
    titulo: String,
    cuerpo: String,
    consecuencias: List<String>,
    textoBoton: String,
    colorBoton: Color,
    iconoBoton: ImageVector? = null,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier
) {
    ContenedorModalCyber(modifier = modifier) {
            Text(
                text = titulo,
                style = MaterialTheme.typography.headlineSmall,
                color = CyberTextoPrincipal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = cuerpo,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (consecuencias.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Espaciado.md),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    consecuencias.forEach { FilaRiesgo(it) }
                }
            }
            Spacer(modifier = Modifier.height(Espaciado.xs))
            BotonCyber(
                texto = textoBoton,
                onClick = onConfirmar,
                icono = iconoBoton,
                contenedor = colorBoton,
                contenido = CyberFondo
            )
            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier.fillMaxWidth().height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CyberGlass,
                    contentColor = CyberTextoSecundario
                ),
                border = BorderStroke(0.dp, Color.Transparent)
            ) {
                Text(
                    text = "Cancelar",
                    style = MaterialTheme.typography.labelLarge
                )
            }
    }
}
