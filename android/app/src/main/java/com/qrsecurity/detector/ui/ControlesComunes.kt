package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassVariant
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Controles UI compartidos por varias pantallas — extraidos por la pasada
 * thermo-nuclear (Blockers 3 y 5) para eliminar duplicacion verbatim entre
 * [PantallaDetalleUrl] / [PantallaDetalleVersionAntigua] / (potencialmente
 * otras pantallas de detalle que surjan).
 *
 * Antes cada pantalla re-declaraba su propio:
 *  - `ContenidoCargando` / `ContenidoCargandoVersionAntigua` (5 LOC identicos).
 *  - `ContenidoNoEncontrado` / `ContenidoNoEncontradoVersionAntigua` (~18 LOC
 *    que solo diffieren en el texto del mensaje).
 *  - Bloque `Glass Pill Back Button` inline (~21 LOC verbatim en
 *    `DetalleUrlScreen` + `DetalleVersionAntiguaContenido`).
 *
 * What lives here son componibles reutilizables que aceptan el minimo estado
 * necesario (callback / texto). No forwardar UiStates — mantener la boundary
 * explicita, segun el patron de [DetalleUrlTarjetas] (toma [EscaneoEntity]
 * y no el UiState para ser reusable en dos pantallas).
 *
 * KDoc por componente detalla el "por que" de la consolidacion.
 */

/**
 * Boton de retroceso estilo "glass pill" — Row con esquinas redondeadas 50,
 * fondo CyberGlass, icono ArrowBack + texto "Volver".
 *
 * Antes estaba inline verbatim en [PantallaDetalleUrl] (ContenidoDetalle) y en
 * [ContenidoDetalleVersionAntigua]. ~21 LOC duplicados que diffieren en cero.
 *
 * @param onBack Callback de retroceso navegable.
 */
@Composable
internal fun GlassPillBackButton(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
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

/**
 * Estado "Cargando" — Box fillMaxSize centrado con [CircularProgressIndicator]
 * tint CyberCyan.
 *
 * Antes: `ContenidoCargando` (DetalleUrlScreen) y
 * `ContenidoCargandoVersionAntigua` (DetalleVersionAntiguaScreen) eran 5 LOC
 * identicos verbatim. Unificados.
 */
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
 * Estado "No encontrado" — Box fillMaxSize centrado + Column con texto del
 * mensaje y boton "Volver".
 *
 * Antes: `ContenidoNoEncontrado` y `ContenidoNoEncontradoVersionAntigua`
 * eran ~18 LOC que diffieren unicamente en el texto del `Text` superior
 * ("Escaneo no encontrado" vs "Version no encontrada"). El boton "Volver"
 * y el resto del layout eran verbatim. La mensaje se hoist a parametro
 * para preservar la unica diferencia funcional.
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
 * Colores compartidos del [androidx.compose.material3.OutlinedTextField] en
 * los formularios (Login/Registro): superficie oscura (CyberGlassVariant),
 * borde cyan al enfocar, cursor cyan.
 *
 * Audit fix D4: el mismo bloque estaba duplicado verbatim 3 veces (2 en
 * LoginScreen + 1 en RegistroScreen como `fieldColors` privada).
 */
@Composable
internal fun coloresCampoTexto() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CyberGlassVariant,
    unfocusedContainerColor = CyberGlassVariant,
    focusedBorderColor = CyberCyan,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = CyberCyan,
    focusedTextColor = CyberTextoPrincipal,
    unfocusedTextColor = CyberTextoPrincipal
)
