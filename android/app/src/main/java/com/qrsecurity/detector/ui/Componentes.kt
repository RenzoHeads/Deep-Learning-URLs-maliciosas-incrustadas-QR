package com.qrsecurity.detector.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import kotlinx.coroutines.flow.Flow

/**
 * Componentes estructurales compartidos (M13 — auditoría frontend): el
 * boilerplate de recolección de eventos one-shot (copiado con el nombre
 * cambiando en Login/Registro/Ajustes/Detalle) y la receta Card glass
 * (copiada a mano en varias pantallas).
 */

/**
 * Recolecta eventos one-shot de un canal del ViewModel mientras la pantalla
 * está STARTED — reemplaza el bloque `LaunchedEffect + repeatOnLifecycle +
 * collect` copiado verbatim en cada pantalla.
 *
 * La recolección se reinicia en cada ON_START (mismo contrato que el
 * boilerplate que reemplaza) y se cancela en ON_STOP; el Channel BUFFERED
 * del VM retiene los eventos emitidos mientras nadie colecciona.
 */
@Composable
internal fun <T> RecolectorEventos(
    eventos: Flow<T>,
    onEvento: (T) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(eventos) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            eventos.collect { onEvento(it) }
        }
    }
}

/**
 * Receta Card glass del design system — shape xxl, contenedor [CyberGlass],
 * sin elevación y contenido en Column con padding parametrizable.
 * Reemplaza la receta copiada a mano en las pantallas.
 */
@Composable
internal fun TarjetaCyber(
    modifier: Modifier = Modifier.fillMaxWidth(),
    paddingContenido: Dp = Espaciado.xl,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(RadioBorde.xxl),
        colors = CardDefaults.cardColors(containerColor = CyberGlass),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
    ) {
        Column(
            modifier = Modifier.padding(paddingContenido),
            content = contenido
        )
    }
}
